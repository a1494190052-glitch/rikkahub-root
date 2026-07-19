package me.rerere.rikkahub.service.shell

import android.util.Log
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.regex.Pattern

/**
 * 无头 PTY 会话: 复用 Termux TerminalSession 的真伪终端 (JNI createSubprocess),
 * 不挂 TerminalView, 供 pty_exec 工具以 expect 方式驱动交互式程序
 * (ssh 密码登录 / sudo 密码 / REPL / apt 确认提示等).
 *
 * 实现要点:
 * - TerminalSession 构造即创建 Handler, 必须在带 Looper 的线程 → Dispatchers.Main.
 * - 输出经 TerminalEmulator 屏幕模型读取: 干净文本无 ANSI 转义, 可直接喂给 LLM.
 * - expect/完成匹配只在屏幕尾部窗口进行(提示词总在底部), 最终结果才读全量 transcript.
 * - 完成协议: 收尾命令追加 printf '\n__PTY_DONE_%s__\n' "$?" 标记; tty 回显里
 *   该位置是字面 %s 不含纯数字, 只有真实输出才匹配 DONE_PATTERN.
 * - su 目标: finishIfRunning 只 SIGKILL 会话进程, 子进程树(如 ssh)靠
 *   记录的 shell pid 用另一个 su 一次性命令按 /proc ppid 链递归清理.
 */
