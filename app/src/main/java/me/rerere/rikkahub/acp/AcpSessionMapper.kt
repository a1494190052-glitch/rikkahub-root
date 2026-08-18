@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package me.rerere.rikkahub.acp

import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.model.ToolCallStatus
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * ACP tool call → RikkaHub 时间线的"功能映射"富化层。
 *
 * 这是纯函数层（只依赖 ACP SDK + kotlinx.serialization），与 OmniBot 的
 * AcpSessionUpdateMapper 一致。它解决的是同一个问题：ACP 的 tool_call 只带
 * 人类可读 title + 原始参数 rawInput + 原始输出 rawOutput，而 UI 需要
 * 工具名 / 参数 / 结果 / 错误原因 / 终端输出。这里把 ACP 的字段补齐。
 */

/**
 * 工具名。ACP 没有 name 字段——只有 [title] + rawInput。优先取 rawInput JSON
 * 里的 name / tool.name，否则把 title slug 化（"Read file" -> "read_file"），
 * 让下游能据此推断工具类型（read/edit/web/bash...）。两者都取不到则返回 null。
 */
internal fun resolveToolName(title: String?, rawInput: JsonElement?): String? {
    rawInput?.asJsonObjectOrNull()?.let { obj ->
        obj.stringField("name")?.takeIf { it.isNotBlank() }?.let { return it }
        (obj["tool"] as? JsonObject)
            ?.stringField("name")?.takeIf { it.isNotBlank() }?.let { return it }
    }
    val slug = title?.trim()?.lowercase()
        ?.replace(Regex("[^a-z0-9]+"), "_")?.trim('_')
    return slug?.takeIf { it.isNotEmpty() }
}

/** 工具执行结果文本（完成时）。 */
internal fun SessionUpdate.ToolCallUpdate.resultText(): String? {
    content?.let { blocks ->
        val text = blocks.joinToString("\n") { block ->
            (block as? ToolCallContent.Content)?.content?.textPayload().orEmpty()
        }.trim()
        if (text.isNotEmpty()) return text
    }
    rawOutput?.asJsonObjectOrNull()?.let { obj ->
        obj.stringField("result")?.takeIf { it.isNotBlank() }?.let { return it }
        obj.stringField("stdout")?.takeIf { it.isNotBlank() }?.let { return it }
    }
    return null
}

/**
 * 失败原因。ACP 0.26 的 tool_call_update 没有 error 字段，错误藏在 rawOutput
 * 的 error/message/stderr 或 content 文本里。这里把它们挖出来，否则 UI 只能
 * 显示一个干巴巴的 "failed"。
 */
internal fun SessionUpdate.ToolCallUpdate.errorMessage(): String? {
    if (status != ToolCallStatus.FAILED) return null
    rawOutput?.asJsonObjectOrNull()?.let { obj ->
        obj.stringField("error")?.takeIf { it.isNotBlank() }?.let { return it }
        obj.stringField("message")?.takeIf { it.isNotBlank() }?.let { return it }
        obj.stringField("stderr")?.takeIf { it.isNotBlank() }?.let { return it }
    }
    return content?.firstNotNullOfOrNull { block ->
        (block as? ToolCallContent.Content)
            ?.content?.textPayload()?.takeIf { it.isNotBlank() }
    }
}

/** 命令类工具的标准输出（stdout）。 */
internal fun SessionUpdate.ToolCallUpdate.terminalOutput(): String? {
    rawOutput?.asJsonObjectOrNull()?.let { obj ->
        obj.stringField("stdout")?.takeIf { it.isNotBlank() }?.let { return it }
        (obj["result"] as? JsonObject)
            ?.stringField("stdout")?.takeIf { it.isNotBlank() }?.let { return it }
        (obj["result"] as? JsonObject)
            ?.stringField("output")?.takeIf { it.isNotBlank() }?.let { return it }
    }
    return null
}

/** 待审批工具的最有信息量参数摘要（审批卡片用）。 */
internal fun toolCallArgsSummary(rawInput: JsonElement?): String? {
    val obj = rawInput?.asJsonObjectOrNull() ?: return null
    obj.stringField("command")?.takeIf { it.isNotBlank() }?.let { return it }
    obj.stringField("cmd")?.takeIf { it.isNotBlank() }?.let { return it }
    val keys = listOf("path", "file", "query", "url", "uri", "pattern", "glob", "name")
    return keys.firstNotNullOfOrNull { key ->
        obj.stringField(key)?.takeIf { it.isNotBlank() }?.let { "$key: $it" }
    }
}

internal fun JsonElement?.asJsonObjectOrNull(): JsonObject? = this as? JsonObject

internal fun JsonObject.stringField(name: String): String? =
    (this[name] as? JsonPrimitive)?.content

private fun com.agentclientprotocol.model.ContentBlock.textPayload(): String = when (this) {
    is com.agentclientprotocol.model.ContentBlock.Text -> text
    is com.agentclientprotocol.model.ContentBlock.ResourceLink -> title ?: name
    is com.agentclientprotocol.model.ContentBlock.Image -> uri ?: ""
    is com.agentclientprotocol.model.ContentBlock.Audio -> ""
    is com.agentclientprotocol.model.ContentBlock.Resource -> resource.toString()
}
