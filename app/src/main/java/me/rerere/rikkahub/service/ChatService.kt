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
import me.rerere.rikkahub.data.ai.subagent.LruSessionCache
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
    /** 子代理编排器（kimi-code 子代理系统）：从本类抽出的 C 簇职责，零 sessions 访问 */
    private val subagentOrchestrator: SubagentOrchestrator by lazy {
        SubagentOrchestrator(
            subagentHost = subagentHost,
            json = json,
            settingsStore = settingsStore,
            workspaceRepository = workspaceRepository,
            localTools = localTools,
            skillManager = skillManager,
            mcpManager = mcpManager,
            workspaceToolsFactory = { wsId, cwd -> createWorkspaceToolsIfReady(wsId, cwd) },
            updateConversationState = { id, update -> updateConversationState(id, update) },
        )
    }

    /** 对话辅助生成器（标题/建议/压缩/翻译）：从本类抽出的职责，通过 lambda 回调本类保存/更新/报错 */
    private val conversationAssistant: ConversationAssistant by lazy {
        ConversationAssistant(
            context = context,
            appScope = appScope,
            settingsStore = settingsStore,
            providerManager = providerManager,
            conversationRepo = conversationRepo,
            generationHandler = generationHandler,
            saveConversation = { id, conv -> saveConversation(id, conv) },
            updateConversation = { id, conv -> updateConversation(id, conv) },
            currentConversation = { id -> getConversationFlow(id).value },
            reportError = { e, cid, title, solution -> addError(e, cid, title, solution) },
        )
    }

    private val workspaceReminderTransformer = WorkspaceReminderTransformer(workspaceRepository)

    /** 会话管理器（A+E 簇）：拥有 sessions map，负责会话生命周期/持久化/消息CRUD/文件夹 */
    private val sessionManager: SessionManager by lazy {
        SessionManager(
            appScope = appScope,
            settingsStore = settingsStore,
            conversationRepo = conversationRepo,
            folderRepository = folderRepository,
            filesManager = filesManager,
            preprocessUserInput = { parts, assistant -> preprocessUserInputParts(parts, assistant) },
        )
    }

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

    fun cleanup() = sessionManager.cleanup()

    // ---- Session ----

    private fun getOrCreateSession(conversationId: Uuid): ConversationSession = sessionManager.getOrCreateSession(conversationId)

    fun addConversationReference(conversationId: Uuid) { sessionManager.addConversationReference(conversationId) }

    fun removeConversationReference(conversationId: Uuid) { sessionManager.removeConversationReference(conversationId) }

    private fun launchWithConversationReference(conversationId: Uuid, block: suspend () -> Unit): Job =
        sessionManager.launchWithConversationReference(conversationId, block)

    fun getConversationFlow(conversationId: Uuid): StateFlow<Conversation> = sessionManager.getConversationFlow(conversationId)

    fun getGenerationJobStateFlow(conversationId: Uuid): Flow<Job?> = sessionManager.getGenerationJobStateFlow(conversationId)

    fun getProcessingStatusFlow(conversationId: Uuid): StateFlow<String?> = sessionManager.getProcessingStatusFlow(conversationId)

    fun getConversationJobs(): Flow<Map<Uuid, Job?>> = sessionManager.getConversationJobs()

    suspend fun initializeConversation(conversationId: Uuid) = sessionManager.initializeConversation(conversationId)

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
                subagentOrchestrator.buildSubagentTools(assistant, settings, conversation.workspaceCwd, depth = 0, assistant.subagentMaxDepth, includeBase = false, conversationId = conversationId)
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

    suspend fun generateTitle(conversationId: Uuid, conversation: Conversation, force: Boolean = false) =
        conversationAssistant.generateTitle(conversationId, conversation, force)

    // ---- 生成建议 ----

    suspend fun generateSuggestion(conversationId: Uuid, conversation: Conversation) =
        conversationAssistant.generateSuggestion(conversationId, conversation)

    // ---- 压缩对话历史 ----

    suspend fun compressConversation(conversationId: Uuid, conversation: Conversation, additionalPrompt: String, targetTokens: Int, keepRecentMessages: Int = 32): Result<Unit> =
        conversationAssistant.compressConversation(conversationId, conversation, additionalPrompt, targetTokens, keepRecentMessages)

    // ---- 对话状态 ----

    private fun updateConversation(conversationId: Uuid, conversation: Conversation) = sessionManager.updateConversation(conversationId, conversation)

    fun updateConversationState(conversationId: Uuid, update: (Conversation) -> Conversation) = sessionManager.updateConversationState(conversationId, update)

    suspend fun moveConversationToFolder(conversationId: Uuid, folderId: Uuid?) = sessionManager.moveConversationToFolder(conversationId, folderId)

    fun hasGeneratingConversationInFolder(folderId: Uuid): Boolean = sessionManager.hasGeneratingConversationInFolder(folderId)

    suspend fun deleteFolder(folderId: Uuid) = sessionManager.deleteFolder(folderId)

    suspend fun saveConversation(conversationId: Uuid, conversation: Conversation) = sessionManager.saveConversation(conversationId, conversation)

    // ---- 翻译 ----

    fun translateMessage(conversationId: Uuid, message: UIMessage, targetLanguage: Locale) =
        conversationAssistant.translateMessage(conversationId, message, targetLanguage)

    private fun updateTranslationField(conversationId: Uuid, messageId: Uuid, translationText: String) =
        conversationAssistant.updateTranslationField(conversationId, messageId, translationText)

    // ---- 消息操作 ----

    suspend fun editMessage(conversationId: Uuid, messageId: Uuid, parts: List<UIMessagePart>) = sessionManager.editMessage(conversationId, messageId, parts)

    suspend fun forkConversationAtMessage(conversationId: Uuid, messageId: Uuid): Conversation = sessionManager.forkConversationAtMessage(conversationId, messageId)

    suspend fun selectMessageNode(conversationId: Uuid, nodeId: Uuid, selectIndex: Int) = sessionManager.selectMessageNode(conversationId, nodeId, selectIndex)

    suspend fun deleteMessage(conversationId: Uuid, messageId: Uuid, failIfMissing: Boolean = true) = sessionManager.deleteMessage(conversationId, messageId, failIfMissing)

    suspend fun deleteMessage(conversationId: Uuid, message: UIMessage) { sessionManager.deleteMessage(conversationId, message) }

    fun clearTranslationField(conversationId: Uuid, messageId: Uuid) =
        conversationAssistant.clearTranslationField(conversationId, messageId)

    suspend fun stopGeneration(conversationId: Uuid) {
        val job = sessionManager.getSessionJob(conversationId) ?: return
        job.cancel()
        runCatching { job.join() }
        finishInterruptedPendingTools(conversationId)
    }
}
