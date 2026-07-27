package me.rerere.rikkahub.service

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.ai.provider.ProviderManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.utils.applyPlaceholders
import me.rerere.rikkahub.data.model.toMessageNode
import java.util.Locale
import kotlin.uuid.Uuid

/**
 * 对话辅助生成器：从 ChatService 抽出的"非核心聊天"生成职责。
 * 负责标题生成、回复建议、对话压缩、消息翻译。
 *
 * 通过构造器 lambda 回调 ChatService（保存/更新会话、报错），避免反向依赖与循环依赖；
 * ChatService 的对应公开方法保留为对这里的薄委托，调用方无感知。
 */
class ConversationAssistant(
    private val context: Context,
    private val appScope: CoroutineScope,
    private val settingsStore: SettingsStore,
    private val providerManager: ProviderManager,
    private val conversationRepo: ConversationRepository,
    private val generationHandler: GenerationHandler,
    private val saveConversation: suspend (Uuid, Conversation) -> Unit,
    private val updateConversation: (Uuid, Conversation) -> Unit,
    private val currentConversation: (Uuid) -> Conversation,
    private val reportError: (Throwable, Uuid?, String?, ChatErrorSolution?) -> Unit,
) {

    // ---- 生成标题 ----

    suspend fun generateTitle(conversationId: Uuid, conversation: Conversation, force: Boolean = false) {
        val shouldGenerate = when { force -> true; conversation.title.isBlank() -> true; else -> false }
        if (!shouldGenerate) return
        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val model = settings.findModelById(settings.titleModelId, fallback = settings.fastModelId) ?: return
            val provider = model.findProvider(settings.providers) ?: return
            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(UIMessage.user(prompt = settings.titlePrompt.applyPlaceholders("locale" to Locale.getDefault().displayName, "content" to conversation.currentMessages.takeLast(4).joinToString("\n\n") { it.summaryAsText(maxLength = 500) }))),
                params = backgroundTextGenerationParams(model),
            )
            conversationRepo.getConversationById(conversation.id)?.let { saveConversation(conversationId, it.copy(title = result.choices[0].message?.toText()?.trim() ?: "")) }
        }.onFailure {
            it.printStackTrace()
            reportError(it, conversationId, context.getString(R.string.error_title_generate_title), ChatErrorSolution.CheckTitleModelSettings)
        }
    }

    // ---- 生成建议 ----

    suspend fun generateSuggestion(conversationId: Uuid, conversation: Conversation) {
        runCatching {
            val settings = settingsStore.settingsFlow.first()
            if (!settings.enableSuggestion) return
            val model = settings.findModelById(settings.suggestionModelId, fallback = settings.fastModelId) ?: return
            val provider = model.findProvider(settings.providers) ?: return
            updateConversation(conversationId, currentConversation(conversationId).copy(chatSuggestions = emptyList()))
            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(UIMessage.user(settings.suggestionPrompt.applyPlaceholders("locale" to Locale.getDefault().displayName, "content" to conversation.currentMessages.takeLast(8).joinToString("\n\n") { it.summaryAsText(maxLength = 500) }))),
                params = backgroundTextGenerationParams(model),
            )
            val suggestions = result.choices[0].message?.toText()?.split("\n")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
            val latestConversation = conversationRepo.getConversationById(conversationId) ?: currentConversation(conversationId)
            saveConversation(conversationId, latestConversation.copy(chatSuggestions = suggestions.take(10)))
        }.onFailure { reportError(it, conversationId, null, null) }
    }

    // ---- 压缩对话历史 ----

    suspend fun compressConversation(conversationId: Uuid, conversation: Conversation, additionalPrompt: String, targetTokens: Int, keepRecentMessages: Int = 32): Result<Unit> = runCatching {
        val settings = settingsStore.settingsFlow.first()
        val model = settings.findModelById(settings.compressModelId) ?: settings.getCurrentChatModel() ?: throw IllegalStateException("No model available for compression")
        val provider = model.findProvider(settings.providers) ?: throw IllegalStateException("Provider not found")
        val providerHandler = providerManager.getProviderByType(provider)
        val maxMessagesPerChunk = 256
        val allMessages = conversation.currentMessages
        val messagesToCompress: List<UIMessage>
        val messagesToKeep: List<UIMessage>
        if (keepRecentMessages > 0 && allMessages.size > keepRecentMessages) { messagesToCompress = allMessages.dropLast(keepRecentMessages); messagesToKeep = allMessages.takeLast(keepRecentMessages) }
        else if (keepRecentMessages > 0) throw IllegalStateException(context.getString(R.string.chat_page_compress_not_enough_messages))
        else { messagesToCompress = allMessages; messagesToKeep = emptyList() }
        fun splitMessages(messages: List<UIMessage>): List<List<UIMessage>> {
            if (messages.size <= maxMessagesPerChunk) return listOf(messages)
            val mid = messages.size / 2
            return splitMessages(messages.subList(0, mid)) + splitMessages(messages.subList(mid, messages.size))
        }
        suspend fun compressMessages(messages: List<UIMessage>): String {
            val contentToCompress = messages.joinToString("\n\n") { it.summaryAsText(maxLength = 2000) }
            val prompt = settings.compressPrompt.applyPlaceholders("content" to contentToCompress, "target_tokens" to targetTokens.toString(), "additional_context" to if (additionalPrompt.isNotBlank()) "Additional instructions from user: $additionalPrompt" else "", "locale" to Locale.getDefault().displayName)
            val result = providerHandler.generateText(providerSetting = provider, messages = listOf(UIMessage.user(prompt)), params = backgroundTextGenerationParams(model))
            return result.choices[0].message?.toText()?.trim() ?: throw IllegalStateException("Failed to generate compressed summary")
        }
        val compressedSummaries = coroutineScope { splitMessages(messagesToCompress).map { chunk -> async { compressMessages(chunk) } }.awaitAll() }
        val newMessageNodes = buildList { compressedSummaries.forEach { add(UIMessage.user(it).toMessageNode()) }; addAll(messagesToKeep.map { it.toMessageNode() }) }
        saveConversation(conversationId, conversation.copy(messageNodes = newMessageNodes, chatSuggestions = emptyList()))
    }

    // ---- 翻译消息 ----

    fun translateMessage(conversationId: Uuid, message: UIMessage, targetLanguage: Locale) {
        appScope.launch(Dispatchers.IO) {
            try {
                val settings = settingsStore.settingsFlow.first()
                val messageText = message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n\n") { it.text }.trim()
                if (messageText.isBlank()) return@launch
                updateTranslationField(conversationId, message.id, context.getString(R.string.translating))
                generationHandler.translateText(settings = settings, sourceText = messageText, targetLanguage = targetLanguage) { translatedText -> updateTranslationField(conversationId, message.id, translatedText) }.collect {}
                saveConversation(conversationId, currentConversation(conversationId))
            } catch (e: Exception) { clearTranslationField(conversationId, message.id); reportError(e, conversationId, context.getString(R.string.error_title_translate_message), null) }
        }
    }

    fun updateTranslationField(conversationId: Uuid, messageId: Uuid, translationText: String) {
        val current = currentConversation(conversationId)
        val updatedNodes = current.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) node.copy(messages = node.messages.map { if (it.id == messageId) it.copy(translation = translationText) else it })
            else node
        }
        updateConversation(conversationId, current.copy(messageNodes = updatedNodes))
    }

    fun clearTranslationField(conversationId: Uuid, messageId: Uuid) {
        val current = currentConversation(conversationId)
        val updatedNodes = current.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) node.copy(messages = node.messages.map { if (it.id == messageId) it.copy(translation = null) else it })
            else node
        }
        updateConversation(conversationId, current.copy(messageNodes = updatedNodes))
    }
}
