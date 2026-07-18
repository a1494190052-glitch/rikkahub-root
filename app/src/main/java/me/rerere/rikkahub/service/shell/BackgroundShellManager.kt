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
        val id = Uuid.random().toString().take(8)
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
        }
        return task
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
                .redirectOutput(logFile)
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
                .redirectOutput(logFile)
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

    private fun tail(file: File, lines: Int): String {
        val all = file.readLines()
        return if (all.size <= lines) {
            all.joinToString("\n")
        } else {
            "... [共 ${all.size} 行, 显示最后 $lines 行]\n" + all.takeLast(lines).joinToString("\n")
        }
    }

    companion object {
        private const val TAG = "BackgroundShellMgr"
    }
}
