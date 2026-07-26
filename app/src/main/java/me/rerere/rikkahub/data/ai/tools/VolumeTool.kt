package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.media.AudioManager
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

private val STREAM_MAP = mapOf("media" to AudioManager.STREAM_MUSIC, "ring" to AudioManager.STREAM_RING, "notification" to AudioManager.STREAM_NOTIFICATION, "alarm" to AudioManager.STREAM_ALARM, "voice_call" to AudioManager.STREAM_VOICE_CALL, "system" to AudioManager.STREAM_SYSTEM)

fun createGetVolumeTool(context: Context): Tool = Tool(
    name = "get_volume", description = "Get current volume level for an audio stream (media/ring/notification/alarm/voice_call/system).",
    parameters = { InputSchema.Obj(properties = buildJsonObject { putJsonObject("stream") { put("type", "string"); put("description", "Stream name, default: media") } }) },
    execute = { args ->
        val streamName = args.jsonObject["stream"]?.jsonPrimitive?.contentOrNull ?: "media"
        val streamType = STREAM_MAP[streamName] ?: return@Tool listOf(UIMessagePart.Text(buildJsonObject { put("success", false); put("error", "Unknown: $streamName") }.toString()))
        val am = context.getSystemService(AudioManager::class.java)!!; val vol = am.getStreamVolume(streamType); val max = am.getStreamMaxVolume(streamType)
        listOf(UIMessagePart.Text(buildJsonObject { put("success", true); put("stream", streamName); put("volume", vol); put("max", max); put("percent", if (max > 0) (vol * 100 / max) else 0) }.toString()))
    }
)

fun createSetVolumeTool(context: Context): Tool = Tool(
    name = "set_volume", description = "Set volume for an audio stream by percentage (0-100).", needsApproval = { true },
    parameters = { InputSchema.Obj(properties = buildJsonObject { putJsonObject("stream") { put("type", "string"); put("description", "Stream name") }; putJsonObject("percent") { put("type", "integer"); put("description", "Volume percentage (0-100)") } }, required = listOf("stream", "percent")) },
    execute = { args ->
        val streamName = args.jsonObject["stream"]?.jsonPrimitive?.contentOrNull; val percent = args.jsonObject["percent"]?.jsonPrimitive?.intOrNull
        if (streamName == null || percent == null) return@Tool listOf(UIMessagePart.Text(buildJsonObject { put("success", false); put("error", "Missing params") }.toString()))
        val streamType = STREAM_MAP[streamName] ?: return@Tool listOf(UIMessagePart.Text(buildJsonObject { put("success", false); put("error", "Unknown: $streamName") }.toString()))
        val am = context.getSystemService(AudioManager::class.java)!!; val max = am.getStreamMaxVolume(streamType); val target = (percent.coerceIn(0, 100) / 100.0 * max).toInt().coerceIn(0, max)
        am.setStreamVolume(streamType, target, 0); val actual = am.getStreamVolume(streamType)
        listOf(UIMessagePart.Text(buildJsonObject { put("success", true); put("stream", streamName); put("volume", actual); put("max", max); put("percent", if (max > 0) (actual * 100 / max) else 0) }.toString()))
    }
)
