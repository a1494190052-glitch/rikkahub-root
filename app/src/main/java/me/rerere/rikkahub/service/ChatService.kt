package me.rerere.rikkahub.service

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.canResumeToolExecution
import me.rerere.ai.ui.finishPendingTools
import me.rerere.ai.ui.finishReasoning
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.common.android.Logging
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.subagent.SubagentHost
import me.rerere.rikkahub.data.ai.subagent.SubagentProfile
import me.rerere.rikkahub.data.ai.subagent.SubagentResult
import me.rerere.rikkahub.data.ai.subagent.SubagentTranscriptStep
import me.rerere.rikkahub.data.ai.subagent.createManageSubagentTool
import me.rerere.rikkahub.data.ai.subagent.createSubagentTools
import me.rerere.rikkahub.data.ai.subagent.mergeSubagentProfiles
import me.rerere.rikkahub.data.ai.subagent.removeSubagentProfile
import me.rerere.rikkahub.data.ai.subagent.upsertSubagentProfile
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.ai.tools.createConversationTools
import me.rerere.rikkahub.data.ai.tools.createSearchTools
import me.rerere.rikkahub.data.ai.tools.createSkillTools
import me.rerere.rikkahub.data.ai.tools.createWorkspaceTools
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.ai.transformers.Base64ImageToLocalFileTransformer
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer
import me.rerere.rikkahub.data.ai.transformers.PromptInjectionTransformer
import me.rerere.rikkahub.data.ai.transformers.RegexInputTransformer
import me.rerere.rikkahub.data.ai.transformers.RegexOutputTransformer
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.ai.transformers.ThinkTagTransformer
import me.rerere.rikkahub.data.ai.transformers.TimeReminderTransformer
import me.rerere.rikkahub.data.ai.transformers.WorkspaceReminderTransformer
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.RegexApplyMode
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.NotFoundException
import me.rerere.rikkahub.utils.applyPlaceholders
import me.rerere.workspace.WorkspaceShellStatus
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val TAG = "ChatService"

internal fun backgroundTextGenerationParams(
    model: Model,
    reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
): TextGenerationParams = TextGenerationParams(
    model = model,
    reasoningLevel = reasoningLevel,
    customHeaders = model.customHeaders,
    customBody = model.customBodies,
)

data class ChatError(
    val id: Uuid = Uuid.random(),
    val title: String? = null,
    val error: Throwable,
    val conversationId: Uuid? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val solution: ChatErrorSolution? = null,
)

enum class ChatErrorSolution {
    CheckTitleModelSettings,
}

private val inputTransformers by lazy {
    listOf(
        TimeReminderTransformer,
        PromptInjectionTransformer,
        PlaceholderTransformer,
        DocumentAsPromptTransformer,
        OcrTransformer,
        RegexInputTransformer,
    )
}

private val outputTransformers by lazy {
    listOf(
        ThinkTagTransformer,
        Base64ImageToLocalFileTransformer,
        RegexOutputTransformer,
    )
}

