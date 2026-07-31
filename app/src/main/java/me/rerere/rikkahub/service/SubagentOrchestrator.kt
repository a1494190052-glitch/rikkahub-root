package me.rerere.rikkahub.service

import android.util.Log
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonPrimitiveOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.subagent.LruSessionCache
import me.rerere.rikkahub.data.ai.subagent.SubagentHost
import me.rerere.rikkahub.data.ai.subagent.SubagentProfile
import me.rerere.rikkahub.data.ai.subagent.SubagentResult
import me.rerere.rikkahub.data.ai.subagent.SubagentTranscriptStep
import me.rerere.rikkahub.data.ai.subagent.createManageSubagentTool
import me.rerere.rikkahub.data.ai.subagent.createSubagentTools
import me.rerere.rikkahub.data.ai.subagent.mergeSubagentProfiles
import me.rerere.rikkahub.data.ai.subagent.removeSubagentProfile
import me.rerere.rikkahub.data.ai.subagent.upsertSubagentProfile
import me.rerere.rikkahub.data.ai.tools.createSearchTools
import me.rerere.rikkahub.data.ai.tools.createSkillTools
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import kotlin.uuid.Uuid

/**
 * 子代理编排器：从 ChatService 抽出的子代理系统职责（C 簇）。
 * 负责子代理工具构建、profile 管理、spawn/resume 编排、流式进度回写。
 *
 * 本类零 sessions 访问，唯一对外耦合是 [updateConversationState] 回调（回写会话状态）
 * 与 [workspaceToolsFactory]（构建工作区工具，原 ChatService.createWorkspaceToolsIfReady）。
 * ChatService 的 buildSubagentTools 调用点改为委托到这里。
 */
