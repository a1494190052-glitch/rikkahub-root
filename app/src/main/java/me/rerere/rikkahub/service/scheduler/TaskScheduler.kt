package me.rerere.rikkahub.service.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import me.rerere.rikkahub.data.db.entity.ScheduledTaskEntity
import java.util.Calendar

/**
 * 定时任务调度器：AlarmManager 精确唤醒（Doze 兼容）。
 * 每次触发后由执行器重排下一次；开机由 BootReceiver 全量重排。
 */
object TaskScheduler {
    private const val TAG = "TaskScheduler"
    const val EXTRA_TASK_ID = "task_id"

    fun schedule(context: Context, task: ScheduledTaskEntity) {
        if (!task.enabled) return
        val triggerAt = computeNextTrigger(task) ?: return
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context, task.id)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                Log.w(TAG, "no exact-alarm permission, fallback inexact for ${task.title}")
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
            Log.i(TAG, "scheduled '${task.title}' at $triggerAt")
        } catch (e: SecurityException) {
            Log.e(TAG, "schedule failed", e)
        }
    }

    fun cancel(context: Context, taskId: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(context, taskId))
    }

    fun rescheduleAll(context: Context, tasks: List<ScheduledTaskEntity>) {
        tasks.filter { it.enabled }.forEach { schedule(context, it) }
    }

    private fun pendingIntent(context: Context, taskId: String): PendingIntent {
        val intent = Intent(context, TaskAlarmReceiver::class.java).apply {
            putExtra(EXTRA_TASK_ID, taskId)
        }
        return PendingIntent.getBroadcast(
            context, taskId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 计算下次触发时间（ms）；任务不应再触发时返回 null */
    fun computeNextTrigger(task: ScheduledTaskEntity, now: Long = System.currentTimeMillis()): Long? {
        return when (task.type) {
            ScheduledTaskEntity.TYPE_ONCE ->
                if (task.startAt > now && task.lastRunAt == 0L) task.startAt else null

            ScheduledTaskEntity.TYPE_INTERVAL -> {
                val base = maxOf(task.lastRunAt, task.createdAt, 0L)
                var next = base + task.intervalMinutes * 60_000L
                while (next <= now) next += task.intervalMinutes * 60_000L
                next
            }

            ScheduledTaskEntity.TYPE_DAILY -> nextDailyTime(task.timeMinutes, now)

            ScheduledTaskEntity.TYPE_WEEKLY -> nextWeeklyTime(task.timeMinutes, task.weekDays, now)

            else -> null
        }
    }

    private fun nextDailyTime(timeMinutes: Int, now: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            set(Calendar.HOUR_OF_DAY, timeMinutes / 60)
            set(Calendar.MINUTE, timeMinutes % 60)
        }
        if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    private fun nextWeeklyTime(timeMinutes: Int, weekDays: String, now: Long): Long? {
        val days = weekDays.split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..7 }
        if (days.isEmpty()) return null
        var best: Long? = null
        for (offset in 0..8) {
            val cal = Calendar.getInstance().apply {
                timeInMillis = now
                add(Calendar.DAY_OF_YEAR, offset)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                set(Calendar.HOUR_OF_DAY, timeMinutes / 60)
                set(Calendar.MINUTE, timeMinutes % 60)
            }
            val dow = when (cal.get(Calendar.DAY_OF_WEEK)) {
                Calendar.SUNDAY -> 7
                else -> cal.get(Calendar.DAY_OF_WEEK) - 1
            }
            if (dow in days && cal.timeInMillis > now) {
                if (best == null || cal.timeInMillis < best!!) best = cal.timeInMillis
            }
        }
        return best
    }
}