class HeadlessPty(
    private val shellPath: String,
    private val cwd: String,
    private val args: Array<String>,
    private val env: Array<String>,
    private val killTreeWithSu: Boolean = false,
) {
    data class PtyResult(
        val exitCode: Int,
        val screen: String,
        val matchedExpects: Int,
        val timedOut: Boolean,
        val truncated: Boolean,
    )

    @Volatile
    private var session: TerminalSession? = null

    @Volatile
    private var shellPid: Int = -1

    @Volatile
    private var sessionFinished = false

    private val client = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) = Unit
        override fun onTitleChanged(changedSession: TerminalSession) = Unit
        override fun onSessionFinished(finishedSession: TerminalSession) {
            sessionFinished = true
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String) = Unit
        override fun onPasteTextFromClipboard(session: TerminalSession) = Unit
        override fun onBell(session: TerminalSession) = Unit
        override fun onColorsChanged(session: TerminalSession) = Unit
        override fun onTerminalCursorStateChange(state: Boolean) = Unit
        override fun getTerminalCursorStyle(): Int = TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE
        override fun logError(tag: String, message: String) = Unit
        override fun logWarn(tag: String, message: String) {
            Log.w(tag, message)
        }

        override fun logInfo(tag: String, message: String) = Unit
        override fun logDebug(tag: String, message: String) = Unit
        override fun logVerbose(tag: String, message: String) = Unit
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
            Log.w(tag, message, e)
        }

        override fun logStackTrace(tag: String, e: Exception) {
            Log.w(tag, "Terminal error", e)
        }
    }

    /** 必须在带 Looper 的线程构造 TerminalSession (内部创建 Handler) */
    private suspend fun start() = withContext(Dispatchers.Main) {
        val created = TerminalSession(shellPath, cwd, args, env, TRANSCRIPT_ROWS, client)
        created.mSessionName = "pty_exec"
        created.updateSize(COLUMNS, ROWS) // mEmulator 为空时会 initializeEmulator 并拉起进程
        session = created
    }

    private fun writeRaw(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        session?.write(bytes, 0, bytes.size)
    }

    /** 屏幕文本: maxRows 限制时只取底部窗口(transcript+屏幕), 否则全量 */
    private fun screenText(maxRows: Int): String {
        val emu = session?.emulator ?: return ""
        val screen = emu.screen ?: return ""
        val columns = emu.mColumns
        val rows = emu.mRows
        val firstRow = -screen.activeTranscriptRows
        val fromRow = if (maxRows == Int.MAX_VALUE) {
            firstRow
        } else {
            (rows - 1 - maxRows).coerceAtLeast(firstRow)
        }
        val sb = StringBuilder()
        for (r in fromRow until rows) {
            sb.append(screen.getSelectedText(0, r, columns - 1, r).trimEnd()).append('\n')
        }
        return sb.toString()
    }

    /**
     * 轮询屏幕尾部窗口直到 pattern 命中, 返回首个捕获组; 超时/会话死亡返回 null.
     */
    private suspend fun waitFor(pattern: Pattern, deadline: Long): String? {
        while (true) {
            val matcher = pattern.matcher(screenText(POLL_WINDOW_ROWS))
            if (matcher.find()) {
                return if (matcher.groupCount() >= 1) matcher.group(1) else ""
            }
            if (sessionFinished) return null
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) return null
            delay(remaining.coerceAtMost(POLL_INTERVAL_MS))
        }
    }

    /**
     * 驱动一次 expect 式交互:
     * 1. 记录 shell pid (su 树清理用) 2. 启动 command 3. 顺序应答 expects
     * 4. 执行 thenCommand + 完成标记, 取 exitCode 5. 返回全量屏幕文本(截断)
     */
    suspend fun runExpect(
        command: String,
        expects: List<Pair<Pattern, String>>,
        thenCommand: String?,
        timeoutMillis: Long,
    ): PtyResult {
        val deadline = System.currentTimeMillis() + timeoutMillis
        start()
        try {
            delay(SETTLE_MS)
            writeRaw(PID_CMD)
            waitFor(PID_PATTERN, deadline)?.let { shellPid = it.toIntOrNull() ?: -1 }

            writeRaw(command + "\n")
            var matched = 0
            for ((pattern, send) in expects) {
                waitFor(pattern, deadline)
                    ?: return buildResult(exitCode = -1, matched = matched, timedOut = true)
                writeRaw(if (send.endsWith("\n")) send else send + "\n")
                matched++
            }

            val tailCmd = buildString {
                if (!thenCommand.isNullOrBlank()) {
                    append(thenCommand).append('\n')
                }
                append(DONE_CMD)
            }
            writeRaw(tailCmd)
            val exitText = waitFor(DONE_PATTERN, deadline)
                ?: return buildResult(exitCode = -1, matched = matched, timedOut = true)
            delay(OUTPUT_DRAIN_MS) // 给标记后的尾输出一点落屏时间
            return buildResult(exitCode = exitText.toIntOrNull() ?: -1, matched = matched, timedOut = false)
        } finally {
            destroy()
        }
    }

    private fun buildResult(exitCode: Int, matched: Int, timedOut: Boolean): PtyResult {
        // 剔除内部协议标记行(pid/done), 保持喂给 LLM 的屏幕干净
        val raw = MARKER_LINE_REGEX.replace(screenText(Int.MAX_VALUE), "").trim()
        val (capped, truncated) = capScreen(raw)
        return PtyResult(exitCode, capped, matched, timedOut, truncated)
    }

    private fun capScreen(text: String): Pair<String, Boolean> {
        if (text.length <= MAX_SCREEN_CHARS) return text to false
        val head = text.take(HEAD_CHARS)
        val tail = text.takeLast(TAIL_CHARS)
        val omitted = text.length - HEAD_CHARS - TAIL_CHARS
        return "$head\n... [middle $omitted chars omitted] ...\n$tail" to true
    }

    suspend fun destroy() {
        val s = session ?: return
        try {
            if (s.isRunning) {
                writeRaw("exit\n")
                delay(200)
            }
        } catch (_: Throwable) {
        }
        try {
            s.finishIfRunning()
        } catch (_: Throwable) {
        }
        if (killTreeWithSu && shellPid > 0) {
            killTreeAsRoot(shellPid)
        }
        session = null
    }

    /** 与 PersistentShellSession 同款的 su 进程树兜底清理 */
    private fun killTreeAsRoot(pid: Int) {
        val script = """
            killtree() {
              p=${'$'}1
              for f in /proc/[0-9]*/stat; do
                ppid=${'$'}(awk -F')' '{print ${'$'}2}' ${'$'}f 2>/dev/null | awk '{print ${'$'}2}')
                [ "${'$'}ppid" = "${'$'}p" ] && killtree ${'$'}(basename ${'$'}f /stat)
              done
              kill -9 ${'$'}p 2>/dev/null
            }
            killtree $pid
        """.trimIndent()
        runCatching {
            val killer = Runtime.getRuntime().exec(arrayOf("su", "-c", script))
            if (!killer.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                killer.destroyForcibly()
            }
        }
    }

    companion object {
        private const val COLUMNS = 120
        private const val ROWS = 40
        private const val TRANSCRIPT_ROWS = 8000
        private const val POLL_WINDOW_ROWS = 60
        private const val POLL_INTERVAL_MS = 120L
        private const val SETTLE_MS = 350L
        private const val OUTPUT_DRAIN_MS = 250L

        private const val MAX_SCREEN_CHARS = 48 * 1024
        private const val HEAD_CHARS = MAX_SCREEN_CHARS * 3 / 4
        private const val TAIL_CHARS = MAX_SCREEN_CHARS - HEAD_CHARS

        private const val PID_CMD = "printf '__PTY_PID_%s__\\n' \"\$\$\"\n"
        private val PID_PATTERN: Pattern = Pattern.compile("__PTY_PID_(\\d+)__")
        private const val DONE_CMD = "printf '\\n__PTY_DONE_%s__\\n' \"\$?\"\n"
        private val DONE_PATTERN: Pattern = Pattern.compile("__PTY_DONE_(\\d+)__")
        private val MARKER_LINE_REGEX = Regex("[^\\n]*__PTY_(?:PID|DONE)_\\d+__[^\\n]*\\n?")
    }
}
