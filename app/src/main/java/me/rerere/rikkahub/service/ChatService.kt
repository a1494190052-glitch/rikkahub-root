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

internal fun backgroundTextGenerationParams(model: Model, reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO) = TextGenerationParams(model = model, reasoningLevel = reasoningLevel, customHeaders = model.customHeaders, customBody = model.customBodies)
data class ChatError(val id: Uuid = Uuid.random(), val title: String? = null, val error: Throwable, val conversationId: Uuid? = null, val timestamp: Long = System.currentTimeMillis(), val solution: ChatErrorSolution? = null)
enum class ChatErrorSolution { CheckTitleModelSettings }

private val inputTransformers by lazy { listOf(TimeReminderTransformer, PromptInjectionTransformer, PlaceholderTransformer, DocumentAsPromptTransformer, OcrTransformer, RegexInputTransformer) }
private val outputTransformers by lazy { listOf(ThinkTagTransformer, Base64ImageToLocalFileTransformer, RegexOutputTransformer) }

class ChatService(
    private val context: Application, private val appScope: AppScope, private val appEventBus: AppEventBus,
    private val settingsStore: SettingsStore, private val conversationRepo: ConversationRepository,
    private val memoryRepository: MemoryRepository, private val generationHandler: GenerationHandler,
    private val templateTransformer: TemplateTransformer, private val providerManager: ProviderManager,
    private val localTools: LocalTools, private val subAgentExecutor: me.rerere.rikkahub.data.ai.tools.local.SubAgentExecutor,
    private val scheduledTaskRepository: me.rerere.rikkahub.service.scheduler.ScheduledTaskRepository,
    val mcpManager: McpManager, private val filesManager: FilesManager, private val skillManager: SkillManager,
    private val workspaceRepository: WorkspaceRepository, private val folderRepository: FolderRepository,
    private val shellSessionManager: me.rerere.workspace.ShellSessionManager,
    private val backgroundShellManager: me.rerere.rikkahub.service.shell.BackgroundShellManager,
    private val shellAuditLogger: me.rerere.rikkahub.service.shell.ShellAuditLogger,
    private val subagentHost: SubagentHost, private val json: Json,
) {
    private val workspaceReminderTransformer = WorkspaceReminderTransformer(workspaceRepository)
    private val sessions = ConcurrentHashMap<Uuid, ConversationSession>()
    private val _sessionsVersion = MutableStateFlow(0L)
    private val _errors = MutableStateFlow<List<ChatError>>(emptyList())
    val errors: StateFlow<List<ChatError>> = _errors.asStateFlow()
    fun addError(error: Throwable, conversationId: Uuid? = null, title: String? = null, solution: ChatErrorSolution? = null) { if (error is CancellationException) return; _errors.update { it + ChatError(title = title, error = error, conversationId = conversationId, solution = solution) } }
    fun dismissError(id: Uuid) { _errors.update { list -> list.filter { it.id != id } } }
    fun clearAllErrors() { _errors.value = emptyList() }
    private val _generationDoneFlow = MutableSharedFlow<Uuid>()
    val generationDoneFlow: SharedFlow<Uuid> = _generationDoneFlow.asSharedFlow()

    fun cleanup() = runCatching { sessions.values.forEach { it.cleanup() }; sessions.clear() }

    private fun getOrCreateSession(conversationId: Uuid) = sessions.computeIfAbsent(conversationId) { id ->
        val settings = settingsStore.settingsFlow.value
        ConversationSession(id = id, initial = Conversation.ofId(id = id, assistantId = settings.getCurrentAssistant().id), scope = appScope, onIdle = { removeSession(it) }).also { _sessionsVersion.value++; Log.i(TAG, "createSession: $id") }
    }
    private fun removeSession(conversationId: Uuid) { val s = sessions[conversationId] ?: return; if (s.isInUse) return; if (sessions.remove(conversationId, s)) { s.cleanup(); _sessionsVersion.value++ } }
    fun addConversationReference(cid: Uuid) { getOrCreateSession(cid).acquire() }
    fun removeConversationReference(cid: Uuid) { sessions[cid]?.release() }

    fun getConversationFlow(cid: Uuid): StateFlow<Conversation> = getOrCreateSession(cid).state
    fun getGenerationJobStateFlow(cid: Uuid): Flow<Job?> = sessions[cid]?.generationJob ?: flowOf(null)
    fun getProcessingStatusFlow(cid: Uuid): StateFlow<String?> = sessions[cid]?.processingStatus ?: MutableStateFlow(null)
    fun getConversationJobs(): Flow<Map<Uuid, Job?>> = _sessionsVersion.flatMapLatest { combine(sessions.values.map { it.generationJob.map { j -> it.id to j } }) { ps -> ps.filter { it.second != null }.toMap() }.let { if (sessions.isEmpty()) flowOf(emptyMap()) else it } }

    suspend fun initializeConversation(conversationId: Uuid) { getOrCreateSession(conversationId); conversationRepo.getConversationById(conversationId)?.let { updateConversation(conversationId, it); settingsStore.updateAssistant(it.assistantId) } ?: run { val cs = settingsStore.settingsFlowRaw.first(); val a = cs.getCurrentAssistant(); updateConversation(conversationId, Conversation.ofId(id = conversationId, assistantId = a.id, newConversation = true).updateCurrentMessages(a.presetMessages)) } }

    fun sendMessage(conversationId: Uuid, content: List<UIMessagePart>, answer: Boolean = true) {
        if (content.isEmptyInputMessage()) return; val session = getOrCreateSession(conversationId); session.getJob()?.cancel()
        session.setJob(appScope.launch {
            try { runCatching { session.getJob()?.join() }; finishInterruptedPendingTools(conversationId); val c = session.state.value; val s = settingsStore.settingsFlow.first(); val a = s.getAssistantById(c.assistantId) ?: s.getCurrentAssistant(); saveConversation(conversationId, c.copy(messageNodes = c.messageNodes + UIMessage(role = MessageRole.USER, parts = preprocessUserInputParts(content, a)).toMessageNode())); if (answer) handleMessageComplete(conversationId); _generationDoneFlow.emit(conversationId) } catch (e: Exception) { e.printStackTrace(); addError(e, conversationId, title = context.getString(R.string.error_title_send_message)) }
        })
    }
    private fun preprocessUserInputParts(parts: List<UIMessagePart>, a: Assistant) = parts.map { if (it is UIMessagePart.Text) it.copy(text = it.text.replaceRegexes(assistant = a, scope = AssistantAffectScope.USER, mode = RegexApplyMode.OUTPUT)) else it }

    fun regenerateAtMessage(cid: Uuid, msg: UIMessage, regen: Boolean = true) { val s = getOrCreateSession(cid); s.getJob()?.cancel(); s.setJob(appScope.launch { try { val c = s.state.value; if (msg.role == MessageRole.USER) { val idx = c.messageNodes.indexOf(c.getMessageNodeByMessage(msg)); saveConversation(cid, c.copy(messageNodes = c.messageNodes.subList(0, idx + 1))); handleMessageComplete(cid) } else { if (regen) { val idx = c.messageNodes.indexOf(c.getMessageNodeByMessage(msg)); handleMessageComplete(cid, messageRange = 0..<idx) } else saveConversation(cid, c) }; _generationDoneFlow.emit(cid) } catch (e: Exception) { addError(e, cid, title = context.getString(R.string.error_title_regenerate_message)) } }) }

    fun continueMessage(cid: Uuid, prompt: String) {
        val s = getOrCreateSession(cid); s.getJob()?.cancel()
        s.setJob(appScope.launch {
            try { val c = s.state.value; val aidx = c.messageNodes.indexOfLast { it.currentMessage.role == MessageRole.ASSISTANT }; if (aidx == -1) return@launch; val st = settingsStore.settingsFlow.first(); val a = st.getAssistantById(c.assistantId) ?: st.getCurrentAssistant(); val m = st.findModelById(a.chatModelId ?: st.chatModelId) ?: return@launch; val ctx = c.currentMessages.subList(0, aidx + 1) + listOf(UIMessage(role = MessageRole.SYSTEM, parts = listOf(UIMessagePart.Text(text = prompt)))); val bp = c.currentMessages[aidx].parts; var acc = ""
                generationHandler.generateText(settings = st, model = m, processingStatus = s.processingStatus, messages = ctx, assistant = a, conversationSystemPrompt = c.customSystemPrompt, conversationModeInjectionIds = c.modeInjectionIds, conversationLorebookIds = c.lorebookIds, workspaceCwd = c.workspaceCwd, memories = if (a.useGlobalMemory) memoryRepository.getGlobalMemories() else memoryRepository.getMemoriesOfAssistant(a.id.toString()), inputTransformers = buildList { addAll(inputTransformers); add(templateTransformer); add(workspaceReminderTransformer) }, outputTransformers = outputTransformers, tools = emptyList(), maxSteps = 1).collect { ch ->
                    if (ch is GenerationChunk.Messages) { val txt = ch.messages.lastOrNull()?.parts?.filterIsInstance<UIMessagePart.Text>()?.joinToString("") { it.text } ?: ""; if (txt.isEmpty() || txt == acc) return@collect; acc = txt; val cv = s.state.value; val nd = cv.messageNodes[aidx]; s.state.value = cv.copy(messageNodes = cv.messageNodes.toMutableList().apply { set(aidx, nd.copy(messages = listOf(nd.currentMessage.copy(parts = bp + UIMessagePart.Text(text = txt))), selectIndex = 0)) }); appEventBus.tryEmit(AppEvent.ChatGenerationUpdate(cid, nd.currentMessage.copy(parts = bp + UIMessagePart.Text(text = txt)), "...")) }
                }; saveConversation(cid, s.state.value); _generationDoneFlow.emit(cid)
            } catch (e: Exception) { addError(e, cid, title = context.getString(R.string.error_title_generation)) }
        })
    }

    fun handleToolApproval(cid: Uuid, tcid: String, approved: Boolean, reason: String = "", answer: String? = null) { val s = getOrCreateSession(cid); s.getJob()?.cancel(); s.setJob(appScope.launch { try { val c = s.state.value; val ns = when { answer != null -> ToolApprovalState.Answered(answer); approved -> ToolApprovalState.Approved; else -> ToolApprovalState.Denied(reason) }; val nc = c.copy(messageNodes = c.messageNodes.map { n -> n.copy(messages = n.messages.map { m -> m.copy(parts = m.parts.map { p -> if (p is UIMessagePart.Tool && p.toolCallId == tcid) p.copy(approvalState = ns) else p }) }) }); saveConversation(cid, nc); if (!nc.messageNodes.any { n -> n.currentMessage.parts.any { it is UIMessagePart.Tool && it.isPending } }) handleMessageComplete(cid); _generationDoneFlow.emit(cid) } catch (e: Exception) { addError(e, cid, title = context.getString(R.string.error_title_tool_approval)) } }) }

    private suspend fun handleMessageComplete(cid: Uuid, messageRange: ClosedRange<Int>? = null) {
        val st = settingsStore.settingsFlow.first(); val ic = getConversationFlow(cid).value
        val a = st.getAssistantById(ic.assistantId) ?: st.getCurrentAssistant()
        val m = st.findModelById(a.chatModelId ?: st.chatModelId) ?: return
        val sn = if (a.useAssistantAvatar) a.name.ifEmpty { context.getString(R.string.assistant_page_default_assistant) } else m.displayName
        runCatching {
            updateConversation(cid, ic.copy(chatSuggestions = emptyList()))
            if (!m.abilities.contains(ModelAbility.TOOL) && (a.enableWebSearch || mcpManager.getAllAvailableTools().isNotEmpty())) addError(IllegalStateException(context.getString(R.string.tools_warning)), cid, title = context.getString(R.string.error_title_tool_unavailable))
            checkInvalidMessages(cid); val c = getConversationFlow(cid).value; val s = getOrCreateSession(cid)
            val subagentTools = if (a.enableSubagents) buildSubagentTools(a, st, c.workspaceCwd, 0, a.subagentMaxDepth, false, cid) else emptyList()
            generationHandler.generateText(settings = st, model = m, processingStatus = s.processingStatus,
                messages = c.currentMessages.let { if (messageRange != null) it.subList(messageRange.start, messageRange.endInclusive + 1) else it },
                assistant = a, conversationSystemPrompt = c.customSystemPrompt, conversationModeInjectionIds = c.modeInjectionIds, conversationLorebookIds = c.lorebookIds, workspaceCwd = c.workspaceCwd,
                memories = if (a.useGlobalMemory) memoryRepository.getGlobalMemories() else memoryRepository.getMemoriesOfAssistant(a.id.toString()),
                inputTransformers = buildList { addAll(inputTransformers); add(templateTransformer); add(workspaceReminderTransformer) }, outputTransformers = outputTransformers,
                tools = buildList {
                    if (a.enableWebSearch) addAll(createSearchTools(st))
                    addAll(localTools.getTools(a.localTools))
                    if (a.localTools.contains(LocalToolOption.Scheduler)) { add(me.rerere.rikkahub.data.ai.tools.local.buildCreateScheduleTool(scheduledTaskRepository, a)); add(me.rerere.rikkahub.data.ai.tools.local.buildListSchedulesTool(scheduledTaskRepository, a)); add(me.rerere.rikkahub.data.ai.tools.local.buildDeleteScheduleTool(scheduledTaskRepository, a)); add(me.rerere.rikkahub.data.ai.tools.local.buildToggleScheduleTool(scheduledTaskRepository, a)) }
                    if (a.enableRecentChatsReference) addAll(createConversationTools(conversationRepo, a.id))
                    addAll(createWorkspaceToolsIfReady(a.workspaceId?.toString(), c.workspaceCwd))
                    if (a.enabledSkills.isNotEmpty()) addAll(createSkillTools(a.enabledSkills, skillManager.listSkills(), skillManager))
                    mcpManager.getAllAvailableTools().also { at -> val iv = at.map { it.second }.distinct().filter { n -> n.isEmpty() || !n.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } }; if (iv.isNotEmpty()) { addError(error = IllegalStateException(context.getString(R.string.error_mcp_invalid_server_name, iv.joinToString(", "))), conversationId = cid); return } }.forEach { (sid, snm, t) -> add(Tool(name = "mcp__${snm}__${t.name}", description = t.description ?: "", parameters = { t.inputSchema }, needsApproval = { t.needsApproval }, execute = { mcpManager.callTool(sid, t.name, it.jsonObject) })) }
                    addAll(subagentTools)
                }
            ).onCompletion { val uc = getConversationFlow(cid).value.copy(messageNodes = getConversationFlow(cid).value.messageNodes.map { it.copy(messages = it.messages.map { mm -> mm.finishReasoning() }) }, updateAt = Instant.now()); updateConversation(cid, uc); appEventBus.emit(AppEvent.ChatGenerationEnded(cid, sn, uc.currentMessages.lastOrNull()?.toText()?.take(50)?.trim() ?: "")) }
            .collect { ch -> if (ch is GenerationChunk.Messages) { val uc = getConversationFlow(cid).value.updateCurrentMessages(ch.messages); updateConversation(cid, uc); ch.messages.lastOrNull()?.let { appEventBus.tryEmit(AppEvent.ChatGenerationUpdate(cid, it, sn)) } } }
        }.onFailure { appEventBus.tryEmit(AppEvent.ChatGenerationEnded(cid, sn, null)); it.printStackTrace(); addError(it, cid, title = context.getString(R.string.error_title_generation)); Logging.log(TAG, "msgComplete: $it") }
        .onSuccess { val fc = getConversationFlow(cid).value; saveConversation(cid, fc); appScope.launch { addConversationReference(cid); try { generateTitle(cid, fc) } finally { removeConversationReference(cid) } }; appScope.launch { addConversationReference(cid); try { generateSuggestion(cid, fc) } finally { removeConversationReference(cid) } } }
    }

    // ========== 子代理 (kimi-code) ==========
    private suspend fun buildSubagentTools(a: Assistant, st: Settings, wd: String?, d: Int, md: Int, ib: Boolean, cid: Uuid? = null): List<Tool> {
        val ps = mergeSubagentProfiles(a.subagentProfiles, a.disabledBuiltinSubagents); val r = mutableListOf<Tool>()
        if (ib) r += SubagentHost.sandboxToolsForSubagent(buildSubagentBaseTools(a, st, wd))
        if (d + 1 < md && ps.isNotEmpty()) r += createSubagentTools(ps, json, a.localTools.contains(LocalToolOption.AskBtw),
            { pn, tk, _ -> ps.firstOrNull { it.name == pn }?.let { p -> val pm = st.findModelById(a.chatModelId ?: st.chatModelId) ?: error("Model not found"); subagentHost.spawn(p, tk, st, a, pm, { ch, dd -> buildSubagentTools(ch, st, wd, dd, md, true) }, d + 1, md, if (cid != null) { sm -> updateSubagentProgress(cid, null, pn, sm) } else null) } ?: SubagentResult(pn, "", false, "Not found: $pn", d + 1) },
            { q -> val bp = SubagentProfile(name = "btw", systemPrompt = a.systemPrompt, inheritTools = false, maxSteps = 1); val pm = st.findModelById(a.chatModelId ?: st.chatModelId) ?: return@createSubagentTools "(failed)"; subagentHost.spawn(bp, q, st, a, pm, { _, _ -> emptyList() }, d + 1, md).let { if (it.succeeded) it.summary else "(failed: ${it.error})" } })
        if (d == 0) r += createManageSubagentTool(ps, json) { ac, nm, pf -> manageSubagentProfile(a.id, ac, nm, pf) }
        return r
    }
    private suspend fun buildSubagentBaseTools(a: Assistant, st: Settings, wd: String?) = buildList { if (a.enableWebSearch) addAll(createSearchTools(st)); addAll(SubagentHost.sandboxToolsForSubagent(localTools.getTools(a.localTools.filter { it != LocalToolOption.AskUser }))); addAll(createWorkspaceToolsIfReady(a.workspaceId?.toString(), wd)); if (a.enabledSkills.isNotEmpty()) addAll(createSkillTools(a.enabledSkills, skillManager.listSkills(), skillManager)) }
    private suspend fun manageSubagentProfile(aid: Uuid, ac: String, nm: String, pf: SubagentProfile?): String { val cs = settingsStore.settingsFlow.first(); val t = cs.assistants.firstOrNull { it.id == aid } ?: return "Error"; return when (ac) { "list" -> mergeSubagentProfiles(t.subagentProfiles, t.disabledBuiltinSubagents).let { if (it.isEmpty()) "无子代理配置" else it.joinToString("\n") { "- ${it.name}: ${it.description}" } }; "create", "update" -> { val p = pf ?: return "Error"; settingsStore.update { s -> s.copy(assistants = s.assistants.map { if (it.id == aid) it.copy(subagentProfiles = upsertSubagentProfile(it.subagentProfiles, p)) else it }) }; "已保存: ${p.name}" }; "delete" -> { if (nm.isBlank()) return "Error"; val bi = SubagentProfile.BUILTIN.any { it.name == nm }; settingsStore.update { s -> s.copy(assistants = s.assistants.map { if (it.id == aid) it.copy(subagentProfiles = removeSubagentProfile(it.subagentProfiles, nm), disabledBuiltinSubagents = if (bi) it.disabledBuiltinSubagents + nm else it.disabledBuiltinSubagents) else it }) }; "已删除: $nm" }; else -> "Error" } }
    private fun updateSubagentProgress(cid: Uuid, tcid: String?, pn: String, sm: List<UIMessage>) { runCatching { val ts = SubagentHost.buildTranscript(sm, 2000); if (ts.isEmpty()) return@runCatching; val md = buildJsonObject { put("subagent_transcript", json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(SubagentTranscriptStep.serializer()), ts)); put("subagent_profile", JsonPrimitive(pn)); put("subagent_steps", JsonPrimitive(ts.size)); put("subagent_succeeded", JsonPrimitive(false)); put("subagent_streaming", JsonPrimitive(true)) }; updateConversationState(cid) { c -> val ms = c.currentMessages; val li = ms.indexOfLast { it.role == MessageRole.ASSISTANT }; if (li < 0) c else c.updateCurrentMessages(ms.mapIndexed { i, m -> if (i != li) m else m.copy(parts = m.parts.map { p -> if (p is UIMessagePart.Tool && p.toolName == "spawn_subagent" && (!p.isExecuted || isStreamingSubagent(p)) && (tcid == null || p.toolCallId == tcid)) p.copy(output = listOf(UIMessagePart.Text("{\"profile_name\":\"$pn\",\"succeeded\":false,\"streaming\":true}", md))) else p }) }) } }.onFailure { Log.w(TAG, "progress: ${it.message}") } }
    private fun isStreamingSubagent(p: UIMessagePart.Tool) = p.output.filterIsInstance<UIMessagePart.Text>().firstOrNull()?.metadata?.get("subagent_streaming")?.jsonPrimitive?.contentOrNull == "true"

    private suspend fun createWorkspaceToolsIfReady(wid: String?, cwd: String? = null) = if (wid.isNullOrBlank()) emptyList() else workspaceRepository.getById(wid)?.takeIf { it.shellStatus == WorkspaceShellStatus.READY.name }?.let { createWorkspaceTools(wid, workspaceRepository, cwd, shellSessionManager, backgroundShellManager, shellAuditLogger) } ?: emptyList()
    private fun checkInvalidMessages(cid: Uuid) { val c = getConversationFlow(cid).value; var ns = c.messageNodes.mapIndexed { _, n -> val hp = n.currentMessage.getTools().any { !it.isExecuted }; if (hp) { val hr = n.currentMessage.getTools().any { !it.isExecuted && it.approvalState.canResumeToolExecution() }; if (hr) return@mapIndexed n; val ae = n.currentMessage.getTools().all { it.isExecuted } && n.currentMessage.getTools().isNotEmpty(); if (ae) return@mapIndexed n; return@mapIndexed n.copy(messages = n.messages.filter { it.id != n.currentMessage.id }, selectIndex = n.selectIndex - 1) }; n }; ns = ns.map { if (it.messages.isNotEmpty() && it.selectIndex !in it.messages.indices) it.copy(selectIndex = 0) else it }; updateConversation(cid, c.copy(messageNodes = ns.filter { it.messages.isNotEmpty() })) }
    private fun cancelToolByUser(t: UIMessagePart.Tool) = t.copy(output = listOf(UIMessagePart.Text("""{"status":"cancelled"}""")), approvalState = ToolApprovalState.Denied("cancelled"))
    private suspend fun finishInterruptedPendingTools(cid: Uuid) { val c = getConversationFlow(cid).value; val ln = c.messageNodes.lastOrNull() ?: return; val lm = ln.currentMessage; val um = lm.finishPendingTools(::cancelToolByUser); if (um == lm) return; saveConversation(cid, c.copy(messageNodes = c.messageNodes.dropLast(1) + ln.copy(messages = ln.messages.map { if (it.id == lm.id) um else it }))) }

    suspend fun generateTitle(cid: Uuid, c: Conversation, force: Boolean = false) { if (!force && c.title.isNotBlank()) return; runCatching { val s = settingsStore.settingsFlow.first(); val m = s.findModelById(s.titleModelId, fallback = s.fastModelId) ?: return; val p = m.findProvider(s.providers) ?: return; val r = providerManager.getProviderByType(p).generateText(p, listOf(UIMessage.user(s.titlePrompt.applyPlaceholders("locale" to Locale.getDefault().displayName, "content" to c.currentMessages.takeLast(4).joinToString("\n\n") { it.summaryAsText(500) }))), backgroundTextGenerationParams(m)); conversationRepo.getConversationById(c.id)?.let { saveConversation(cid, it.copy(title = r.choices[0].message?.toText()?.trim() ?: "")) } }.onFailure { it.printStackTrace(); addError(error = it, conversationId = cid, title = context.getString(R.string.error_title_generate_title), solution = ChatErrorSolution.CheckTitleModelSettings) } }
    suspend fun generateSuggestion(cid: Uuid, c: Conversation) { runCatching { val s = settingsStore.settingsFlow.first(); if (!s.enableSuggestion) return; val m = s.findModelById(s.suggestionModelId, fallback = s.fastModelId) ?: return; val p = m.findProvider(s.providers) ?: return; sessions[cid]?.let { updateConversation(cid, it.state.value.copy(chatSuggestions = emptyList())) }; val r = providerManager.getProviderByType(p).generateText(p, listOf(UIMessage.user(s.suggestionPrompt.applyPlaceholders("locale" to Locale.getDefault().displayName, "content" to c.currentMessages.takeLast(8).joinToString("\n\n") { it.summaryAsText(500) }))), backgroundTextGenerationParams(m)); saveConversation(cid, (conversationRepo.getConversationById(cid) ?: sessions[cid]?.state?.value ?: c).copy(chatSuggestions = r.choices[0].message?.toText()?.split("\n")?.map { it.trim() }?.filter { it.isNotBlank() }?.take(10) ?: emptyList())) }.onFailure { it.printStackTrace() } }
    suspend fun compressConversation(cid: Uuid, c: Conversation, ap: String, tt: Int, kr: Int = 32) = runCatching { val s = settingsStore.settingsFlow.first(); val m = s.findModelById(s.compressModelId) ?: s.getCurrentChatModel() ?: throw IllegalStateException("No model"); val p = m.findProvider(s.providers) ?: throw IllegalStateException("No provider"); val ph = providerManager.getProviderByType(p); val am = c.currentMessages; val (tc, tk) = if (kr > 0 && am.size > kr) am.dropLast(kr) to am.takeLast(kr) else if (kr > 0) throw IllegalStateException(context.getString(R.string.chat_page_compress_not_enough_messages)) else am to emptyList(); fun sp(ms: List<UIMessage>): List<List<UIMessage>> = if (ms.size <= 256) listOf(ms) else { val mid = ms.size / 2; sp(ms.subList(0, mid)) + sp(ms.subList(mid, ms.size)) }; suspend fun cm(ms: List<UIMessage>) = ph.generateText(p, listOf(UIMessage.user(s.compressPrompt.applyPlaceholders("content" to ms.joinToString("\n\n") { it.summaryAsText(2000) }, "target_tokens" to tt.toString(), "additional_context" to if (ap.isNotBlank()) "User: $ap" else "", "locale" to Locale.getDefault().displayName))), backgroundTextGenerationParams(m)).choices[0].message?.toText()?.trim() ?: throw IllegalStateException("compress failed"); saveConversation(cid, c.copy(messageNodes = buildList { coroutineScope { sp(tc).map { async { cm(it) } }.awaitAll() }.forEach { add(UIMessage.user(it).toMessageNode()) }; addAll(tk.map { it.toMessageNode() }) }, chatSuggestions = emptyList())) }

    private fun updateConversation(cid: Uuid, c: Conversation) { if (c.id != cid) return; val s = getOrCreateSession(cid); checkFilesDelete(c, s.state.value); s.state.value = c }
    fun updateConversationState(cid: Uuid, u: (Conversation) -> Conversation) { updateConversation(cid, u(getConversationFlow(cid).value)) }
    suspend fun moveConversationToFolder(cid: Uuid, fid: Uuid?) { if (sessions.containsKey(cid)) updateConversationState(cid) { it.copy(folderId = fid) }; conversationRepo.updateConversationFolderId(cid, fid) }
    fun hasGeneratingConversationInFolder(fid: Uuid) = sessions.values.any { it.isGenerating && it.state.value.folderId == fid }
    suspend fun deleteFolder(fid: Uuid) { sessions.values.filter { it.state.value.folderId == fid }.forEach { updateConversationState(it.id) { c -> c.copy(folderId = null) } }; folderRepository.deleteFolder(fid) }
    private fun checkFilesDelete(nc: Conversation, oc: Conversation) { val dl = oc.files.filter { nc.files.none { f -> it == f } }; if (dl.isNotEmpty()) filesManager.deleteChatFiles(dl) }
    suspend fun saveConversation(cid: Uuid, c: Conversation) { if (!conversationRepo.existsConversationById(c.id) && c.title.isBlank() && c.messageNodes.isEmpty()) return; val nc = c.copy(); updateConversation(cid, nc); if (!conversationRepo.existsConversationById(nc.id)) conversationRepo.insertConversation(nc) else conversationRepo.updateConversation(nc) }

    fun translateMessage(cid: Uuid, msg: UIMessage, tl: Locale) { appScope.launch(Dispatchers.IO) { try { val s = settingsStore.settingsFlow.first(); val txt = msg.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n\n") { it.text }.trim(); if (txt.isBlank()) return@launch; updateTranslationField(cid, msg.id, context.getString(R.string.translating)); generationHandler.translateText(s, txt, tl) { updateTranslationField(cid, msg.id, it) }.collect {}; saveConversation(cid, getConversationFlow(cid).value) } catch (e: Exception) { clearTranslationField(cid, msg.id); addError(e, cid, title = context.getString(R.string.error_title_translate_message)) } } }
    private fun updateTranslationField(cid: Uuid, mid: Uuid, tt: String) { updateConversation(cid, getConversationFlow(cid).value.copy(messageNodes = getConversationFlow(cid).value.messageNodes.map { n -> if (n.messages.any { it.id == mid }) n.copy(messages = n.messages.map { if (it.id == mid) it.copy(translation = tt) else it }) else n })) }

    suspend fun editMessage(cid: Uuid, mid: Uuid, parts: List<UIMessagePart>) { if (parts.isEmptyInputMessage()) return; val c = getConversationFlow(cid).value; val a = (settingsStore.settingsFlow.first().getAssistantById(c.assistantId) ?: settingsStore.settingsFlow.first().getCurrentAssistant()); var e = false; val ns = c.messageNodes.map { n -> if (!n.messages.any { it.id == mid }) n else { e = true; n.copy(messages = n.messages + UIMessage(role = n.role, parts = preprocessUserInputParts(parts, a)), selectIndex = n.messages.size) } }; if (!e) return; saveConversation(cid, c.copy(messageNodes = ns)) }
    suspend fun forkConversationAtMessage(cid: Uuid, mid: Uuid): Conversation { val c = getConversationFlow(cid).value; val idx = c.messageNodes.indexOfFirst { it.messages.any { m -> m.id == mid } }; if (idx == -1) throw NotFoundException("Not found"); val fc = Conversation(Uuid.random(), c.assistantId, c.messageNodes.subList(0, idx + 1).map { n -> n.copy(id = Uuid.random(), messages = n.messages.map { it.copy(parts = it.parts.map { p -> p.copyWithForkedFileUrl() }) }) }, c.customSystemPrompt, c.modeInjectionIds, c.lorebookIds); saveConversation(fc.id, fc); return fc }
    suspend fun selectMessageNode(cid: Uuid, nid: Uuid, si: Int) { val c = getConversationFlow(cid).value; val n = c.messageNodes.firstOrNull { it.id == nid } ?: throw NotFoundException("Not found"); if (si !in n.messages.indices) throw BadRequestException("Bad index"); if (n.selectIndex == si) return; saveConversation(cid, c.copy(messageNodes = c.messageNodes.map { if (it.id == nid) it.copy(selectIndex = si) else it })) }
    suspend fun deleteMessage(cid: Uuid, mid: Uuid, fm: Boolean = true) { val c = getConversationFlow(cid).value; val u = buildConversationAfterMessageDelete(c, mid) ?: if (fm) throw NotFoundException("Not found") else return; saveConversation(cid, u) }
    suspend fun deleteMessage(cid: Uuid, m: UIMessage) { deleteMessage(cid, m.id, false) }
    private fun buildConversationAfterMessageDelete(c: Conversation, mid: Uuid) = c.messageNodes.indexOfFirst { it.messages.any { m -> m.id == mid } }.takeIf { it != -1 }?.let { idx -> c.copy(messageNodes = c.messageNodes.mapIndexedNotNull { i, n -> if (i != idx) n else n.messages.filterNot { it.id == mid }.takeIf { it.isNotEmpty() }?.let { n.copy(messages = it, selectIndex = n.selectIndex.coerceAtMost(it.lastIndex)) } }) }
    private fun UIMessagePart.copyWithForkedFileUrl() = when (this) { is UIMessagePart.Image -> copy(url = clf(url)); is UIMessagePart.Document -> copy(url = clf(url)); is UIMessagePart.Video -> copy(url = clf(url)); is UIMessagePart.Audio -> copy(url = clf(url)); else -> this }; private fun clf(url: String) = if (!url.startsWith("file:")) url else filesManager.createChatFilesByContents(listOf(url.toUri())).firstOrNull()?.toString() ?: url
    fun clearTranslationField(cid: Uuid, mid: Uuid) { updateConversation(cid, getConversationFlow(cid).value.copy(messageNodes = getConversationFlow(cid).value.messageNodes.map { n -> if (n.messages.any { it.id == mid }) n.copy(messages = n.messages.map { if (it.id == mid) it.copy(translation = null) else it }) else n })) }
    suspend fun stopGeneration(cid: Uuid) { sessions[cid]?.getJob()?.let { it.cancel(); runCatching { it.join() }; finishInterruptedPendingTools(cid) } }
}
