package me.rerere.workspace

import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import java.util.regex.Pattern
import kotlin.random.Random

/**
 * 持久 Shell 会话: 维持一个长生命周期的 shell 进程(非交互模式, stdin 保持打开),
 * 命令逐条写入执行, 因此 cd / export / 后台进程(&)都会跨命令保留.
 *
 * 命令边界协议: 每条命令后追加一行带随机 nonce 的 sentinel 输出:
 *   printf '\n__RIKKA_EOF_<nonce>_%s__%s__\n' "$?" "$PWD"
 * nonce 防止命令自身输出伪造边界. reader 线程持续读 stdout 到共享缓冲,
 * exec 等待 sentinel 出现并解析 exitCode 与 cwd.
 *
 * 注意:
 *  - stderr 合并进 stdout (交互式 shell 无法可靠分流).
 *  - 命令若读取 stdin (cat/read 等) 或引号未闭合, 会吞掉后续输入 — 超时后整个会话销毁重建.
 *  - exec 前清空共享缓冲: 后台进程的持续输出不会把下一次 exec 的 mark 顶越界.
 */
class PersistentShellSession private constructor(
    private val process: Process,
    private val tag: String,
    private val nonce: String,
    private val killTreeWithSu: Boolean,
) {
    private val buffer = StringBuilder()
    private val lock = Object()

    @Volatile
    var dead = false
        private set

    @Volatile
    var currentCwd: String = ""
        private set

    private val sentinelPattern: Pattern =
        Pattern.compile("__RIKKA_EOF_${nonce}_(\\d+)__([^\\n]*?)__\\n")

    private val sentinelCmd =
        "printf '\\n__RIKKA_EOF_${nonce}_%s__%s__\\n' \"\$?\" \"\$PWD\""

    private val readerThread = Thread {
        try {
            process.inputStream.bufferedReader().use { reader ->
                val chunk = CharArray(4096)
                while (true) {
                    val read = reader.read(chunk)
                    if (read < 0) break
                    synchronized(lock) {
                        buffer.append(chunk, 0, read)
                        // 防御: 缓冲超过 4MB 时丢弃最旧的一半(exec 开头会清空, 这里只兜底)
                        if (buffer.length > MAX_BUFFER_CHARS) {
                            buffer.delete(0, buffer.length / 2)
                        }
                        lock.notifyAll()
                    }
                }
            }
        } catch (_: IOException) {
            // 进程被销毁时流关闭, read 抛异常属正常
        } finally {
            dead = true
            synchronized(lock) { lock.notifyAll() }
        }
    }.apply {
        isDaemon = true
        name = "PersistentShell-reader-$tag"
        start()
    }

    /**
     * 执行命令并等待完成. 阻塞调用 — 调用方应包 runInterruptible / withContext(IO).
     * @param cwd 非空时先 cd 到该目录; null 保持会话当前目录
     */
    fun exec(command: String, cwd: String?, timeoutMillis: Long): WorkspaceCommandResult {
        require(command.isNotBlank()) { "Command is required" }
        if (dead) {
            return WorkspaceCommandResult(-1, "", "shell session is dead", timedOut = false)
        }
        // 清空缓冲并从 0 开始定位: 后台进程残留输出不会干扰本次 mark
        synchronized(lock) { buffer.setLength(0) }
        val mark = 0
        try {
            val payload = buildString {
                if (!cwd.isNullOrBlank()) {
                    append("cd -- ").append(shellQuote(cwd)).append('\n')
                }
                append(command).append('\n')
                append(sentinelCmd).append('\n')
            }
            synchronized(lock) {
                process.outputStream.write(payload.toByteArray(Charsets.UTF_8))
                process.outputStream.flush()
            }
        } catch (e: IOException) {
            dead = true
            return WorkspaceCommandResult(-1, "", "failed to write to shell: ${e.message}", timedOut = false)
        }

        val deadline = System.currentTimeMillis() + timeoutMillis
        while (true) {
            // synchronized 是 inline 函数, 允许在块内直接 return
            synchronized(lock) {
                val found = sentinelPattern.matcher(buffer)
                found.region(mark, buffer.length)
                if (found.find()) {
                    val output = buffer.substring(mark, found.start())
                    buffer.delete(mark, found.end())
                    currentCwd = found.group(2)?.trim().orEmpty()
                    return buildResult(
                        exitCode = found.group(1)?.toIntOrNull() ?: -1,
                        rawOutput = output,
                    )
                }
                if (dead) {
                    val leftover = buffer.substring(mark.coerceAtMost(buffer.length))
                    buffer.setLength(0)
                    return WorkspaceCommandResult(-1, capOutput(leftover), "shell session terminated", timedOut = false)
                }
                if (System.currentTimeMillis() >= deadline) {
                    val partial = buffer.substring(mark.coerceAtMost(buffer.length))
                    buffer.setLength(0)
                    destroy()
                    return WorkspaceCommandResult(
                        exitCode = -1,
                        stdout = capOutput(partial),
                        stderr = "command timed out after ${timeoutMillis}ms; shell session was killed and will be recreated on next call",
                        timedOut = true,
                    )
                }
                val remaining = deadline - System.currentTimeMillis()
                if (remaining > 0) {
                    try {
                        lock.wait(remaining.coerceAtMost(500L))
                    } catch (e: InterruptedException) {
                        destroy()
                        throw e
                    }
                }
            }
        }
    }

    /** 输出上限截断(头+尾), 防止超大输出撑爆 LLM 上下文 */
    private fun buildResult(exitCode: Int, rawOutput: String): WorkspaceCommandResult {
        val trimmed = rawOutput.trimEnd('\n')
        val (capped, truncated) = capOutputWithFlag(trimmed)
        return WorkspaceCommandResult(
            exitCode = exitCode,
            stdout = capped,
            stderr = "",
            timedOut = false,
            truncated = truncated,
        )
    }

    private fun capOutput(text: String): String = capOutputWithFlag(text).first

    private fun capOutputWithFlag(text: String): Pair<String, Boolean> {
        if (text.length <= MAX_OUTPUT_CHARS) return text to false
        val head = text.take(HEAD_CHARS)
        val tail = text.takeLast(TAIL_CHARS)
        val omitted = text.length - HEAD_CHARS - TAIL_CHARS
        return "$head\n... [省略中间 $omitted 字符] ...\n$tail" to true
    }

    fun destroy() {
        dead = true
        // 先让 shell 自然退出: 关闭 stdin 的同时补一条 exit, 多数 shell 会立即终止
        try {
            process.outputStream.write("exit\n".toByteArray())
            process.outputStream.flush()
            process.outputStream.close()
        } catch (_: IOException) {
        }
        // 温和 destroy(给进程组退出机会)
        process.destroy()
        val exited = try {
            process.waitFor(300, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            false
        }
        if (!exited && killTreeWithSu) {
            // su 会话: destroy 只杀 su 客户端进程, root shell 及其子孙会变孤儿常驻。
            // 借另一个 su 命令按 /proc 的 ppid 链递归杀整棵进程树。
            killTreeAsRoot()
        }
        if (process.isAlive) {
            process.destroyForcibly()
        }
        synchronized(lock) { lock.notifyAll() }
    }

    /** 用 root 权限递归杀进程树(仅 su 会话兜底用) */
    private fun killTreeAsRoot() {
        val pid = runCatching { process.pid() }.getOrNull() ?: return
        // /proc/<pid>/stat 第 4 列是 ppid; comm 列可能含空格, 用 ')' 之后的内容解析更稳
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
        private const val MAX_BUFFER_CHARS = 4 * 1024 * 1024
        private const val MAX_OUTPUT_CHARS = 128 * 1024
        private const val HEAD_CHARS = MAX_OUTPUT_CHARS * 3 / 4
        private const val TAIL_CHARS = MAX_OUTPUT_CHARS - HEAD_CHARS

        fun start(
            tag: String,
            builder: ProcessBuilder,
            killTreeWithSu: Boolean = false,
        ): PersistentShellSession {
            val process = builder
                .redirectErrorStream(true)
                .start()
            val nonce = (1..8).map { "abcdefghijklmnopqrstuvwxyz0123456789"[Random.nextInt(36)] }.joinToString("")
            return PersistentShellSession(process, tag, nonce, killTreeWithSu)
        }

        private fun shellQuote(path: String): String =
            "'" + path.replace("'", "'\\''") + "'"
    }
}

/**
 * 持久会话注册表: 按 workspace (或宿主机 root) 复用会话, 空闲超时回收.
 * per-key 锁: 同一会话串行执行, 不同会话并行, 且锁可中断(协程取消生效).
 */
class ShellSessionManager(
    private val baseDir: File,
    private val nativeLibraryDir: File,
    private val extraBindMounts: List<WorkspaceBindMount> = emptyList(),
    private val rootModeProvider: () -> Boolean = { false },
    private val patcher: RootfsPatcher = RootfsPatcher(),
) {
    private val sessions = ConcurrentHashMap<String, PersistentShellSession>()
    private val lastUsedAt = ConcurrentHashMap<String, Long>()
    private val locks = ConcurrentHashMap<String, ReentrantLock>()
    private val createLock = Any()

    /**
     * 在持久会话中执行命令.
     * @param root workspace root 名; null 表示宿主机 root 会话(与具体 workspace 无关)
     * @param cwd 相对 workspace files 区的目录(proot 模式映射到 /workspace 下, root 模式映射为真实路径);
     *            null 保持会话当前目录
     */
    fun exec(
        root: String?,
        command: String,
        cwd: String?,
        timeoutMillis: Long,
    ): WorkspaceCommandResult {
        val key = sessionKey(root)
        val keyLock = locks.getOrPut(key) { ReentrantLock(true) }
        // lockInterruptibly: runInterruptible 取消时能打断, 避免被取消的命令仍继续执行
        keyLock.lockInterruptibly()
        try {
            var session = sessions[key]
            if (session == null || session.dead) {
                session?.destroy()
                session = synchronized(createLock) {
                    sessions[key]?.takeIf { !it.dead } ?: createSession(root).also { sessions[key] = it }
                }
            }
            lastUsedAt[key] = System.currentTimeMillis()
            val mappedCwd = mapCwd(root, cwd)
            val result = session.exec(command, mappedCwd, timeoutMillis)
            if (session.dead) {
                sessions.remove(key)
            }
            return result
        } finally {
            keyLock.unlock()
        }
    }

    /** 宿主机 root 模式持久会话 (root_shell 工具用) */
    fun execHostRoot(command: String, cwd: String?, timeoutMillis: Long): WorkspaceCommandResult =
        exec(null, command, cwd, timeoutMillis)

    /** 该 root 是否已有存活会话(用于决定是否应用默认 cwd) */
    fun hasSession(root: String?): Boolean {
        return sessions[sessionKey(root)]?.dead == false
    }

    /** 会话当前目录(来自最近一次 sentinel 的 $PWD) */
    fun currentCwd(root: String?): String {
        return sessions[sessionKey(root)]?.currentCwd.orEmpty()
    }

    /** 会话 key 携带执行模式: root 模式切换后旧 proot 会话不会被误用 */
    private fun sessionKey(root: String?): String {
        val mode = if (isRootMode()) "su" else "proot"
        return if (root == null) "host_root" else "ws:$mode:$root"
    }

    private fun mapCwd(root: String?, cwd: String?): String? {
        if (cwd.isNullOrBlank()) return null
        if (root == null) return cwd // 宿主机会话: cwd 原样(绝对路径)
        val trimmed = cwd.trim().trim('/')
        return if (isRootMode()) {
            // root 模式: 真实文件系统路径
            val base = File(File(baseDir, root), "files").absolutePath
            if (trimmed.isEmpty()) base else "$base/$trimmed"
        } else {
            // proot 模式: /workspace 相对路径
            if (trimmed.isEmpty()) "/workspace" else "/workspace/$trimmed"
        }
    }

    private fun createSession(root: String?): PersistentShellSession {
        return if (root == null || isRootMode()) {
            val builder = ProcessBuilder("su")
            val dir = initialHostDir(root)
            if (dir != null && dir.isDirectory) builder.directory(dir)
            // su 会话: destroy 杀不掉 root 子进程树, 需要 su 兜底清理, 防止 root 进程成孤儿
            PersistentShellSession.start(
                tag = if (root == null) "host-root" else "root:$root",
                builder = builder,
                killTreeWithSu = true,
            )
        } else {
            createProotSession(root)
        }
    }

    private fun initialHostDir(root: String?): File? {
        if (root == null) return null
        return File(File(baseDir, root), "files")
    }

    private fun createProotSession(root: String): PersistentShellSession {
        val wsDir = File(baseDir, root)
        val filesDir = File(wsDir, "files").apply { mkdirs() }
        val linuxDir = File(wsDir, "linux")
        val tempDir = File(wsDir, "tmp").apply { mkdirs() }
        require(File(linuxDir, "bin/sh").isFile) { "Rootfs is not installed" }
        val proot = File(nativeLibraryDir, "libproot_exec.so")
        val loader = File(nativeLibraryDir, "libproot_loader.so")
        require(proot.isFile) { "proot executable not found" }
        require(loader.isFile) { "proot loader not found" }

        patcher.patch(linuxDir)
        val args = mutableListOf(
            proot.absolutePath,
            "--root-id",
            "--link2symlink",
            "--kill-on-exit",
            "-r",
            linuxDir.absolutePath,
            "-w",
            "/workspace",
            "-b",
            "${filesDir.absolutePath}:/workspace",
        )
        extraBindMounts.forEach { mount ->
            if (mount.source.exists()) {
                args += "-b"
                args += "${mount.source.absolutePath}:${mount.target.trimEnd('/')}"
            }
        }
        listOf("/dev", "/proc", "/sys").forEach { path ->
            if (File(path).exists()) {
                args += "-b"
                args += path
            }
        }
        args += listOf(
            "/usr/bin/env",
            "-i",
            "HOME=/root",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "LC_ALL=C.UTF-8",
            "/bin/bash",
            "--noprofile",
            "--norc",
        )
        val builder = ProcessBuilder(args)
            .directory(filesDir)
        builder.environment()["PROOT_LOADER"] = loader.absolutePath
        builder.environment()["PROOT_TMP_DIR"] = tempDir.absolutePath
        builder.environment()["TMPDIR"] = tempDir.absolutePath
        return PersistentShellSession.start(tag = "proot:$root", builder = builder)
    }

    /** 回收空闲会话, 返回回收数量 */
    fun reapIdleSessions(maxIdleMillis: Long = IDLE_TIMEOUT_MS): Int {
        val now = System.currentTimeMillis()
        var reaped = 0
        for ((key, session) in sessions) {
            val idle = now - (lastUsedAt[key] ?: 0L)
            if (idle > maxIdleMillis || session.dead) {
                session.destroy()
                sessions.remove(key)
                lastUsedAt.remove(key)
                reaped++
            }
        }
        return reaped
    }

    fun close(root: String) {
        // 两种模式的会话都关掉
        listOf("ws:su:$root", "ws:proot:$root").forEach { key ->
            sessions.remove(key)?.destroy()
            lastUsedAt.remove(key)
        }
    }

    fun closeAll() {
        sessions.values.forEach { it.destroy() }
        sessions.clear()
        lastUsedAt.clear()
    }

    private fun isRootMode(): Boolean = rootModeProvider()

    companion object {
        const val IDLE_TIMEOUT_MS = 10 * 60 * 1000L
    }
}
