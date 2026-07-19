package me.rerere.rikkahub.service.scheduler

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * 定时任务执行 Worker。
 *
 * 闹钟广播只有约 10s 执行预算（goAsync），而一次 LLM 生成常需数十秒到数分钟，
 * 超预算后进程被回收会导致周期任务"静默停火"（finally 里的重排没来得及跑）。
 * 因此 TaskAlarmReceiver 只做毫秒级入队，真正的执行放在这里——
 * expedited work 即时启动且不受广播预算限制，超时窗口为 10 分钟。
 */
class ScheduledTaskWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params), KoinComponent {

    private val executor: ScheduledTaskExecutor by inject()

    override suspend fun doWork(): Result {
        val taskId = inputData.getString(TaskScheduler.EXTRA_TASK_ID) ?: return Result.failure()
        executor.run(taskId)
        return Result.success()
    }

    companion object {
        /** 入队立即执行指定任务（闹钟触发的唯一入口） */
        fun enqueue(context: Context, taskId: String) {
            val request = OneTimeWorkRequestBuilder<ScheduledTaskWorker>()
                .setInputData(Data.Builder().putString(TaskScheduler.EXTRA_TASK_ID, taskId).build())
                // 配额耗尽时降级为普通 work, 仍然执行而不是丢失
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "scheduled_task_$taskId",
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
