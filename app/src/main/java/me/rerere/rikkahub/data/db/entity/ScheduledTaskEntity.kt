package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 定时任务：AI 主动消息的调度定义
 * type: ONCE(一次性) / DAILY(每天) / WEEKLY(每周几天) / INTERVAL(间隔分钟)
 */
@Entity(tableName = "scheduled_tasks")
data class ScheduledTaskEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "assistant_id") val assistantId: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "prompt") val prompt: String,
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "time_minutes") val timeMinutes: Int = 9 * 60, // DAILY/WEEKLY: 一天中的分钟
    @ColumnInfo(name = "week_days") val weekDays: String = "",        // WEEKLY: "1,3,5" (1=周一..7=周日)
    @ColumnInfo(name = "interval_minutes") val intervalMinutes: Int = 60, // INTERVAL
    @ColumnInfo(name = "start_at") val startAt: Long = 0,             // ONCE: 触发时间戳
    @ColumnInfo(name = "enabled") val enabled: Boolean = true,
    @ColumnInfo(name = "conversation_id") val conversationId: String? = null, // 结果落到的会话(空=自动创建)
    @ColumnInfo(name = "last_run_at") val lastRunAt: Long = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val TYPE_ONCE = "ONCE"
        const val TYPE_DAILY = "DAILY"
        const val TYPE_WEEKLY = "WEEKLY"
        const val TYPE_INTERVAL = "INTERVAL"
    }
}
