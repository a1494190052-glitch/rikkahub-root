package me.rerere.rikkahub.service.shell

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.db.entity.ShellAuditEntity
import me.rerere.workspace.ProotShellRunner
import me.rerere.workspace.WorkspaceShellContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

data class ShellTask(
    val id: String,
    val workspaceId: String,
    val command: String,
    val cwd: String,
    val rootMode: Boolean,
    val startedAt: Long,
    val logFile: String,
    val auditId: String,
    @Volatile var status: String = STATUS_RUNNING,
    @Volatile var exitCode: Int? = null,
) {
    companion object {
        const val STATUS_RUNNING = "running"
        const val STATUS_DONE = "done"
        const val STATUS_KILLED = "killed"
        const val STATUS_ERROR = "error"
    }
}

/**
 * 后台 Shell 任务管理器: AI 可以启动长任务(下载/编译/起服务)后离开,
 * 之后通过 task id 轮询输出或杀掉. 输出实时写入日志文件, 进程独立于 App UI.
 * 注意: 任务表在内存中, App 重启后任务句柄丢失(日志文件仍保留在磁盘).
 */
class BackgroundShellManager(
    filesDir: File,
    private val workspacesDir: File,
    private val prootRunner: ProotShellRunner,
    private val rootModeProvider: () -> Boolean,
    private val auditLogger: ShellAuditLogger,
) {
    private val logsDir = File(filesDir, "shell_tasks").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tasks = ConcurrentHashMap<String, ShellTask>()
    private val processes = ConcurrentHashMap<String, Process>()

    suspend fun start(
        workspaceId: String,
        root: String,
        command: String,
        cwd: String = "",
    ): ShellTask {
        require(command.isNotBlank()) { "Command is required" }
        // id 碰撞防护: 极小概率下重试
        var id = Uuid.random().toString().take(8)
        while (tasks.putIfAbsent(id, PLACEHOLDER) != null) {
            id = Uuid.random().toString().take(8)
        }
        tasks.remove(id)
        val logFile = File(logsDir, "$id.log")
        val rootMode = rootModeProvider()
        val auditId = auditLogger.start(
            source = ShellAuditEntity.SOURCE_AI_BACKGROUND,
            command = command,
            cwd = cwd,
            workspaceId = workspaceId,
            taskId = id,
        )
        val task = ShellTask(
            id = id,
            workspaceId = workspaceId,
            command = command,
            cwd = cwd,
            rootMode = rootMode,
            startedAt = System.currentTimeMillis(),
            logFile = logFile.absolutePath,
            auditId = auditId,
        )
        val process = try {
            buildProcess(root, command, cwd, rootMode, logFile)
        } catch (e: Throwable) {
            task.status = ShellTask.STATUS_ERROR
            auditLogger.finish(auditId, task.startedAt, ShellAuditEntity.STATUS_ERROR, null, e.message)
            throw e
        }
        tasks[id] = task
        processes[id] = process
        scope.launch {
            val exit = runCatching { process.waitFor() }.getOrElse { -1 }
            val status = if (task.status == ShellTask.STATUS_KILLED) {
                ShellAuditEntity.STATUS_KILLED
            } else if (exit == 0) {
                task.status = ShellTask.STATUS_DONE
                ShellAuditEntity.STATUS_DONE
            } else {
                task.status = ShellTask.STATUS_ERROR
                ShellAuditEntity.STATUS_ERROR
            }
            task.exitCode = exit
            processes.remove(id)
            val tail = runCatching { tail(logFile, 40) }.getOrNull()
            auditLogger.finish(auditId, task.startedAt, status, exit, tail)
            Log.i(TAG, "background task $id finished: exit=$exit status=$status")
            reapCompletedTasks()
        }
        return task
    }

    /** 清理 24h 前完成的旧任务(防内存表无限膨胀; 日志文件保留) */
    private fun reapCompletedTasks(maxAgeMs: Long = 24 * 60 * 60 * 1000L) {
        val now = System.currentTimeMillis()
        tasks.values.removeIf { task ->
            task.status != ShellTask.STATUS_RUNNING && now - task.startedAt > maxAgeMs
        }
    }

    private fun buildProcess(
        root: String,
        command: String,
        cwd: String,
        rootMode: Boolean,
        logFile: File,
    ): Process {
        return if (rootMode) {
            val wsFiles = File(File(workspacesDir, root), "files")
            val workDir = if (cwd.isBlank()) wsFiles else File(wsFiles, cwd)
            ProcessBuilder("su", "-c", command)
                .directory(workDir.takeIf { it.isDirectory } ?: wsFiles)
                .redirectErrorStream(true)
                .redirectOutput(java.lang.ProcessBuilder.Redirect.appendTo(logFile))
                .start()
        } else {
            val wsDir = File(workspacesDir, root)
            val filesDir = File(wsDir, "files")
            val workDir = if (cwd.isBlank()) filesDir else File(filesDir, cwd)
            require(workDir.isDirectory) { "Working directory does not exist: $cwd" }
            val context = WorkspaceShellContext(
                root = root,
                command = command,
                cwd = cwd,
                filesDir = filesDir,
                linuxDir = File(wsDir, "linux"),
                tempDir = File(wsDir, "tmp"),
                workingDir = workDir,
                timeoutMillis = 0,
                stdin = null,
            )
            val builder = prootRunner.buildProcessBuilderOrNull(context)
                ?: error("Rootfs is not installed or proot is unavailable")
            builder
                .redirectErrorStream(true)
                .redirectOutput(java.lang.ProcessBuilder.Redirect.appendTo(logFile))
                .start()
        }
    }

    fun list(): List<ShellTask> = tasks.values.sortedByDescending { it.startedAt }

    fun get(id: String): ShellTask? = tasks[id]

    fun output(id: String, tailLines: Int = 200): String {
        val task = tasks[id] ?: error("Task not found: $id")
        val logFile = File(task.logFile)
        if (!logFile.isFile) return ""
        return tail(logFile, tailLines)
    }

    fun kill(id: String): Boolean {
        val task = tasks[id] ?: return false
        task.status = ShellTask.STATUS_KILLED
        processes[id]?.destroyForcibly()
        return true
    }

    /** 尾读: 只从文件末尾读, 几百 MB 的日志也不会 OOM; 超限时顺带截断保留尾部 */
    private fun tail(file: File, lines: Int): String {
        val length = file.length()
        if (length > MAX_LOG_BYTES) {
            truncateLogKeepTail(file, KEEP_LOG_BYTES)
        }
        val readFrom = (file.length() - TAIL_READ_BYTES).coerceAtLeast(0)
        val content = java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(readFrom)
            val bytes = ByteArray((file.length() - readFrom).toInt())
            raf.readFully(bytes)
            String(bytes)
        }
        val all = content.lines().let { if (readFrom > 0) it.drop(1) else it } // 丢弃首行残段
        return if (all.size <= lines) {
            (if (readFrom > 0) "... [仅显示日志尾部]\n" else "") + all.joinToString("\n")
        } else {
            "... [共 ${all.size}+ 行, 显示最后 $lines 行]\n" + all.takeLast(lines).joinToString("\n")
        }
    }

    /** 日志超限: 保留尾部(配合 appendTo 打开方式, 截断后进程续写无空洞) */
    private fun truncateLogKeepTail(file: File, keepBytes: Long) {
        runCatching {
            val tailBytes = java.io.RandomAccessFile(file, "r").use { raf ->
                val from = (file.length() - keepBytes).coerceAtLeast(0)
                raf.seek(from)
                ByteArray((file.length() - from).toInt()).also { raf.readFully(it) }
            }
            file.writeBytes(tailBytes)
        }
    }

    companion object {
        private const val TAG = "BackgroundShellMgr"
        private const val MAX_LOG_BYTES = 8L * 1024 * 1024
        private const val KEEP_LOG_BYTES = 2L * 1024 * 1024
        private const val TAIL_READ_BYTES = 512L * 1024

        /** id 碰撞占位 */
        private val PLACEHOLDER = ShellTask("", "", "", "", false, 0, "", "")
    }
}
