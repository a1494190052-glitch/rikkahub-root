package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.media.MediaScannerConnection
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

fun createMediaScannerTool(context: Context): Tool = Tool(
    name = "scan_media", description = "Notify the media scanner to scan files so they appear in gallery apps.", needsApproval = { true },
    parameters = { InputSchema.Obj(properties = buildJsonObject { putJsonObject("paths") { put("type", "array"); put("description", "Array of absolute file paths to scan"); putJsonObject("items") { put("type", "string") } } }, required = listOf("paths")) },
    execute = { args ->
        val arr = args.jsonObject["paths"] as? JsonArray ?: return@Tool listOf(UIMessagePart.Text(buildJsonObject { put("success", false); put("error", "Missing 'paths'") }.toString()))
        val paths = arr.mapNotNull { it.jsonPrimitive.contentOrNull }; if (paths.isEmpty()) return@Tool listOf(UIMessagePart.Text(buildJsonObject { put("success", false); put("error", "No valid paths") }.toString()))
        MediaScannerConnection.scanFile(context, paths.toTypedArray(), null, null)
        listOf(UIMessagePart.Text(buildJsonObject { put("success", true); put("scanned", paths.size) }.toString()))
    }
)
