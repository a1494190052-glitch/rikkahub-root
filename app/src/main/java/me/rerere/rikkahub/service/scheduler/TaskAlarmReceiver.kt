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

/** 闹钟触发：执行定时任务（goAsync 延长广播生命周期） */
class TaskAlarmReceiver : BroadcastReceiver(), KoinComponent {
    private val executor: ScheduledTaskExecutor by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(TaskScheduler.EXTRA_TASK_ID) ?: return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                executor.run(taskId)
            } catch (e: Exception) {
                Log.e("TaskAlarmReceiver", "run task $taskId failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}

/** 开机后重排所有启用的定时任务（闹钟不持久化） */
class BootReceiver : BroadcastReceiver(), KoinComponent {
    private val repo: ScheduledTaskRepository by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                TaskScheduler.rescheduleAll(context, repo.getEnabled())
            } catch (e: Exception) {
                Log.e("BootReceiver", "reschedule failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
