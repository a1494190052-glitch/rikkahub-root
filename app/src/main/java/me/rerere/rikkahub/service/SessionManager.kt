package me.rerere.rikkahub.service

import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.NotFoundException
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/**
 * 会话管理器：从 ChatService 抽出的会话生命周期 + 持久化 + 消息 CRUD 职责（A+E 簇）。
 * 拥有核心 sessions map，负责会话创建/回收、状态读写、持久化、消息编辑/删除/fork、文件夹操作。
 *
 * ChatService 的核心生成流（B 簇）经委托方法访问会话；editMessage 所需的 preprocessUserInputParts
 * 由 ChatService 以回调注入（该方法同时被 sendMessage 使用，故保留在 ChatService）。
 */
class SessionManager(
    private val appScope: CoroutineScope,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val folderRepository: FolderRepository,
    private val filesManager: FilesManager,
    private val preprocessUserInput: (List<UIMessagePart>, Assistant) -> List<UIMessagePart>,
) {
    private val sessions = ConcurrentHashMap<Uuid, ConversationSession>()
    private val _sessionsVersion = MutableStateFlow(0L)

    fun cleanup() = runCatching {
        sessions.values.forEach { it.cleanup() }
        sessions.clear()
    }

    // ---- Session 生命周期 ----

    fun getOrCreateSession(conversationId: Uuid): ConversationSession {
        return sessions.computeIfAbsent(conversationId) { id ->
            val settings = settingsStore.settingsFlow.value
            ConversationSession(
                id = id,
                initial = Conversation.ofId(id = id, assistantId = settings.getCurrentAssistant().id),
                scope = appScope,
                onIdle = { removeSession(it) }
            ).also {
                _sessionsVersion.value++
                Log.i(TAG, "createSession: $id (total: ${sessions.size + 1})")
            }
        }
    }

    private fun removeSession(conversationId: Uuid) {
        val session = sessions[conversationId] ?: return
        if (session.isInUse) { Log.d(TAG, "removeSession: skipped $conversationId (still in use)"); return }
        if (sessions.remove(conversationId, session)) {
            session.cleanup()
            _sessionsVersion.value++
            Log.i(TAG, "removeSession: $conversationId (remaining: ${sessions.size})")
        }
    }

    fun addConversationReference(conversationId: Uuid) { getOrCreateSession(conversationId).acquire() }

    fun removeConversationReference(conversationId: Uuid) { sessions[conversationId]?.release() }

    fun launchWithConversationReference(conversationId: Uuid, block: suspend () -> Unit): Job =
        appScope.launch { addConversationReference(conversationId); try { block() } finally { removeConversationReference(conversationId) } }

    fun getConversationFlow(conversationId: Uuid): StateFlow<Conversation> = getOrCreateSession(conversationId).state

    fun getGenerationJobStateFlow(conversationId: Uuid): Flow<Job?> {
        val session = sessions[conversationId] ?: return flowOf(null)
        return session.generationJob
    }

    fun getProcessingStatusFlow(conversationId: Uuid): StateFlow<String?> {
        val session = sessions[conversationId] ?: return MutableStateFlow(null)
        return session.processingStatus
    }

    fun getConversationJobs(): Flow<Map<Uuid, Job?>> {
        return _sessionsVersion.flatMapLatest {
            val currentSessions = sessions.values.toList()
            if (currentSessions.isEmpty()) flowOf(emptyMap())
            else combine(currentSessions.map { s -> s.generationJob.map { job -> s.id to job } }) { pairs ->
                pairs.filter { it.second != null }.toMap()
            }
        }
    }

    /** 供 stopGeneration 等需直接拿 generation job 的场景使用（不创建会话） */
    fun getSessionJob(conversationId: Uuid): Job? = sessions[conversationId]?.getJob()

    suspend fun initializeConversation(conversationId: Uuid) {
        getOrCreateSession(conversationId)
        val conversation = conversationRepo.getConversationById(conversationId)
        if (conversation != null) {
            updateConversation(conversationId, conversation)
            settingsStore.updateAssistant(conversation.assistantId)
        } else {
            val currentSettings = settingsStore.settingsFlowRaw.first()
            val assistant = currentSettings.getCurrentAssistant()
            val newConversation = Conversation.ofId(id = conversationId, assistantId = assistant.id, newConversation = true)
                .updateCurrentMessages(assistant.presetMessages)
            updateConversation(conversationId, newConversation)
        }
    }

    // ---- 状态 / 持久化 ----

    fun updateConversation(conversationId: Uuid, conversation: Conversation) {
        if (conversation.id != conversationId) return
        val session = getOrCreateSession(conversationId)
        checkFilesDelete(conversation, session.state.value)
        session.state.value = conversation
    }

    fun updateConversationState(conversationId: Uuid, update: (Conversation) -> Conversation) {
        val current = getConversationFlow(conversationId).value
        updateConversation(conversationId, update(current))
    }

    suspend fun saveConversation(conversationId: Uuid, conversation: Conversation) {
        val exists = conversationRepo.existsConversationById(conversation.id)
        if (!exists && conversation.title.isBlank() && conversation.messageNodes.isEmpty()) return
        val updatedConversation = conversation.copy()
        updateConversation(conversationId, updatedConversation)
        if (!exists) conversationRepo.insertConversation(updatedConversation) else conversationRepo.updateConversation(updatedConversation)
    }

    private fun checkFilesDelete(newConversation: Conversation, oldConversation: Conversation) {
        val newSet = newConversation.files.toSet()
        val deletedFiles = oldConversation.files.filterNot { it in newSet }
        if (deletedFiles.isNotEmpty()) { filesManager.deleteChatFiles(deletedFiles); Log.w(TAG, "checkFilesDelete: $deletedFiles") }
    }

    // ---- 文件夹 ----

    suspend fun moveConversationToFolder(conversationId: Uuid, folderId: Uuid?) {
        if (sessions.containsKey(conversationId)) updateConversationState(conversationId) { it.copy(folderId = folderId) }
        conversationRepo.updateConversationFolderId(conversationId, folderId)
    }

    fun hasGeneratingConversationInFolder(folderId: Uuid): Boolean = sessions.values.any { it.isGenerating && it.state.value.folderId == folderId }

    suspend fun deleteFolder(folderId: Uuid) {
        sessions.values.filter { it.state.value.folderId == folderId }.forEach { updateConversationState(it.id) { c -> c.copy(folderId = null) } }
        folderRepository.deleteFolder(folderId)
    }

    // ---- 消息操作 ----

    suspend fun editMessage(conversationId: Uuid, messageId: Uuid, parts: List<UIMessagePart>) {
        if (parts.isEmptyInputMessage()) return
        val currentConversation = getConversationFlow(conversationId).value
        val settings = settingsStore.settingsFlow.first()
        val assistant = settings.getAssistantById(currentConversation.assistantId) ?: settings.getCurrentAssistant()
        val processedParts = preprocessUserInput(parts, assistant)
        var edited = false
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (!node.messages.any { it.id == messageId }) return@map node
            edited = true
            node.copy(messages = node.messages + UIMessage(role = node.role, parts = processedParts), selectIndex = node.messages.size)
        }
        if (!edited) return
        saveConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    suspend fun forkConversationAtMessage(conversationId: Uuid, messageId: Uuid): Conversation {
        val currentConversation = getConversationFlow(conversationId).value
        val targetNodeIndex = currentConversation.messageNodes.indexOfFirst { it.messages.any { it.id == messageId } }
        if (targetNodeIndex == -1) throw NotFoundException("Message not found")
        val copiedNodes = currentConversation.messageNodes.subList(0, targetNodeIndex + 1).map { node ->
            node.copy(id = Uuid.random(), messages = node.messages.map { it.copy(parts = it.parts.map { part -> part.copyWithForkedFileUrl() }) })
        }
        val forkConversation = Conversation(id = Uuid.random(), assistantId = currentConversation.assistantId, messageNodes = copiedNodes, customSystemPrompt = currentConversation.customSystemPrompt, modeInjectionIds = currentConversation.modeInjectionIds, lorebookIds = currentConversation.lorebookIds)
        saveConversation(forkConversation.id, forkConversation)
        return forkConversation
    }

    suspend fun selectMessageNode(conversationId: Uuid, nodeId: Uuid, selectIndex: Int) {
        val currentConversation = getConversationFlow(conversationId).value
        val targetNode = currentConversation.messageNodes.firstOrNull { it.id == nodeId } ?: throw NotFoundException("Message node not found")
        if (selectIndex !in targetNode.messages.indices) throw BadRequestException("Invalid selectIndex")
        if (targetNode.selectIndex == selectIndex) return
        saveConversation(conversationId, currentConversation.copy(messageNodes = currentConversation.messageNodes.map { if (it.id == nodeId) it.copy(selectIndex = selectIndex) else it }))
    }

    suspend fun deleteMessage(conversationId: Uuid, messageId: Uuid, failIfMissing: Boolean = true) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedConversation = buildConversationAfterMessageDelete(currentConversation, messageId)
        if (updatedConversation == null) { if (failIfMissing) throw NotFoundException("Message not found"); return }
        saveConversation(conversationId, updatedConversation)
    }

    suspend fun deleteMessage(conversationId: Uuid, message: UIMessage) { deleteMessage(conversationId, message.id, failIfMissing = false) }

    private fun buildConversationAfterMessageDelete(conversation: Conversation, messageId: Uuid): Conversation? {
        val targetNodeIndex = conversation.messageNodes.indexOfFirst { it.messages.any { it.id == messageId } }
        if (targetNodeIndex == -1) return null
        val updatedNodes = conversation.messageNodes.mapIndexedNotNull { index, node ->
            if (index != targetNodeIndex) return@mapIndexedNotNull node
            val nextMessages = node.messages.filterNot { it.id == messageId }
            if (nextMessages.isEmpty()) return@mapIndexedNotNull null
            node.copy(messages = nextMessages, selectIndex = node.selectIndex.coerceAtMost(nextMessages.lastIndex))
        }
        return conversation.copy(messageNodes = updatedNodes)
    }

    private fun UIMessagePart.copyWithForkedFileUrl(): UIMessagePart {
        fun copyLocalFileIfNeeded(url: String): String { if (!url.startsWith("file:")) return url; val copied = filesManager.createChatFilesByContents(listOf(url.toUri())).firstOrNull(); return copied?.toString() ?: url }
        return when (this) { is UIMessagePart.Image -> copy(url = copyLocalFileIfNeeded(url)); is UIMessagePart.Document -> copy(url = copyLocalFileIfNeeded(url)); is UIMessagePart.Video -> copy(url = copyLocalFileIfNeeded(url)); is UIMessagePart.Audio -> copy(url = copyLocalFileIfNeeded(url)); else -> this }
    }

    private companion object {
        private const val TAG = "SessionManager"
    }
}
