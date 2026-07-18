package me.rerere.rikkahub.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Shell 命令审计日志: 记录 AI/后台任务执行过的每条 shell 命令,
 * 供用户在「Shell 日志」页回看 AI 在设备上做过什么.
 */
@Entity(tableName = "shell_audit")
data class ShellAuditEntity(
    @PrimaryKey
    val id: String,
    val createdAt: Long,
    /** 来源: ai_workspace / ai_root / ai_background / scheduled_task */
    val source: String,
    val workspaceId: String? = null,
    val command: String,
    val cwd: String = "",
    /** running / done / timeout / killed / error / blocked */
    val status: String = STATUS_RUNNING,
    val exitCode: Int? = null,
    val durationMs: Long? = null,
    /** 输出预览(头+尾各 ~500 字符), 完整输出对后台任务在 log 文件里 */
    val outputPreview: String? = null,
    /** 后台任务 id (source=ai_background 时关联) */
    val taskId: String? = null,
) {
    companion object {
        const val STATUS_RUNNING = "running"
        const val STATUS_DONE = "done"
        const val STATUS_TIMEOUT = "timeout"
        const val STATUS_KILLED = "killed"
        const val STATUS_ERROR = "error"
        const val STATUS_BLOCKED = "blocked"

        const val SOURCE_AI_WORKSPACE = "ai_workspace"
        const val SOURCE_AI_ROOT = "ai_root"
        const val SOURCE_AI_BACKGROUND = "ai_background"
        const val SOURCE_SCHEDULED_TASK = "scheduled_task"
    }
}
