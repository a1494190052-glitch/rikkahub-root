package me.rerere.rikkahub.service.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * 闹钟触发：只做毫秒级入队，执行交给 [ScheduledTaskWorker]。
 * （goAsync 只有约 10s 预算，LLM 生成远超此限，超预算进程回收会让周期任务静默停火）
 */
class TaskAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(TaskScheduler.EXTRA_TASK_ID) ?: return
        runCatching { ScheduledTaskWorker.enqueue(context, taskId) }
            .onFailure { Log.e("TaskAlarmReceiver", "enqueue task $taskId failed", it) }
    }
}

/** 开机/时区变化后重排所有启用的定时任务（闹钟不持久化，时区变化后触发时刻全变） */
class BootReceiver : BroadcastReceiver(), KoinComponent {
    private val repo: ScheduledTaskRepository by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_TIMEZONE_CHANGED &&
            intent.action != Intent.ACTION_TIME_CHANGED
        ) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // rescheduleAllWithCleanup 会先停用过期未跑的僵尸 ONCE 任务再重排
                TaskScheduler.rescheduleAllWithCleanup(context, repo)
            } catch (e: Exception) {
                Log.e("BootReceiver", "reschedule failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
