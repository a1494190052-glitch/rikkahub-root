package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

fun createVibrateTool(context: Context): Tool = Tool(
    name = "vibrate",
    description = "Vibrate the device. Provide duration_ms (single) or pattern (waveform of off/on ms).",
    needsApproval = true,
    parameters = { InputSchema.Obj(properties = buildJsonObject { putJsonObject("duration_ms") { put("type", "integer"); put("description", "Duration in ms (1-5000)") }; putJsonObject("pattern") { put("type", "array"); put("description", "Waveform [off,on,...] in ms"); putJsonObject("items") { put("type", "integer") } } }) },
    execute = { args ->
        val params = args.jsonObject; val dur = params["duration_ms"]?.jsonPrimitive?.intOrNull; val pat = params["pattern"] as? JsonArray
        if (dur != null && pat != null) return@Tool listOf(UIMessagePart.Text(buildJsonObject { put("success", false); put("error", "Use duration_ms or pattern, not both") }.toString()))
        try {
            val v = context.getSystemService(Vibrator::class.java)!!
            if (!v.hasVibrator()) return@Tool listOf(UIMessagePart.Text(buildJsonObject { put("success", false); put("error", "No vibrator") }.toString()))
            if (pat != null) { v.vibrate(VibrationEffect.createWaveform(pat.mapNotNull { it.jsonPrimitive.intOrNull?.toLong() }.toLongArray(), -1)); listOf(UIMessagePart.Text(buildJsonObject { put("success", true); put("mode", "pattern") }.toString())) }
            else { val ms = (dur ?: 500).coerceIn(1, 5000); v.vibrate(VibrationEffect.createOneShot(ms.toLong(), VibrationEffect.DEFAULT_AMPLITUDE)); listOf(UIMessagePart.Text(buildJsonObject { put("success", true); put("mode", "oneshot"); put("duration_ms", ms) }.toString())) }
        } catch (e: Exception) { listOf(UIMessagePart.Text(buildJsonObject { put("success", false); put("error", e.message ?: "") }.toString())) }
    }
)
