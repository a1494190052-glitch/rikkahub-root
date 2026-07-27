package me.rerere.rikkahub.data.ai.tools.local

import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolParameter

/**
 * ask_btw — 轻量级旁路提问工具
 *
 * 让 AI 向一个无工具的独立 agent 实例提一个快速问题，
 * 适合获取第二意见 / 快速查证，不污染主对话上下文。
 */
fun buildAskBtwTool(): Tool = Tool(
    name = "ask_btw",
    description = """Ask a lightweight, tool-less side question to a fresh agent instance. Use for quick second opinions or fact checks without polluting the main conversation context. The sub-agent has NO tools — only its own knowledge.""",
    parameters = listOf(
        ToolParameter(
            name = "question",
            type = "string",
            description = "The self-contained side question to ask",
            required = true,
        ),
    ),
) { args ->
    val question = args["question"]?.toString() ?: return@Tool "Error: missing 'question' parameter"
    // 实际调用由 ChatService / SubAgentExecutor 在运行时注入；
    // 此处返回占位提示，真正的逻辑在 ChatService.handleToolCall 中拦截处理。
    "[ask_btw] question=$question — handled by runtime"
}
