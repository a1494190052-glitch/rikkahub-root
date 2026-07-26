package me.rerere.rikkahub.service.shell

import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.dao.ShellAuditDAO
import me.rerere.rikkahub.data.db.entity.ShellAuditEntity
import kotlin.uuid.Uuid

/**
 * Shell 审计日志记录器: AI 工具/后台任务每次执行 shell 命令都会留下记录,
 * 用户在「Shell 日志」页可回看(含运行中任务的实时状态).
 */
class ShellAuditLogger(
    private val dao: ShellAuditDAO,
) {
    fun recentFlow(limit: Int = 300): Flow<List<ShellAuditEntity>> = dao.recentFlow(limit)

    fun runningFlow(): Flow<List<ShellAuditEntity>> = dao.runningFlow()

    /** 登记一条开始执行的记录, 返回审计 id */
    suspend fun start(
        source: String,
        command: String,
        cwd: String = "",
        workspaceId: String? = null,
        taskId: String? = null,
    ): String {
        val id = Uuid.random().toString()
        dao.insert(
            ShellAuditEntity(
                id = id,
                createdAt = System.currentTimeMillis(),
                source = source,
                workspaceId = workspaceId,
                command = command.take(MAX_COMMAND_CHARS),
                cwd = cwd,
                status = ShellAuditEntity.STATUS_RUNNING,
                taskId = taskId,
            )
        )
        return id
    }

    /** 直接登记一条已完成的记录(如被安全闸门拦截的命令) */
    suspend fun logCompleted(
        source: String,
        command: String,
        status: String,
        cwd: String = "",
        workspaceId: String? = null,
        exitCode: Int? = null,
        outputPreview: String? = null,
    ) {
        dao.insert(
            ShellAuditEntity(
                id = Uuid.random().toString(),
                createdAt = System.currentTimeMillis(),
                source = source,
                workspaceId = workspaceId,
                command = command.take(MAX_COMMAND_CHARS),
                cwd = cwd,
                status = status,
                exitCode = exitCode,
                durationMs = 0,
                outputPreview = outputPreview?.let { preview(it) },
            )
        )
    }

    suspend fun finish(
        id: String,
        startedAt: Long,
        status: String,
        exitCode: Int?,
        output: String?,
    ) {
        dao.finish(
            id = id,
            status = status,
            exitCode = exitCode,
            durationMs = System.currentTimeMillis() - startedAt,
            outputPreview = output?.let { preview(it) },
        )
        runCatching { dao.trim() }
    }

    suspend fun clearAll() = dao.clearAll()

    private fun preview(output: String): String {
        if (output.length <= PREVIEW_CHARS * 2) return output
        return output.take(PREVIEW_CHARS) +
            "\n... [省略] ...\n" +
            output.takeLast(PREVIEW_CHARS)
    }

    companion object {
        private const val MAX_COMMAND_CHARS = 4 * 1024
        private const val PREVIEW_CHARS = 500
    }
}