class ChatService(
    private val context: Application,
    private val appScope: AppScope,
    private val appEventBus: AppEventBus,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val memoryRepository: MemoryRepository,
    private val generationHandler: GenerationHandler,
    private val templateTransformer: TemplateTransformer,
    private val providerManager: ProviderManager,
    private val localTools: LocalTools,
    private val subAgentExecutor: me.rerere.rikkahub.data.ai.tools.local.SubAgentExecutor,
    private val scheduledTaskRepository: me.rerere.rikkahub.service.scheduler.ScheduledTaskRepository,
    val mcpManager: McpManager,
    private val filesManager: FilesManager,
    private val skillManager: SkillManager,
    private val workspaceRepository: WorkspaceRepository,
    private val folderRepository: FolderRepository,
    private val shellSessionManager: me.rerere.workspace.ShellSessionManager,
    private val backgroundShellManager: me.rerere.rikkahub.service.shell.BackgroundShellManager,
    private val shellAuditLogger: me.rerere.rikkahub.service.shell.ShellAuditLogger,
    // ---- 子代理系统 (kimi-code) ----
    private val subagentHost: SubagentHost,
    private val json: Json,
) {
    private val workspaceReminderTransformer = WorkspaceReminderTransformer(workspaceRepository)

    private val sessions = ConcurrentHashMap<Uuid, ConversationSession>()
    private val _sessionsVersion = MutableStateFlow(0L)

    private val _errors = MutableStateFlow<List<ChatError>>(emptyList())
    val errors: StateFlow<List<ChatError>> = _errors.asStateFlow()

    fun addError(
        error: Throwable,
        conversationId: Uuid? = null,
        title: String? = null,
        solution: ChatErrorSolution? = null,
    ) {
        if (error is CancellationException) return
        _errors.update {
            it + ChatError(title = title, error = error, conversationId = conversationId, solution = solution)
        }
    }

    fun dismissError(id: Uuid) {
        _errors.update { list -> list.filter { it.id != id } }
    }

    fun clearAllErrors() {
        _errors.value = emptyList()
    }

    private val _generationDoneFlow = MutableSharedFlow<Uuid>()
    val generationDoneFlow: SharedFlow<Uuid> = _generationDoneFlow.asSharedFlow()

    fun cleanup() = runCatching {
        sessions.values.forEach { it.cleanup() }
        sessions.clear()
    }

    // ---- Session ----

    private fun getOrCreateSession(conversationId: Uuid): ConversationSession {
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

    private fun launchWithConversationReference(conversationId: Uuid, block: suspend () -> Unit): Job =
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

    // ---- 发送消息 ----

    fun sendMessage(conversationId: Uuid, content: List<UIMessagePart>, answer: Boolean = true) {
        if (content.isEmptyInputMessage()) return
        val session = getOrCreateSession(conversationId)
        val previousJob = session.getJob()
        previousJob?.cancel()
        val job = appScope.launch {
            try {
                runCatching { previousJob?.join() }
                finishInterruptedPendingTools(conversationId)
                val currentConversation = session.state.value
                val settings = settingsStore.settingsFlow.first()
                val assistant = settings.getAssistantById(currentConversation.assistantId) ?: settings.getCurrentAssistant()
                val processedContent = preprocessUserInputParts(content, assistant)
                val newConversation = currentConversation.copy(
                    messageNodes = currentConversation.messageNodes + UIMessage(role = MessageRole.USER, parts = processedContent).toMessageNode(),
                )
                saveConversation(conversationId, newConversation)
                if (answer) handleMessageComplete(conversationId)
                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                e.printStackTrace()
                addError(e, conversationId, title = context.getString(R.string.error_title_send_message))
            }
        }
        session.setJob(job)
    }

    private fun preprocessUserInputParts(parts: List<UIMessagePart>, assistant: Assistant): List<UIMessagePart> {
        return parts.map { part ->
            when (part) {
                is UIMessagePart.Text -> part.copy(
                    text = part.text.replaceRegexes(assistant = assistant, scope = AssistantAffectScope.USER, mode = RegexApplyMode.OUTPUT)
                )
                else -> part
            }
        }
    }

    // ---- 重新生成 ----

    fun regenerateAtMessage(conversationId: Uuid, message: UIMessage, regenerateAssistantMsg: Boolean = true) {
        val session = getOrCreateSession(conversationId)
        session.getJob()?.cancel()
        val job = appScope.launch {
            try {
                val conversation = session.state.value
                if (message.role == MessageRole.USER) {
                    val node = conversation.getMessageNodeByMessage(message)
                    val indexAt = conversation.messageNodes.indexOf(node)
                    saveConversation(conversationId, conversation.copy(messageNodes = conversation.messageNodes.subList(0, indexAt + 1)))
                    handleMessageComplete(conversationId)
                } else {
                    if (regenerateAssistantMsg) {
                        val node = conversation.getMessageNodeByMessage(message)
                        val nodeIndex = conversation.messageNodes.indexOf(node)
                        handleMessageComplete(conversationId, messageRange = 0..<nodeIndex)
                    } else saveConversation(conversationId, conversation)
                }
                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) { addError(e, conversationId, title = context.getString(R.string.error_title_regenerate_message)) }
        }
        session.setJob(job)
    }

    // ---- 静默续写 ----

    fun continueMessage(conversationId: Uuid, continuePrompt: String) {
        val session = getOrCreateSession(conversationId)
        session.getJob()?.cancel()
        val job = appScope.launch {
            try {
                val conversation = session.state.value
                val lastAssistantIdx = conversation.messageNodes.indexOfLast { it.currentMessage.role == MessageRole.ASSISTANT }
                if (lastAssistantIdx == -1) return@launch
                val settings = settingsStore.settingsFlow.first()
                val assistant = settings.getAssistantById(conversation.assistantId) ?: settings.getCurrentAssistant()
                val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId) ?: return@launch
                val contextMessages = conversation.currentMessages.subList(0, lastAssistantIdx + 1) +
                    listOf(UIMessage(role = MessageRole.SYSTEM, parts = listOf(UIMessagePart.Text(text = continuePrompt))))
                val originalConvMsg = conversation.currentMessages.getOrNull(lastAssistantIdx) ?: return@launch
                val baseParts = originalConvMsg.parts
                var accumulatedContinuation = ""
                generationHandler.generateText(
                    settings = settings, model = model, processingStatus = session.processingStatus,
                    messages = contextMessages, assistant = assistant,
                    conversationSystemPrompt = conversation.customSystemPrompt,
                    conversationModeInjectionIds = conversation.modeInjectionIds,
                    conversationLorebookIds = conversation.lorebookIds,
                    workspaceCwd = conversation.workspaceCwd,
                    memories = if (assistant.useGlobalMemory) memoryRepository.getGlobalMemories() else memoryRepository.getMemoriesOfAssistant(assistant.id.toString()),
                    inputTransformers = buildList { addAll(inputTransformers); add(templateTransformer); add(workspaceReminderTransformer) },
                    outputTransformers = outputTransformers, tools = emptyList(), maxSteps = 1,
                ).collect { chunk ->
                    when (chunk) {
                        is GenerationChunk.Messages -> {
                            val continuationMsg = chunk.messages.lastOrNull() ?: return@collect
                            val currentText = continuationMsg.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }
                            if (currentText.isEmpty() || currentText == accumulatedContinuation) return@collect
                            accumulatedContinuation = currentText
                            val currentConv = session.state.value
                            val node = currentConv.messageNodes.getOrNull(lastAssistantIdx) ?: return@collect
                            val mergedParts = baseParts + UIMessagePart.Text(text = currentText)
                            val mergedMessage = node.currentMessage.copy(parts = mergedParts)
                            val updatedConv = currentConv.copy(
                                messageNodes = currentConv.messageNodes.toMutableList().apply {
                                    set(lastAssistantIdx, node.copy(messages = listOf(mergedMessage), selectIndex = 0))
                                }
                            )
                            session.state.value = updatedConv
                            appEventBus.tryEmit(AppEvent.ChatGenerationUpdate(conversationId, mergedMessage, "..."))
                        }
                    }
                }
                saveConversation(conversationId, session.state.value)
                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) { addError(e, conversationId, title = context.getString(R.string.error_title_generation)) }
        }
        session.setJob(job)
    }

    // ---- 工具审批 ----

    fun handleToolApproval(conversationId: Uuid, toolCallId: String, approved: Boolean, reason: String = "", answer: String? = null) {
        val session = getOrCreateSession(conversationId)
        session.getJob()?.cancel()
        val job = appScope.launch {
            try {
                val conversation = session.state.value
                val newApprovalState = when { answer != null -> ToolApprovalState.Answered(answer); approved -> ToolApprovalState.Approved; else -> ToolApprovalState.Denied(reason) }
                val updatedNodes = conversation.messageNodes.map { node ->
                    node.copy(messages = node.messages.map { msg ->
                        msg.copy(parts = msg.parts.map { part ->
                            if (part is UIMessagePart.Tool && part.toolCallId == toolCallId) part.copy(approvalState = newApprovalState) else part
                        })
                    })
                }
                val updatedConversation = conversation.copy(messageNodes = updatedNodes)
                saveConversation(conversationId, updatedConversation)
                val hasPendingTools = updatedNodes.any { node -> node.currentMessage.parts.any { it is UIMessagePart.Tool && it.isPending } }
                if (!hasPendingTools) handleMessageComplete(conversationId)
                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) { addError(e, conversationId, title = context.getString(R.string.error_title_tool_approval)) }
        }
        session.setJob(job)
    }

    // ---- 消息补全 ----

    private suspend fun handleMessageComplete(conversationId: Uuid, messageRange: ClosedRange<Int>? = null) {
        val settings = settingsStore.settingsFlow.first()
        val initialConversation = getConversationFlow(conversationId).value
        val assistant = settings.getAssistantById(initialConversation.assistantId) ?: settings.getCurrentAssistant()
        val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId) ?: return
        val senderName = if (assistant.useAssistantAvatar) assistant.name.ifEmpty { context.getString(R.string.assistant_page_default_assistant) } else model.displayName

        runCatching {
            updateConversation(conversationId, initialConversation.copy(chatSuggestions = emptyList()))
            if (!model.abilities.contains(ModelAbility.TOOL)) {
                if (assistant.enableWebSearch || mcpManager.getAllAvailableTools().isNotEmpty()) {
                    addError(IllegalStateException(context.getString(R.string.tools_warning)), conversationId, title = context.getString(R.string.error_title_tool_unavailable))
                }
            }
            checkInvalidMessages(conversationId)
            val conversation = getConversationFlow(conversationId).value
            val session = getOrCreateSession(conversationId)

            // ---- 子代理工具：kimi-code spawn_subagent ----
            val subagentTools = if (assistant.enableSubagents) {
                buildSubagentTools(assistant, settings, conversation.workspaceCwd, depth = 0, assistant.subagentMaxDepth, includeBase = false, conversationId = conversationId)
            } else emptyList()

            generationHandler.generateText(
                settings = settings, model = model, processingStatus = session.processingStatus,
                messages = conversation.currentMessages.let { if (messageRange != null) it.subList(messageRange.start, messageRange.endInclusive + 1) else it },
                assistant = assistant,
                conversationSystemPrompt = conversation.customSystemPrompt,
                conversationModeInjectionIds = conversation.modeInjectionIds,
                conversationLorebookIds = conversation.lorebookIds,
                workspaceCwd = conversation.workspaceCwd,
                memories = if (assistant.useGlobalMemory) memoryRepository.getGlobalMemories() else memoryRepository.getMemoriesOfAssistant(assistant.id.toString()),
                inputTransformers = buildList { addAll(inputTransformers); add(templateTransformer); add(workspaceReminderTransformer) },
                outputTransformers = outputTransformers,
                tools = buildList {
                    if (assistant.enableWebSearch) addAll(createSearchTools(settings))
                    addAll(localTools.getTools(assistant.localTools))
                    if (assistant.localTools.contains(LocalToolOption.Scheduler)) {
                        add(me.rerere.rikkahub.data.ai.tools.local.buildCreateScheduleTool(scheduledTaskRepository, assistant))
                        add(me.rerere.rikkahub.data.ai.tools.local.buildListSchedulesTool(scheduledTaskRepository, assistant))
                        add(me.rerere.rikkahub.data.ai.tools.local.buildDeleteScheduleTool(scheduledTaskRepository, assistant))
                        add(me.rerere.rikkahub.data.ai.tools.local.buildToggleScheduleTool(scheduledTaskRepository, assistant))
                    }
                    if (assistant.enableRecentChatsReference) addAll(createConversationTools(conversationRepo, assistant.id))
                    addAll(createWorkspaceToolsIfReady(assistant.workspaceId?.toString(), conversation.workspaceCwd))
                    if (assistant.enabledSkills.isNotEmpty()) {
                        addAll(createSkillTools(enabledSkills = assistant.enabledSkills, allSkills = skillManager.listSkills(), skillManager = skillManager))
                    }
                    mcpManager.getAllAvailableTools().also { allTools ->
                        val invalidNames = allTools.map { it.second }.distinct().filter { name -> name.isEmpty() || !name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } }
                        if (invalidNames.isNotEmpty()) {
                            addError(error = IllegalStateException(context.getString(R.string.error_mcp_invalid_server_name, invalidNames.joinToString(", "))), conversationId = conversationId)
                            return
                        }
                    }.forEach { (serverId, serverName, tool) ->
                        add(Tool(name = "mcp__${serverName}__${tool.name}", description = tool.description ?: "", parameters = { tool.inputSchema }, needsApproval = { tool.needsApproval }, execute = { mcpManager.callTool(serverId, tool.name, it.jsonObject) }))
                    }
                    // kimi-code 子代理委派工具
                    addAll(subagentTools)
                },
            ).onCompletion {
                val updatedConversation = getConversationFlow(conversationId).value.copy(
                    messageNodes = getConversationFlow(conversationId).value.messageNodes.map { node -> node.copy(messages = node.messages.map { it.finishReasoning() }) },
                    updateAt = Instant.now()
                )
                updateConversation(conversationId, updatedConversation)
                appEventBus.emit(AppEvent.ChatGenerationEnded(conversationId = conversationId, senderName = senderName, contentPreview = updatedConversation.currentMessages.lastOrNull()?.toText()?.take(50)?.trim() ?: ""))
            }.collect { chunk ->
                when (chunk) {
                    is GenerationChunk.Messages -> {
                        val updatedConversation = getConversationFlow(conversationId).value.updateCurrentMessages(chunk.messages)
                        updateConversation(conversationId, updatedConversation)
                        chunk.messages.lastOrNull()?.let { lastMessage -> appEventBus.tryEmit(AppEvent.ChatGenerationUpdate(conversationId, lastMessage, senderName)) }
                    }
                }
            }
        }.onFailure {
            appEventBus.tryEmit(AppEvent.ChatGenerationEnded(conversationId, senderName, null))
            it.printStackTrace()
            addError(it, conversationId, title = context.getString(R.string.error_title_generation))
            Logging.log(TAG, "handleMessageComplete: $it")
            Logging.log(TAG, it.stackTraceToString())
        }.onSuccess {
            val finalConversation = getConversationFlow(conversationId).value
            saveConversation(conversationId, finalConversation)
            launchWithConversationReference(conversationId) { generateTitle(conversationId, finalConversation) }
            launchWithConversationReference(conversationId) { generateSuggestion(conversationId, finalConversation) }
        }
    }

    // ========== 子代理系统 (kimi-code 移植) ==========

    private suspend fun buildSubagentTools(
        assistant: Assistant, settings: Settings, workspaceCwd: String?,
        depth: Int, maxDepth: Int, includeBase: Boolean,
        conversationId: Uuid? = null,
        mcpServerIds: Set<Uuid>? = null,
    ): List<Tool> {
        val profiles = mergeSubagentProfiles(assistant.subagentProfiles, assistant.disabledBuiltinSubagents)
        val result = mutableListOf<Tool>()

        if (includeBase) {
            result += SubagentHost.sandboxToolsForSubagent(buildSubagentBaseTools(assistant, settings, workspaceCwd, mcpServerIds))
        }

        // maxDepth 语义 = 允许嵌套的子代理层数: depth 从 0 起, depth < maxDepth 时允许再 spawn
        if (depth < maxDepth && profiles.isNotEmpty()) {
            result += createSubagentTools(
                profiles = profiles, json = json,
                includeAskBtw = assistant.localTools.contains(LocalToolOption.AskBtw),
                spawn = { profileName, task, _ ->
                    val profile = profiles.firstOrNull { it.name == profileName }
                    if (profile == null) SubagentResult(profileName = profileName, summary = "", succeeded = false, error = "Subagent profile not found: $profileName", depth = depth + 1)
                    else {
                        val parentModel = settings.findModelById(assistant.chatModelId ?: settings.chatModelId) ?: error("Model not found")
                        subagentHost.spawn(
                            profile = profile, task = task, settings = settings,
                            parentAssistant = assistant, parentModel = parentModel,
                            buildChildTools = { child, d -> buildSubagentTools(child, settings, workspaceCwd, d, maxDepth, includeBase = true, mcpServerIds = profile.mcpServerIds) },
                            depth = depth + 1, maxDepth = maxDepth,
                            onProgress = if (conversationId != null) { subMessages -> updateSubagentProgress(conversationId, null, profileName, subMessages) } else null,
                        )
                    }
                },
                askBtw = { question ->
                    val btwProfile = SubagentProfile(name = "btw", systemPrompt = assistant.systemPrompt, inheritTools = false, maxSteps = 1)
                    val parentModel = settings.findModelById(assistant.chatModelId ?: settings.chatModelId) ?: return@createSubagentTools "(side agent failed: model not found)"
                    val r = subagentHost.spawn(profile = btwProfile, task = question, settings = settings, parentAssistant = assistant, parentModel = parentModel, buildChildTools = { _, _ -> emptyList() }, depth = depth + 1, maxDepth = maxDepth)
                    if (r.succeeded) r.summary else "(side agent failed: ${r.error})"
                },
            )
        }

        if (depth == 0) {
            result += createManageSubagentTool(profiles = profiles, json = json, manage = { action, name, profile -> manageSubagentProfile(assistant.id, action, name, profile) })
        }

        return result
    }

    private suspend fun buildSubagentBaseTools(
        assistant: Assistant, settings: Settings, workspaceCwd: String?,
        mcpServerIds: Set<Uuid>? = null,
    ): List<Tool> = buildList {
        if (assistant.enableWebSearch) addAll(createSearchTools(settings))
        addAll(SubagentHost.sandboxToolsForSubagent(localTools.getTools(assistant.localTools.filter { it != LocalToolOption.AskUser })))
        addAll(createWorkspaceToolsIfReady(assistant.workspaceId?.toString(), workspaceCwd))
        if (assistant.enabledSkills.isNotEmpty()) addAll(createSkillTools(enabledSkills = assistant.enabledSkills, allSkills = skillManager.listSkills(), skillManager = skillManager))
        // MCP 工具: profile 配了 mcpServerIds 白名单则只挂白名单内的 server, 否则全部挂载
        mcpManager.getAllAvailableTools()
            .filter { (serverId, _, _) -> mcpServerIds.isNullOrEmpty() || serverId in mcpServerIds }
            .forEach { (serverId, serverName, tool) ->
                if (serverName.isNotEmpty() && serverName.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }) {
                    add(Tool(name = "mcp__${serverName}__${tool.name}", description = tool.description ?: "", parameters = { tool.inputSchema }, needsApproval = { tool.needsApproval }, execute = { mcpManager.callTool(serverId, tool.name, it.jsonObject) }))
                }
            }
    }

    private suspend fun manageSubagentProfile(assistantId: Uuid, action: String, name: String, profile: SubagentProfile?): String {
        val current = settingsStore.settingsFlow.first()
        val target = current.assistants.firstOrNull { it.id == assistantId } ?: return "Error: assistant not found"
        val merged = mergeSubagentProfiles(target.subagentProfiles, target.disabledBuiltinSubagents)
        return when (action) {
            "list" -> if (merged.isEmpty()) "No subagent profiles available." else "Available subagent profiles (${merged.size}):\n" + merged.joinToString("\n") { "- ${it.name}: ${it.description.ifBlank { "(no description)" }}" }
            "create", "update" -> {
                val p = profile ?: return "Error: profile data missing"
                settingsStore.update { s -> s.copy(assistants = s.assistants.map { if (it.id == assistantId) it.copy(subagentProfiles = upsertSubagentProfile(it.subagentProfiles, p)) else it }) }
                "$action: subagent profile '${p.name}' saved."
            }
            "delete" -> {
                if (name.isBlank()) return "Error: name required for delete"
                val isBuiltin = SubagentProfile.BUILTIN.any { it.name == name }
                settingsStore.update { s -> s.copy(assistants = s.assistants.map { if (it.id == assistantId) it.copy(subagentProfiles = removeSubagentProfile(it.subagentProfiles, name), disabledBuiltinSubagents = if (isBuiltin) it.disabledBuiltinSubagents + name else it.disabledBuiltinSubagents) else it }) }
                "delete: subagent profile '$name' removed." + if (isBuiltin) " (built-in profile disabled)" else ""
            }
            else -> "Error: unknown action '$action'"
        }
    }

    private fun updateSubagentProgress(conversationId: Uuid, toolCallId: String?, profileName: String, subMessages: List<UIMessage>) {
        runCatching {
            val transcript = SubagentHost.buildTranscript(subMessages, truncateToolOutput = 2000)
            if (transcript.isEmpty()) return@runCatching
            val listSerializer = kotlinx.serialization.builtins.ListSerializer(SubagentTranscriptStep.serializer())
            val transcriptMetadata = buildJsonObject {
                put("subagent_transcript", json.encodeToJsonElement(listSerializer, transcript))
                put("subagent_profile", JsonPrimitive(profileName))
                put("subagent_steps", JsonPrimitive(transcript.size))
                put("subagent_succeeded", JsonPrimitive(false))
                put("subagent_streaming", JsonPrimitive(true))
            }
            val partialOutput = UIMessagePart.Text(text = "{\"profile_name\":\"$profileName\",\"succeeded\":false,\"streaming\":true}", metadata = transcriptMetadata)
            updateConversationState(conversationId) { conversation ->
                val messages = conversation.currentMessages
                val lastAssistantIndex = messages.indexOfLast { it.role == MessageRole.ASSISTANT }
                if (lastAssistantIndex < 0) return@updateConversationState conversation
                val updatedMessages = messages.mapIndexed { index, message ->
                    if (index != lastAssistantIndex) return@mapIndexed message
                    val matchesTool: (UIMessagePart.Tool) -> Boolean = { part -> part.toolName == "spawn_subagent" && (!part.isExecuted || isStreamingSubagent(part)) && (toolCallId == null || part.toolCallId == toolCallId) }
                    if (!message.parts.any { it is UIMessagePart.Tool && matchesTool(it) }) return@mapIndexed message
                    message.copy(parts = message.parts.map { part -> if (part is UIMessagePart.Tool && matchesTool(part)) part.copy(output = listOf(partialOutput)) else part })
                }
                conversation.updateCurrentMessages(updatedMessages)
            }
        }.onFailure { Log.w(TAG, "updateSubagentProgress failed: ${it.message}") }
    }

    private fun isStreamingSubagent(part: UIMessagePart.Tool): Boolean {
        val textPart = part.output.filterIsInstance<UIMessagePart.Text>().firstOrNull()
        return textPart?.metadata?.get("subagent_streaming")?.jsonPrimitive?.contentOrNull == "true"
    }

    // ========== 子代理系统结束 ==========

    private suspend fun createWorkspaceToolsIfReady(workspaceId: String?, cwd: String? = null): List<Tool> {
        if (workspaceId.isNullOrBlank()) return emptyList()
        val workspace = workspaceRepository.getById(workspaceId) ?: return emptyList()
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) { Log.d(TAG, "createWorkspaceToolsIfReady: skip, status=${workspace.shellStatus}"); return emptyList() }
        return createWorkspaceTools(workspaceId = workspaceId, workspaceRepository = workspaceRepository, cwd = cwd, shellSessionManager = shellSessionManager, backgroundShellManager = backgroundShellManager, shellAuditLogger = shellAuditLogger)
    }

    private fun checkInvalidMessages(conversationId: Uuid) {
        val conversation = getConversationFlow(conversationId).value
        var messagesNodes = conversation.messageNodes
        messagesNodes = messagesNodes.mapIndexed { _, node ->
            val hasPendingTools = node.currentMessage.getTools().any { !it.isExecuted }
            if (hasPendingTools) {
                val hasResumableTool = node.currentMessage.getTools().any { !it.isExecuted && it.approvalState.canResumeToolExecution() }
                if (hasResumableTool) return@mapIndexed node
                val allToolsExecuted = node.currentMessage.getTools().all { it.isExecuted }
                if (allToolsExecuted && node.currentMessage.getTools().isNotEmpty()) return@mapIndexed node
                return@mapIndexed node.copy(messages = node.messages.filter { it.id != node.currentMessage.id }, selectIndex = node.selectIndex - 1)
            }
            node
        }
        messagesNodes = messagesNodes.map { node -> if (node.messages.isNotEmpty() && node.selectIndex !in node.messages.indices) node.copy(selectIndex = 0) else node }
        messagesNodes = messagesNodes.filter { it.messages.isNotEmpty() }
        updateConversation(conversationId, conversation.copy(messageNodes = messagesNodes))
    }

    private fun cancelToolByUser(tool: UIMessagePart.Tool): UIMessagePart.Tool = tool.copy(
        output = listOf(UIMessagePart.Text("""{"status":"cancelled","error":"Generation cancelled by user before tool execution completed."}""")),
        approvalState = ToolApprovalState.Denied("Generation cancelled by user")
    )

    private suspend fun finishInterruptedPendingTools(conversationId: Uuid) {
        val currentConversation = getConversationFlow(conversationId).value
        val lastNode = currentConversation.messageNodes.lastOrNull() ?: return
        val lastMessage = lastNode.currentMessage
        val updatedMessage = lastMessage.finishPendingTools(::cancelToolByUser)
        if (updatedMessage == lastMessage) return
        val updatedConversation = currentConversation.copy(
            messageNodes = currentConversation.messageNodes.dropLast(1) + lastNode.copy(messages = lastNode.messages.map { if (it.id == lastMessage.id) updatedMessage else it })
        )
        saveConversation(conversationId, updatedConversation)
    }

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
            addError(error = it, conversationId = conversationId, title = context.getString(R.string.error_title_generate_title), solution = ChatErrorSolution.CheckTitleModelSettings)
        }
    }

    // ---- 生成建议 ----

    suspend fun generateSuggestion(conversationId: Uuid, conversation: Conversation) {
        runCatching {
            val settings = settingsStore.settingsFlow.first()
            if (!settings.enableSuggestion) return
            val model = settings.findModelById(settings.suggestionModelId, fallback = settings.fastModelId) ?: return
            val provider = model.findProvider(settings.providers) ?: return
            sessions[conversationId]?.let { updateConversation(conversationId, it.state.value.copy(chatSuggestions = emptyList())) }
            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(UIMessage.user(settings.suggestionPrompt.applyPlaceholders("locale" to Locale.getDefault().displayName, "content" to conversation.currentMessages.takeLast(8).joinToString("\n\n") { it.summaryAsText(maxLength = 500) }))),
                params = backgroundTextGenerationParams(model),
            )
            val suggestions = result.choices[0].message?.toText()?.split("\n")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
            val latestConversation = conversationRepo.getConversationById(conversationId) ?: sessions[conversationId]?.state?.value ?: conversation
            saveConversation(conversationId, latestConversation.copy(chatSuggestions = suggestions.take(10)))
        }.onFailure { it.printStackTrace() }
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

    // ---- 对话状态 ----

    private fun updateConversation(conversationId: Uuid, conversation: Conversation) {
        if (conversation.id != conversationId) return
        val session = getOrCreateSession(conversationId)
        checkFilesDelete(conversation, session.state.value)
        session.state.value = conversation
    }

    fun updateConversationState(conversationId: Uuid, update: (Conversation) -> Conversation) {
        val current = getConversationFlow(conversationId).value
        updateConversation(conversationId, update(current))
    }

    suspend fun moveConversationToFolder(conversationId: Uuid, folderId: Uuid?) {
        if (sessions.containsKey(conversationId)) updateConversationState(conversationId) { it.copy(folderId = folderId) }
        conversationRepo.updateConversationFolderId(conversationId, folderId)
    }

    fun hasGeneratingConversationInFolder(folderId: Uuid): Boolean = sessions.values.any { it.isGenerating && it.state.value.folderId == folderId }

    suspend fun deleteFolder(folderId: Uuid) {
        sessions.values.filter { it.state.value.folderId == folderId }.forEach { updateConversationState(it.id) { c -> c.copy(folderId = null) } }
        folderRepository.deleteFolder(folderId)
    }

    private fun checkFilesDelete(newConversation: Conversation, oldConversation: Conversation) {
        val deletedFiles = oldConversation.files.filter { file -> newConversation.files.none { it == file } }
        if (deletedFiles.isNotEmpty()) { filesManager.deleteChatFiles(deletedFiles); Log.w(TAG, "checkFilesDelete: $deletedFiles") }
    }

    suspend fun saveConversation(conversationId: Uuid, conversation: Conversation) {
        val exists = conversationRepo.existsConversationById(conversation.id)
        if (!exists && conversation.title.isBlank() && conversation.messageNodes.isEmpty()) return
        val updatedConversation = conversation.copy()
        updateConversation(conversationId, updatedConversation)
        if (!exists) conversationRepo.insertConversation(updatedConversation) else conversationRepo.updateConversation(updatedConversation)
    }

    // ---- 翻译 ----

    fun translateMessage(conversationId: Uuid, message: UIMessage, targetLanguage: Locale) {
        appScope.launch(Dispatchers.IO) {
            try {
                val settings = settingsStore.settingsFlow.first()
                val messageText = message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n\n") { it.text }.trim()
                if (messageText.isBlank()) return@launch
                updateTranslationField(conversationId, message.id, context.getString(R.string.translating))
                generationHandler.translateText(settings = settings, sourceText = messageText, targetLanguage = targetLanguage) { translatedText -> updateTranslationField(conversationId, message.id, translatedText) }.collect {}
                saveConversation(conversationId, getConversationFlow(conversationId).value)
            } catch (e: Exception) { clearTranslationField(conversationId, message.id); addError(e, conversationId, title = context.getString(R.string.error_title_translate_message)) }
        }
    }

    private fun updateTranslationField(conversationId: Uuid, messageId: Uuid, translationText: String) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) node.copy(messages = node.messages.map { if (it.id == messageId) it.copy(translation = translationText) else it })
            else node
        }
        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    // ---- 消息操作 ----

    suspend fun editMessage(conversationId: Uuid, messageId: Uuid, parts: List<UIMessagePart>) {
        if (parts.isEmptyInputMessage()) return
        val currentConversation = getConversationFlow(conversationId).value
        val settings = settingsStore.settingsFlow.first()
        val assistant = settings.getAssistantById(currentConversation.assistantId) ?: settings.getCurrentAssistant()
        val processedParts = preprocessUserInputParts(parts, assistant)
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

    fun clearTranslationField(conversationId: Uuid, messageId: Uuid) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) node.copy(messages = node.messages.map { if (it.id == messageId) it.copy(translation = null) else it })
            else node
        }
        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    suspend fun stopGeneration(conversationId: Uuid) {
        val job = sessions[conversationId]?.getJob() ?: return
        job.cancel()
        runCatching { job.join() }
        finishInterruptedPendingTools(conversationId)
    }
}
