package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.ToolArgs
import me.rerere.rikkahub.data.ai.tools.params
import me.rerere.rikkahub.utils.readClipboardText
import me.rerere.rikkahub.utils.writeClipboardText

private val CLIPBOARD_PARAMS = params {
    string("action", "Operation to perform: read or write", required = true, enum = listOf("read", "write"))
    string("text", "Text to write to the clipboard (required for write)")
}

internal fun buildClipboardTool(context: Context): Tool = Tool(
    name = "clipboard_tool",
    description = """
        Read or write plain text from the device clipboard.
        Use action: read or write. For write, provide text.
        Do NOT write to the clipboard unless the user has explicitly requested it.
    """.trimIndent().replace("\n", " "),
    parameters = { CLIPBOARD_PARAMS },
    execute = { input ->
        val a = ToolArgs(input)
        when (a.str("action")) {
            "read" -> {
                val payload = buildJsonObject { put("text", context.readClipboardText()) }
                listOf(UIMessagePart.Text(payload.toString()))
            }

            "write" -> {
                val text = a.str("text")
                context.writeClipboardText(text)
                val payload = buildJsonObject {
                    put("success", true)
                    put("text", text)
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }

            else -> error("unknown action: ${a.str("action")}, must be one of [read, write]")
        }
    }
)
