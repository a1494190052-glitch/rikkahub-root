package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shell 安全审计日志 — 文件级记录, 用于事后分析被拦截的命令。
 *
 * 存储位置: context.filesDir/shell_audit.log
 * 格式: 时间戳 | 命令 | 分类结果 | 拦截原因 | 问题子命令
 * 保留最近 [MAX_ENTRIES] 条, 超出自动轮转(截断前半)。
 *
 * 线程安全: 所有写操作 synchronized。
 */
class ShellSafetyAuditLog(context: Context) {

    private val logFile = File(context.filesDir, AUDIT_FILE_NAME)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)

    /** 审计日志条目 */
    data class AuditEntry(
        val timestamp: String,
        val command: String,
        val classification: String,
        val reason: String,
        val offendingSegment: String?,
    ) {
        fun toLine(): String = buildString {
            append(timestamp)
            append(SEPARATOR)
            append(command.replace("\n", "\\n").replace("|", "\\|"))
            append(SEPARATOR)
            append(classification)
            append(SEPARATOR)
            append(reason.replace("|", "\\|"))
            append(SEPARATOR)
            append(offendingSegment?.replace("\n", "\\n")?.replace("|", "\\|") ?: "-")
        }

        companion object {
            fun fromLine(line: String): AuditEntry? {
                val parts = line.split(SEPARATOR, limit = 5)
                if (parts.size < 4) return null
                return AuditEntry(
                    timestamp = parts[0],
                    command = parts[1].replace("\\n", "\n").replace("\\|", "|"),
                    classification = parts[2],
                    reason = parts[3].replace("\\|", "|"),
                    offendingSegment = parts.getOrNull(4)
                        ?.takeIf { it != "-" }
                        ?.replace("\\n", "\n")
                        ?.replace("\\|", "|"),
                )
            }
        }
    }

    /** 记录一条被拦截的命令 */
    @Synchronized
    fun logBlocked(command: String, reason: String, offendingSegment: String? = null) {
        val entry = AuditEntry(
            timestamp = dateFormat.format(Date()),
            command = command.take(MAX_COMMAND_LENGTH),
            classification = "BLOCKED",
            reason = reason,
            offendingSegment = offendingSegment,
        )
        appendEntry(entry)
    }

    /** 记录一条深度分类结果(非 BLOCKED 也可记录, 用于审计 WRITE 操作) */
    @Synchronized
    fun logClassification(command: String, classification: DeepClassification) {
        if (classification.risk == ShellRisk.READ_ONLY) return // 只读不记录
        val entry = AuditEntry(
            timestamp = dateFormat.format(Date()),
            command = command.take(MAX_COMMAND_LENGTH),
            classification = classification.risk.name,
            reason = classification.reason ?: "-",
            offendingSegment = classification.offendingSegment,
        )
        appendEntry(entry)
    }

    /** 获取最近 [count] 条审计记录(最新在前) */
    @Synchronized
    fun getRecentLogs(count: Int = 50): List<AuditEntry> {
        if (!logFile.exists()) return emptyList()
        return logFile.readLines()
            .filter { it.isNotBlank() }
            .mapNotNull { AuditEntry.fromLine(it) }
            .takeLast(count)
            .reversed()
    }

    /** 获取全部审计记录数 */
    @Synchronized
    fun getLogCount(): Int {
        if (!logFile.exists()) return 0
        return logFile.readLines().count { it.isNotBlank() }
    }

    /** 清空审计日志 */
    @Synchronized
    fun clear() {
        logFile.delete()
    }

    // ---------- 内部实现 ----------

    private fun appendEntry(entry: AuditEntry) {
        try {
            logFile.parentFile?.mkdirs()
            logFile.appendText(entry.toLine() + "\n")
            rotateIfNeeded()
        } catch (_: Exception) {
            // 审计日志写入失败不应影响主流程
        }
    }

    /** 超过 MAX_ENTRIES 时截断前半, 保留最近的记录 */
    private fun rotateIfNeeded() {
        try {
            val lines = logFile.readLines().filter { it.isNotBlank() }
            if (lines.size > MAX_ENTRIES) {
                val keep = lines.takeLast(MAX_ENTRIES)
                logFile.writeText(keep.joinToString("\n") + "\n")
            }
        } catch (_: Exception) {
            // ignore
        }
    }

    companion object {
        private const val AUDIT_FILE_NAME = "shell_safety_audit.log"
        private const val SEPARATOR = " | "
        private const val MAX_ENTRIES = 1000
        private const val MAX_COMMAND_LENGTH = 2048
    }
}
