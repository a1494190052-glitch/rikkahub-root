package me.rerere.rikkahub.service

import android.content.Context
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.createConversationTools
import me.rerere.rikkahub.data.ai.tools.createSearchTools
import me.rerere.rikkahub.data.ai.tools.createSkillTools
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.ai.tools.local.buildCreateScheduleTool
import me.rerere.rikkahub.data.ai.tools.local.buildDeleteScheduleTool
import me.rerere.rikkahub.data.ai.tools.local.buildListSchedulesTool
import me.rerere.rikkahub.data.ai.tools.local.buildToggleScheduleTool
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.scheduler.ScheduledTaskRepository
import kotlin.uuid.Uuid

/**
 * 工具装配器：从 ChatService.handleMessageComplete 抽出的"为一次生成请求构建工具列表"职责。
 * 把原本内联的 8 类工具装配（搜索/本地/调度/会话引用/工作区/技能/MCP/子代理）集中于此，
 * 让 handleMessageComplete 退化为纯生成编排。
 *
 * MCP server 名非法时上报错误并返回 null，调用方据此中断本次生成。
 */
class ToolAssembler(
    private val context: Context,
    private val localTools: LocalTools,
    private val scheduledTaskRepository: ScheduledTaskRepository,
    private val conversationRepo: ConversationRepository,
    private val skillManager: SkillManager,
    private val mcpManager: McpManager,
    private val subagentOrchestrator: SubagentOrchestrator,
    /** 构建工作区工具（包装 ChatService.createWorkspaceToolsIfReady） */
    private val workspaceToolsFactory: suspend (String?, String?) -> List<Tool>,
    /** 上报错误（包装 ChatService.addError） */
    private val reportError: (Throwable, Uuid?, String?, ChatErrorSolution?) -> Unit,
) {
    /**
     * 装配一次生成请求所需的工具列表。
     * @return 工具列表；若 MCP server 名非法则上报错误并返回 null（调用方应中断生成）。
     */
    suspend fun assembleTools(
        assistant: Assistant,
        settings: Settings,
        conversation: Conversation,
        conversationId: Uuid,
    ): List<Tool>? {
        // MCP server 名校验：非法名直接中断（与原内联逻辑等价，提到装配前更清晰）
        val mcpTools = mcpManager.getAllAvailableTools()
        val invalidNames = mcpTools.map { it.second }.distinct().filter { name -> name.isEmpty() || !name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } }
        if (invalidNames.isNotEmpty()) {
            reportError(IllegalStateException(context.getString(R.string.error_mcp_invalid_server_name, invalidNames.joinToString(", "))), conversationId, null, null)
            return null
        }

        val subagentTools = if (assistant.enableSubagents) {
            subagentOrchestrator.buildSubagentTools(assistant, settings, conversation.workspaceCwd, depth = 0, assistant.subagentMaxDepth, includeBase = false, conversationId = conversationId)
        } else emptyList()

        val tools = buildList {
            if (assistant.enableWebSearch) addAll(createSearchTools(settings))
            addAll(localTools.getTools(assistant.localTools))
            if (assistant.localTools.contains(LocalToolOption.Scheduler)) {
                add(buildCreateScheduleTool(scheduledTaskRepository, assistant))
                add(buildListSchedulesTool(scheduledTaskRepository, assistant))
                add(buildDeleteScheduleTool(scheduledTaskRepository, assistant))
                add(buildToggleScheduleTool(scheduledTaskRepository, assistant))
            }
            if (assistant.enableRecentChatsReference) addAll(createConversationTools(conversationRepo, assistant.id))
            addAll(workspaceToolsFactory(assistant.workspaceId?.toString(), conversation.workspaceCwd))
            if (assistant.enabledSkills.isNotEmpty()) {
                addAll(createSkillTools(enabledSkills = assistant.enabledSkills, allSkills = skillManager.listSkills(), skillManager = skillManager))
            }
            mcpTools.forEach { (serverId, serverName, tool) ->
                add(Tool(name = "mcp__${serverName}__${tool.name}", description = tool.description ?: "", parameters = { tool.inputSchema }, needsApproval = { tool.needsApproval }, execute = { mcpManager.callTool(serverId, tool.name, it.jsonObject) }))
            }
            // kimi-code 子代理委派工具
            addAll(subagentTools)
        }

        return tools.distinctBy { it.name }
    }
}
