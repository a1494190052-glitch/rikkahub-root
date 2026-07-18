package me.rerere.rikkahub.service.scheduler

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.flow.first
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.db.dao.ScheduledTaskDAO
import me.rerere.rikkahub.data.db.entity.ScheduledTaskEntity
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.backgroundTextGenerationParams
import me.rerere.rikkahub.utils.NotificationUtil
import java.time.Instant
import kotlin.uuid.Uuid

const val SCHEDULED_TASK_NOTIFICATION_CHANNEL_ID = "scheduled_task"

/**
 * 定时任务执行器：闹钟触发后调 LLM 生成"主动消息"，落会话 + 发通知 + 重排下次。
 * 带任务专属会话的最近历史，让主动消息延续之前的剧情/上下文。
 */
class ScheduledTaskExecutor(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val providerManager: ProviderManager,
    private val scheduledTaskDao: ScheduledTaskDAO,
    private val conversationRepo: ConversationRepository,
) {
    companion object {
        private const val TAG = "ScheduledTaskExecutor"
        private const val HISTORY_LIMIT = 10
    }

    suspend fun run(taskId: String) {
        val task = scheduledTaskDao.getById(taskId) ?: return
        if (!task.enabled) return
        Log.i(TAG, "running task '${task.title}'")
        try {
            executeTask(task)
        } catch (e: Exception) {
            Log.e(TAG, "task '${task.title}' failed", e)
            notifyUser(task, null, "定时任务执行失败：${e.message?.take(120) ?: e.javaClass.simpleName}")
        } finally {
            // 重排下次（ONCE 执行后 computeNextTrigger 返回 null, 自动失效）
            if (task.type != ScheduledTaskEntity.TYPE_ONCE) {
                TaskScheduler.schedule(context, task)
            } else {
                scheduledTaskDao.setEnabled(task.id, false)
            }
        }
    }

    private suspend fun executeTask(task: ScheduledTaskEntity) {
        val settings = settingsStore.settingsFlow.first()
        val assistant = settings.assistants.find { it.id.toString() == task.assistantId } ?: return
        val model = settings.findModelById(assistant.chatModelId, fallback = settings.fastModelId) ?: return
        val provider = model.findProvider(settings.providers) ?: return
        val handler = providerManager.getProviderByType(provider)

        // 任务专属会话的历史（延续上下文）
        val existing = task.conversationId?.let { cid ->
            runCatching { conversationRepo.getConversationById(Uuid.parse(cid)) }.getOrNull()
        }
        val history = existing?.messageNodes
            ?.mapNotNull { node -> runCatching { node.currentMessage }.getOrNull() }
            ?.takeLast(HISTORY_LIMIT)
            .orEmpty()

        val messages = buildList {
            if (assistant.systemPrompt.isNotBlank()) add(UIMessage.system(assistant.systemPrompt))
            addAll(history)
            add(UIMessage.user(task.prompt))
        }

        val result = handler.generateText(
            providerSetting = provider,
            messages = messages,
            params = backgroundTextGenerationParams(model),
        )
        val reply = result.choices.firstOrNull()?.message?.toText()?.trim().orEmpty()
        if (reply.isBlank()) return

        val conversationId = saveToConversation(task, assistant, existing, reply)
        scheduledTaskDao.markRun(task.id, System.currentTimeMillis(), conversationId)
        notifyUser(task, assistant.name, reply)
    }

    /** 追加到任务专属会话（没有则新建，标题 = 任务名） */
    private suspend fun saveToConversation(
        task: ScheduledTaskEntity,
        assistant: me.rerere.rikkahub.data.model.Assistant,
        existing: Conversation?,
        reply: String,
    ): String {
        val userNode = MessageNode.of(UIMessage.user(task.prompt))
        val aiNode = MessageNode.of(UIMessage.assistant(reply))
        return if (existing != null) {
            conversationRepo.updateConversation(
                existing.copy(
                    messageNodes = existing.messageNodes + userNode + aiNode,
                    updateAt = Instant.now(),
                )
            )
            existing.id.toString()
        } else {
            val conv = Conversation(
                assistantId = assistant.id,
                title = task.title.ifBlank { "定时消息" },
                messageNodes = listOf(userNode, aiNode),
            )
            conversationRepo.insertConversation(conv)
            conv.id.toString()
        }
    }

    private fun notifyUser(task: ScheduledTaskEntity, assistantName: String?, reply: String) {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP) }
        val pi = launch?.let {
            PendingIntent.getActivity(
                context, task.id.hashCode(), it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        NotificationUtil.notify(
            context = context,
            channelId = SCHEDULED_TASK_NOTIFICATION_CHANNEL_ID,
            notificationId = task.id.hashCode(),
        ) {
            title = if (assistantName != null) "$assistantName · ${task.title}" else task.title
            content = reply.take(300)
            useBigTextStyle = true
            autoCancel = true
            contentIntent = pi
        }
    }
}
