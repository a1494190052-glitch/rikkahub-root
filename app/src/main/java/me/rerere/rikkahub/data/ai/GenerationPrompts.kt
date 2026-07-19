package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.utils.JsonInstantPretty

/**
 * 宿主环境与自身能力简报: 让 AI 知道自己活在什么环境、App 具备什么功能、
 * 当前哪些工具可用、审批开关状态。动态生成, 跟随设置实时变化。
 */
internal fun buildHostCapabilityPrompt(assistant: Assistant, settings: Settings): String {
    if (assistant.localTools.isEmpty()) return ""
    return buildString {
        appendLine()
        appendLine("**Host Environment & Your Capabilities**")
        appendLine("You are running INSIDE the RikkaHub app on the user's rooted Android phone — not in a cloud sandbox. Key facts and rules:")
        appendLine("- Workspace = a full Ubuntu 24.04 container (proot) on the phone with apt/bash. You can install runtimes (nodejs, python3, …) and run background services bound to 127.0.0.1. ALWAYS prefer running servers/services in the workspace over a remote VPS or asking the user to install apps.")
        appendLine("- MCP: this app is an MCP CLIENT that connects to servers via SSE / Streamable-HTTP URL only (no stdio). To install an MCP server: run it in the workspace on 127.0.0.1, then register the URL with the mcp_manager tool. Never ask the user to edit app settings manually when a tool can do it.")
        appendLine("- Skills are plain files under the app's skills directory; installing a skill = writing its files there.")
        append("- Enabled local tool groups: ")
        append(assistant.localTools.joinToString { it.javaClass.simpleName })
        append(". Plus always-on tools: mcp_manager, memory_tool and workspace file/shell tools (when a workspace is bound).")
        appendLine()
        val overrides = settings.toolApprovalOverrides
        val notes = mutableListOf<String>()
        if (assistant.localTools.contains(LocalToolOption.RootShell)) {
            notes += if (overrides["root_shell"] == false) {
                "root_shell runs WITHOUT asking (approval disabled by user)"
            } else {
                "root_shell asks for confirmation on write commands"
            }
        }
        if (overrides["pty_exec"] == false) notes += "pty_exec runs WITHOUT asking"
        if (overrides["pty_session"] == false) notes += "pty_session runs WITHOUT asking"
        if (notes.isNotEmpty()) {
            append("- Approval state: ").append(notes.joinToString("; ")).append(".")
            appendLine()
        }
        val mcpCount = settings.mcpServers.count { it.commonOptions.enable }
        if (mcpCount > 0) {
            append("- MCP servers configured & enabled: $mcpCount (manage with mcp_manager).")
            appendLine()
        }
    }
}

internal fun buildMemoryPrompt(memories: List<AssistantMemory>) =
    buildString {
        appendLine()
        append("**Memories**")
        appendLine()
        append("These are memories stored via the memory_tool that you can reference in future conversations.")
        appendLine()
        val json = buildJsonArray {
            memories.forEach { memory ->
                add(buildJsonObject {
                    put("id", memory.id)
                    put("content", memory.content)
                })
            }
        }
        append(JsonInstantPretty.encodeToString(json))
        appendLine()
    }
