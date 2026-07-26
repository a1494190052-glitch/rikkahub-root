package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.content.Intent
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

fun createShareTool(context: Context): Tool = Tool(
    name = "share", description = "Share text or URL via the system share sheet.", needsApproval = true,
    parameters = { InputSchema.Obj(properties = buildJsonObject { putJsonObject("text") { put("type", "string"); put("description", "Text to share") }; putJsonObject("url") { put("type", "string"); put("description", "URL to share") }; putJsonObject("subject") { put("type", "string"); put("description", "Subject for email sharing") } }) },
    execute = { args ->
        val params = args.jsonObject; val text = params["text"]?.jsonPrimitive?.contentOrNull; val url = params["url"]?.jsonPrimitive?.contentOrNull
        if (text.isNullOrBlank() && url.isNullOrBlank()) return@Tool listOf(UIMessagePart.Text(buildJsonObject { put("success", false); put("error", "Need 'text' or 'url'") }.toString()))
        val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, listOfNotNull(text, url).joinToString("\n")); params["subject"]?.jsonPrimitive?.contentOrNull?.let { putExtra(Intent.EXTRA_SUBJECT, it) }; addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        context.startActivity(Intent.createChooser(intent, "Share").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        listOf(UIMessagePart.Text(buildJsonObject { put("success", true) }.toString()))
    }
)
