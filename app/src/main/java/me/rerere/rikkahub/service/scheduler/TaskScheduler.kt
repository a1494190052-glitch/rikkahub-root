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

    /**
     * 重排前清理僵尸 ONCE 任务（触发时间已过且从未执行：创建于过去、关机错过闹钟）。
     * 这类任务 computeNextTrigger 返回 null 永不排期，不停用会一直显示"启用"却不跑。
     */
    suspend fun rescheduleAllWithCleanup(context: Context, repo: ScheduledTaskRepository) {
        val tasks = repo.getEnabled()
        val now = System.currentTimeMillis()
        val alive = tasks.filter { task ->
            val expiredOnce = task.type == ScheduledTaskEntity.TYPE_ONCE &&
                task.startAt <= now && task.lastRunAt == 0L
            if (expiredOnce) {
                runCatching { repo.setEnabled(task.id, false) }
                Log.i(TAG, "expired ONCE task disabled: ${task.title}")
            }
            !expiredOnce
        }
        rescheduleAll(context, alive)
    }

    private fun pendingIntent(context: Context, taskId: String): PendingIntent {
        val intent = Intent(context, TaskAlarmReceiver::class.java).apply {
            // data 参与 PendingIntent 唯一性判定: 防止两个 taskId 的 hashCode 碰撞时互相覆盖闹钟
            data = android.net.Uri.parse("rikkahub://scheduled_task/$taskId")
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
                // intervalMinutes<=0 时 while 循环永不退出, 兜底视为不可排期
                if (task.intervalMinutes <= 0) return null
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

    /** 归一化到 0..1439, 防历史脏数据(如 99:99)经 Calendar lenient 溢出到数天后 */
    private fun normalizeTimeMinutes(timeMinutes: Int): Int =
        ((timeMinutes % 1440) + 1440) % 1440

    private fun nextDailyTime(timeMinutes: Int, now: Long): Long {
        val normalized = normalizeTimeMinutes(timeMinutes)
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            set(Calendar.HOUR_OF_DAY, normalized / 60)
            set(Calendar.MINUTE, normalized % 60)
        }
        if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    private fun nextWeeklyTime(timeMinutes: Int, weekDays: String, now: Long): Long? {
        val days = weekDays.split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..7 }
        if (days.isEmpty()) return null
        val normalized = normalizeTimeMinutes(timeMinutes)
        var best: Long? = null
        for (offset in 0..8) {
            val cal = Calendar.getInstance().apply {
                timeInMillis = now
                add(Calendar.DAY_OF_YEAR, offset)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                set(Calendar.HOUR_OF_DAY, normalized / 60)
                set(Calendar.MINUTE, normalized % 60)
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