class SubagentOrchestrator(
    private val subagentHost: SubagentHost,
    private val json: Json,
    private val settingsStore: SettingsStore,
    private val workspaceRepository: WorkspaceRepository,
    private val localTools: LocalTools,
    private val skillManager: SkillManager,
    private val mcpManager: McpManager,
    /** 构建工作区工具（包装 ChatService.createWorkspaceToolsIfReady） */
    private val workspaceToolsFactory: suspend (workspaceId: String?, cwd: String?) -> List<Tool>,
    /** 回写会话状态（包装 ChatService.updateConversationState） */
    private val updateConversationState: (Uuid, (Conversation) -> Conversation) -> Unit,
) {
    private val subagentSessions = LruSessionCache<String, SubagentHost.SubagentSessionData>(20)

    fun storeSubagentSession(id: String, data: SubagentHost.SubagentSessionData) {
        subagentSessions.put(id, data)
    }

    suspend fun buildSubagentTools(
        assistant: Assistant, settings: Settings, workspaceCwd: String?,
        depth: Int, maxDepth: Int, includeBase: Boolean,
        conversationId: Uuid? = null,
        mcpServerIds: Set<Uuid>? = null,
        allowHostShellWrite: Boolean = false,
    ): List<Tool> {
        val profiles = mergeSubagentProfiles(assistant.subagentProfiles, assistant.disabledBuiltinSubagents)
        val result = mutableListOf<Tool>()

        if (includeBase) {
            result += SubagentHost.sandboxToolsForSubagent(buildSubagentBaseTools(assistant, settings, workspaceCwd, mcpServerIds, allowHostShellWrite), allowHostShellWrite, workspaceRootMode = workspaceRepository.isRootMode())
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
                            buildChildTools = { child, d -> buildSubagentTools(child, settings, workspaceCwd, d, maxDepth, includeBase = true, conversationId = conversationId, mcpServerIds = profile.mcpServerIds, allowHostShellWrite = profile.allowHostShellWrite) },
                            depth = depth + 1, maxDepth = maxDepth,
                            onProgress = if (conversationId != null) { subMessages -> updateSubagentProgress(conversationId, null, profileName, depth + 1, subMessages) } else null,
                            onSessionComplete = { sessionId, session -> storeSubagentSession(sessionId, session) },
                        )
                    }
                },
                resume = { sessionId, followUp ->
                    val session = subagentSessions.get(sessionId)
                    if (session == null) SubagentResult(profileName = "", summary = "", succeeded = false, error = "Subagent session not found (expired or invalid): $sessionId")
                    else {
                        val (r, newMessages) = subagentHost.resume(
                            session = session, followUp = followUp, settings = settings,
                            buildChildTools = { child, d -> buildSubagentTools(child, settings, workspaceCwd, d, maxDepth, includeBase = true, conversationId = conversationId, mcpServerIds = session.profile.mcpServerIds, allowHostShellWrite = session.profile.allowHostShellWrite) },
                            onProgress = if (conversationId != null) { subMessages -> updateSubagentProgress(conversationId, null, session.profile.name, depth + 1, subMessages) } else null,
                        )
                        if (r.succeeded) storeSubagentSession(sessionId, session.copy(messages = newMessages))
                        r
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
        allowHostShellWrite: Boolean = false,
    ): List<Tool> = buildList {
        if (assistant.enableWebSearch) addAll(createSearchTools(settings))
        addAll(SubagentHost.sandboxToolsForSubagent(localTools.getTools(assistant.localTools.filter { it != LocalToolOption.AskUser }), allowHostShellWrite))
        addAll(workspaceToolsFactory(assistant.workspaceId?.toString(), workspaceCwd))
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

    suspend fun manageSubagentProfile(assistantId: Uuid, action: String, name: String, profile: SubagentProfile?): String {
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

    fun updateSubagentProgress(conversationId: Uuid, toolCallId: String?, profileName: String, depth: Int, subMessages: List<UIMessage>) {
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
                put("subagent_depth", JsonPrimitive(depth))
            }
            val partialOutput = UIMessagePart.Text(text = "{\"profile_name\":\"$profileName\",\"succeeded\":false,\"streaming\":true,\"depth\":$depth}", metadata = transcriptMetadata)
            updateConversationState(conversationId) { conversation ->
                val messages = conversation.currentMessages
                val lastAssistantIndex = messages.indexOfLast { it.role == MessageRole.ASSISTANT }
                if (lastAssistantIndex < 0) return@updateConversationState conversation
                val updatedMessages = messages.mapIndexed { index, message ->
                    if (index != lastAssistantIndex) return@mapIndexed message
                    // 并行 spawn 进度路由: 优先精确 toolCallId; 否则按 streaming metadata 里的 (profile, depth) 匹配;
                    // 还未写入 metadata 的 spawn 部件只认领第一个, 避免多个并行子代理互相覆盖进度
                    var claimed = false
                    val matchesTool: (UIMessagePart.Tool) -> Boolean = matches@{ part ->
                        if (claimed || part.toolName != "spawn_subagent") return@matches false
                        if (toolCallId != null) return@matches part.toolCallId == toolCallId
                        val streamingProfile = streamingProfileOf(part)
                        val partDepth = part.output.filterIsInstance<UIMessagePart.Text>().firstOrNull()
                            ?.metadata?.get("subagent_depth")?.jsonPrimitiveOrNull?.intOrNull
                        val hit = when {
                            isStreamingSubagent(part) -> {
                                // 嵌套层级精确匹配优先: 同 profile 不同 depth 不互相覆盖
                                if (partDepth != null && partDepth != depth) false
                                else streamingProfile == profileName
                            }
                            !part.isExecuted -> streamingProfile == null || streamingProfile == profileName
                            else -> false
                        }
                        if (hit) claimed = true
                        hit
                    }
                    if (!message.parts.any { it is UIMessagePart.Tool && matchesTool(it) }) return@mapIndexed message
                    claimed = false
                    message.copy(parts = message.parts.map { part ->
                        if (part is UIMessagePart.Tool && matchesTool(part)) {
                            mergeNestedProgress(part, profileName, depth, transcript, listSerializer, partialOutput)
                        } else part
                    })
                }
                conversation.updateCurrentMessages(updatedMessages)
            }
        }.onFailure { Log.w(TAG, "updateSubagentProgress failed: ${it.message}") }
    }

    /**
     * 嵌套子代理进度合并：
     * - 顶层 (depth==1)：整体替换为自身 transcript（原逻辑）
     * - 嵌套 (depth>1) 且目标 part 属于外层其他 profile：不覆盖外层 transcript，
     *   在末尾追加/更新一行状态标记 "🧩 子代理 xxx 运行中…"，外层时间线可见嵌套活动
     */
    private fun mergeNestedProgress(
        part: UIMessagePart.Tool,
        profileName: String,
        depth: Int,
        innerTranscript: List<SubagentTranscriptStep>,
        listSerializer: kotlinx.serialization.builtins.ListSerializer<SubagentTranscriptStep>,
        partialOutput: UIMessagePart.Text,
    ): UIMessagePart.Tool {
        val existingMeta = part.output.filterIsInstance<UIMessagePart.Text>().firstOrNull()?.metadata
        val existingProfile = existingMeta?.get("subagent_profile")?.jsonPrimitiveOrNull?.contentOrNull
        val partDepth = existingMeta?.get("subagent_depth")?.jsonPrimitiveOrNull?.intOrNull
        // 嵌套到外层：外层已有 transcript 且 profile/深度不同 → 追加状态标记而非覆盖
        if (depth > 1 && existingProfile != null && existingProfile != profileName) {
            val existingTranscript = existingMeta?.get("subagent_transcript")?.let { el ->
                runCatching {
                    json.decodeFromJsonElement(listSerializer, el)
                }.getOrNull()
            }.orEmpty()
            val marker = "🧩 子代理 $profileName (深度$depth) 运行中…"
            val mergedTranscript = if (existingTranscript.lastOrNull() is SubagentTranscriptStep.Text &&
                (existingTranscript.last() as SubagentTranscriptStep.Text).text.startsWith("🧩 子代理 $profileName")
            ) {
                existingTranscript.dropLast(1) + SubagentTranscriptStep.Text(marker)
            } else {
                existingTranscript + SubagentTranscriptStep.Text(marker)
            }
            return part.copy(output = listOf(partialOutput.copy(metadata = buildJsonObject {
                put("subagent_transcript", json.encodeToJsonElement(listSerializer, mergedTranscript))
                put("subagent_profile", JsonPrimitive(existingProfile))
                put("subagent_steps", JsonPrimitive(mergedTranscript.size))
                put("subagent_succeeded", JsonPrimitive(false))
                put("subagent_streaming", JsonPrimitive(true))
                put("subagent_depth", JsonPrimitive(partDepth ?: 1))
            })))
        }
        // 顶层或自己的进度：整体替换
        return part.copy(output = listOf(partialOutput))
    }

    private fun streamingProfileOf(part: UIMessagePart.Tool): String? =
        part.output.filterIsInstance<UIMessagePart.Text>().firstOrNull()
            ?.metadata?.get("subagent_profile")?.jsonPrimitiveOrNull?.contentOrNull

    private fun isStreamingSubagent(part: UIMessagePart.Tool): Boolean {
        val textPart = part.output.filterIsInstance<UIMessagePart.Text>().firstOrNull()
        return textPart?.metadata?.get("subagent_streaming")?.jsonPrimitiveOrNull?.contentOrNull == "true"
    }

    private companion object {
        private const val TAG = "SubagentOrchestrator"
    }
}
