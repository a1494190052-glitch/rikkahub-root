package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.provider.Settings
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

fun createGetBrightnessTool(context: Context): Tool = Tool(
    name = "get_brightness",
    description = "Get the current screen brightness level (0-255) and whether auto-brightness is enabled.",
    parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
    execute = { _ ->
        try {
            val cr = context.contentResolver
            val brightness = try { Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS) } catch (_: Exception) { 128 }
            val auto = try { Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC } catch (_: Exception) { false }
            listOf(UIMessagePart.Text(buildJsonObject { put("success", true); put("brightness", brightness); put("max", 255); put("auto", auto); put("message", "$brightness/255, Auto: $auto") }.toString()))
        } catch (e: Exception) { listOf(UIMessagePart.Text(buildJsonObject { put("success", false); put("error", e.message ?: "") }.toString())) }
    }
)

fun createSetBrightnessTool(context: Context): Tool = Tool(
    name = "set_brightness",
    description = "Set the screen brightness (1-255). Requires WRITE_SETTINGS permission.",
    needsApproval = { true },
    parameters = { InputSchema.Obj(properties = buildJsonObject { putJsonObject("value") { put("type", "integer"); put("description", "Brightness value (1-255)") } }, required = listOf("value")) },
    execute = { args ->
        val value = args.jsonObject["value"]?.jsonPrimitive?.intOrNull ?: return@Tool listOf(UIMessagePart.Text(buildJsonObject { put("success", false); put("error", "Missing 'value'") }.toString()))
        try {
            if (!Settings.System.canWrite(context)) return@Tool listOf(UIMessagePart.Text(buildJsonObject { put("success", false); put("error", "WRITE_SETTINGS not granted") }.toString()))
            val cr = context.contentResolver
            try { Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) } catch (_: Exception) {}
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, value.coerceIn(1, 255))
            listOf(UIMessagePart.Text(buildJsonObject { put("success", true); put("brightness", value.coerceIn(1, 255)) }.toString()))
        } catch (e: Exception) { listOf(UIMessagePart.Text(buildJsonObject { put("success", false); put("error", e.message ?: "") }.toString())) }
    }
)
