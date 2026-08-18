package me.rerere.rikkahub.acp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/**
 * 一个 ACP agent 子进程的连接：负责 spawn + stdin 写 + stdout 按行读 + stderr 落日志。
 *
 * ACP 走 stdio 的 NDJSON 线协议，stdout 是纯协议帧，stderr 必须是诊断日志，
 * 二者严格分离（把 stderr 并进 stdout 会污染协议帧）。
 *
 * 进程通过注入的 [processBuilderFactory] 创建（生产实现是 proot 工作区里的
 * agent 命令），这样连接层本身不依赖 Android。
 */
class AcpProcessConnection(
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val processBuilderFactory: suspend () -> ProcessBuilder,
) {
    private val inputChannel = Channel<String>(Channel.UNLIMITED)
    private val writeMutex = Mutex()
    private val stderrLock = Any()
    private val stderrTail = ArrayDeque<String>()

    private var process: Process? = null
    private var readerJob: Job? = null
    private var stderrJob: Job? = null
    private var waitJob: Job? = null
    private var writer: OutputStreamWriter? = null

    @Volatile
    private var closing = false

    /** stdout 协议帧流（每行一条 JSON-RPC）。 */
    val input: Flow<String> = inputChannel.receiveAsFlow()

    /** 进程退出码；进程意外退出时以异常完成。 */
    val exitSignal = CompletableDeferred<Int?>()

    val isRunning: Boolean
        get() = process?.isAlive == true

    suspend fun start() {
        if (isRunning) return
        closing = false
        val started = processBuilderFactory().start()
        process = started
        writer = OutputStreamWriter(started.outputStream, StandardCharsets.UTF_8)

        readerJob = scope.launch {
            try {
                lineFlow(started).collect { inputChannel.send(it) }
            } catch (error: IOException) {
                handleStreamReadFailure("stdout", error, started, terminateProcess = true)
            }
        }

        stderrJob = scope.launch(ioDispatcher) {
            try {
                started.errorStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        if (line.isNotBlank()) appendDiagnostic(line)
                    }
                }
            } catch (error: IOException) {
                handleStreamReadFailure("stderr", error, started, terminateProcess = false)
            }
        }

        waitJob = scope.launch(ioDispatcher) {
            val exitCode = runCatching { started.waitFor() }.getOrNull()
            exitSignal.complete(exitCode)
            if (process === started) {
                process = null
                inputChannel.close(
                    IllegalStateException("ACP agent exited with code $exitCode")
                )
            }
        }
    }

    suspend fun writeLine(line: String) {
        writeMutex.withLock {
            val output = writer
                ?: throw IllegalStateException("ACP agent stdin is closed")
            withContext(ioDispatcher) {
                output.write(line)
                output.write("\n")
                output.flush()
            }
        }
    }

    suspend fun close() {
        closing = true
        val current = process
        process = null
        readerJob?.cancel()
        stderrJob?.cancel()
        waitJob?.cancel()
        runCatching { writer?.close() }
        writer = null
        runCatching { current?.inputStream?.close() }
        runCatching { current?.errorStream?.close() }
        runCatching { current?.destroy() }
        readerJob?.join()
        stderrJob?.join()
        waitJob?.join()
        readerJob = null
        stderrJob = null
        waitJob = null
        inputChannel.close()
    }

    /** 最近一段 stderr 诊断文本，用于初始化失败时的报错。 */
    fun diagnosticSummary(): String {
        val stderr = synchronized(stderrLock) { stderrTail.joinToString("\n").trim() }
        return if (stderr.isBlank()) "" else "Agent stderr: ${stderr.takeLast(MAX_STDERR_CHARS)}"
    }

    private fun appendDiagnostic(message: String) {
        synchronized(stderrLock) {
            stderrTail.addLast(message)
            while (
                stderrTail.size > MAX_STDERR_LINES ||
                stderrTail.sumOf(String::length) > MAX_STDERR_CHARS
            ) {
                stderrTail.removeFirstOrNull()
            }
        }
    }

    private fun handleStreamReadFailure(
        streamName: String,
        error: IOException,
        started: Process,
        terminateProcess: Boolean,
    ) {
        if (closing || process !== started || !started.isAlive) return
        appendDiagnostic("$streamName reader failed: ${error.message ?: error.javaClass.simpleName}")
        if (terminateProcess) {
            exitSignal.complete(null)
            runCatching { started.destroy() }
        }
    }

    private fun lineFlow(p: Process): Flow<String> = flow {
        p.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isNotBlank()) emit(line)
            }
        }
    }.flowOn(ioDispatcher)

    private companion object {
        private const val MAX_STDERR_LINES = 60
        private const val MAX_STDERR_CHARS = 6_000
    }
}
