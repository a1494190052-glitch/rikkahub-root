package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/**
 * ask_btw — 轻量级旁路提问工具
 *
 * 让 AI 向一个无工具的独立 agent 实例提一个快速问题，
 * 适合获取第二意见 / 快速查证，不污染主对话上下文。
 * 实际调用逻辑由 ChatService 在运行时拦截处理。
 */
internal fun buildAskBtwTool(): Tool = Tool(
    name = "ask_btw",
    description = """
        Ask a lightweight, tool-less side question to a fresh agent instance.
        Use for quick second opinions or fact checks without polluting the main conversation context.
        The sub-agent has NO tools — only its own knowledge.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("question", buildJsonObject {
                    put("type", "string")
                    put("description", "The self-contained side question to ask")
                })
            },
            required = listOf("question")
        )
    },
    execute = {
        // 实际逻辑由 ChatService / SubAgentExecutor 在运行时拦截；
        // 此处仅为占位，正常不会执行到这里。
        listOf(UIMessagePart.Text("[ask_btw] handled by runtime"))
    }
)
