package me.rerere.workspace

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

interface WorkspaceShellRunner {
    fun execute(context: WorkspaceShellContext): WorkspaceCommandResult
}

data class WorkspaceShellContext(
    val root: String,
    val command: String,
    val cwd: String,
    val filesDir: File,
    val linuxDir: File,
    val tempDir: File,
    val workingDir: File,
    val timeoutMillis: Long,
    val stdin: ByteArray? = null,
)

class HostShellRunner : WorkspaceShellRunner {
    override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
        val process = ProcessBuilder(defaultShell(), "-c", context.command)
            .directory(context.workingDir)
            .redirectErrorStream(false)
            .start()
        return process.readResult(context.timeoutMillis, context.stdin)
    }

    private fun defaultShell(): String =
        if (File("/system/bin/sh").exists()) "/system/bin/sh" else "/bin/sh"
}

// 单个流保留的最大字符数, 防止命令疯狂输出导致 OOM 或撑爆 LLM 上下文
const val MAX_OUTPUT_CHARS = 128 * 1024

// 头部保留比例: 剩余容量分配给尾部(编译错误/最终结果往往在输出末尾)
private const val HEAD_OUTPUT_CHARS = MAX_OUTPUT_CHARS * 3 / 4
private const val TAIL_OUTPUT_CHARS = MAX_OUTPUT_CHARS - HEAD_OUTPUT_CHARS

fun Process.readResult(timeoutMillis: Long, stdin: ByteArray? = null): WorkspaceCommandResult {
    val stdout = StreamCollector(inputStream)
    val stderr = StreamCollector(errorStream)
    val stdinWriter = stdin?.let { bytes -> StreamWriter(outputStream, bytes) }
    try {
        val finished = waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        if (!finished) {
            destroyForcibly()
        }
        stdinWriter?.join(1_000)
        stdout.join(1_000)
        stderr.join(1_000)
        return WorkspaceCommandResult(
            exitCode = if (finished) exitValue() else -1,
            stdout = stdout.text(),
            stderr = stderr.text(),
            timedOut = !finished,
            truncated = stdout.truncated || stderr.truncated,
        )
    } catch (e: InterruptedException) {
        // 调用方线程被中断（如协程取消时的 runInterruptible），杀掉进程避免命令继续执行
        destroyForcibly()
        // 进程被杀后 stdout/stderr 会关闭, 这里 join 回收两个采集线程, 避免每次取消泄漏一对线程
        stdinWriter?.join(1_000)
        stdout.join(1_000)
        stderr.join(1_000)
        throw e
    }
}

private class StreamWriter(
    private val stream: java.io.OutputStream,
    private val bytes: ByteArray,
) {
    private val thread = Thread {
        try {
            stream.use { output ->
                output.write(bytes)
                output.flush()
            }
        } catch (_: IOException) {
            // 子进程提前退出或被强杀时 stdin 可能关闭, 忽略即可, 退出状态会由进程本身返回
        }
    }.apply {
        isDaemon = true
        start()
    }

    fun join(millis: Long) = thread.join(millis)
}

/**
 * 头+尾保留收集器: 头部最多 [headChars] 字符, 尾部滚动保留 [tailChars] 字符,
 * 中间被省略的字符计数并在 [text] 里以省略标记呈现.
 * 尾部往往是命令的最终结果/编译错误, 纯头部截断会丢掉最关键的信息.
 */
private class StreamCollector(
    stream: InputStream,
    private val headChars: Int = HEAD_OUTPUT_CHARS,
    private val tailChars: Int = TAIL_OUTPUT_CHARS,
) {
    private val head = StringBuilder()
    private val tail = StringBuilder()
    private var omittedChars = 0L

    @Volatile
    var truncated = false
        private set

    private val lock = Any()

    private val thread = Thread {
        try {
            stream.bufferedReader().use { reader ->
                val buffer = CharArray(4096)
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    // 持续读到 EOF (即使超上限), 否则管道写满会阻塞子进程导致其无法退出
                    synchronized(lock) {
                        var offset = 0
                        var remaining = read
                        val headRoom = headChars - head.length
                        if (headRoom > 0) {
                            val n = minOf(headRoom, remaining)
                            head.append(buffer, offset, n)
                            offset += n
                            remaining -= n
                        }
                        if (remaining > 0) {
                            tail.append(buffer, offset, remaining)
                            if (tail.length > tailChars) {
                                // 只有尾部也溢出(真正丢内容)时才标 truncated
                                val excess = tail.length - tailChars
                                tail.delete(0, excess)
                                omittedChars += excess
                                truncated = true
                            }
                        }
                    }
                }
            }
        } catch (_: IOException) {
            // 进程被强杀（超时/取消）时流会被关闭，阻塞中的 read 会抛 InterruptedIOException 等，
            // 保留已读取的内容即可；不能让异常逃逸，否则会触发线程默认异常处理导致应用崩溃
        }
    }.apply {
        // 设为 daemon: 即使 proot grandchild 残留 fd 导致 read() 永久阻塞, 也不会阻止 JVM 退出
        isDaemon = true
        start()
    }

    fun join(millis: Long) = thread.join(millis)

    fun text(): String = synchronized(lock) {
        if (omittedChars <= 0) {
            head.toString() + tail
        } else {
            buildString {
                append(head)
                append("\n... [省略中间 ").append(omittedChars).append(" 字符] ...\n")
                append(tail)
            }
        }
    }
}
