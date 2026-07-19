package me.rerere.rikkahub.service.scheduler

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.data.db.dao.ScheduledTaskDAO
import me.rerere.rikkahub.data.db.entity.ScheduledTaskEntity

/** 定时任务仓储：DB + 调度联动（任何增删改都同步重排闹钟） */
class ScheduledTaskRepository(
    private val context: Context,
    private val dao: ScheduledTaskDAO,
) {
    fun getAllFlow(): Flow<List<ScheduledTaskEntity>> = dao.getAllFlow()

    fun getOfAssistantFlow(assistantId: String): Flow<List<ScheduledTaskEntity>> =
        dao.getOfAssistantFlow(assistantId)

    suspend fun getAll(): List<ScheduledTaskEntity> = dao.getAll()

    suspend fun getEnabled(): List<ScheduledTaskEntity> = dao.getEnabled()

    suspend fun getById(id: String): ScheduledTaskEntity? = dao.getById(id)

    suspend fun upsert(task: ScheduledTaskEntity) {
        dao.upsert(task)
        TaskScheduler.cancel(context, task.id)
        if (task.enabled) TaskScheduler.schedule(context, task)
    }

    suspend fun delete(id: String) {
        dao.deleteById(id)
        TaskScheduler.cancel(context, id)
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        dao.setEnabled(id, enabled)
        TaskScheduler.cancel(context, id)
        if (enabled) dao.getById(id)?.let { TaskScheduler.schedule(context, it) }
    }
}
