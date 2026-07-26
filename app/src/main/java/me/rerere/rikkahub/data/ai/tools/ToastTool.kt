package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

fun createToastTool(context: Context): Tool = Tool(
    name = "show_toast", description = "Show a brief Toast notification on screen. Use sparingly.",
    parameters = { InputSchema.Obj(properties = buildJsonObject { putJsonObject("text") { put("type", "string"); put("description", "Text to display") }; putJsonObject("long") { put("type", "boolean"); put("description", "Long duration (3.5s) vs short (2s)") } }, required = listOf("text")) },
    execute = { args ->
        val text = args.jsonObject["text"]?.jsonPrimitive?.contentOrNull; val long = args.jsonObject["long"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        if (text.isNullOrBlank()) return@Tool listOf(UIMessagePart.Text(buildJsonObject { put("success", false); put("error", "Missing 'text'") }.toString()))
        Handler(Looper.getMainLooper()).post { Toast.makeText(context, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show() }
        listOf(UIMessagePart.Text(buildJsonObject { put("success", true); put("text", text) }.toString()))
    }
)
