package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.hardware.camera2.CameraManager
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

fun createTorchTool(context: Context): Tool = Tool(
    name = "set_torch",
    description = "Turn the device flashlight/torch on or off.",
    needsApproval = true,
    parameters = { InputSchema.Obj(properties = buildJsonObject { putJsonObject("on") { put("type", "boolean"); put("description", "True to turn on, false to turn off") } }, required = listOf("on")) },
    execute = { args ->
        val on = args.jsonObject["on"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: return@Tool listOf(UIMessagePart.Text(buildJsonObject { put("success", false); put("error", "Missing 'on'") }.toString()))
        try {
            val cm = context.getSystemService(CameraManager::class.java)!!
            var id: String? = null
            for (c in cm.cameraIdList) { if (cm.getCameraCharacteristics(c).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) { id = c; break } }
            if (id == null) return@Tool listOf(UIMessagePart.Text(buildJsonObject { put("success", false); put("error", "No flashlight") }.toString()))
            cm.setTorchMode(id!!, on)
            listOf(UIMessagePart.Text(buildJsonObject { put("success", true); put("torch_on", on) }.toString()))
        } catch (e: Exception) { listOf(UIMessagePart.Text(buildJsonObject { put("success", false); put("error", e.message ?: "") }.toString())) }
    }
)
