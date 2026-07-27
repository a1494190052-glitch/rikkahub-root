package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.os.PowerManager
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

@Suppress("DEPRECATION")
fun createWakeScreenTool(context: Context): Tool = Tool(
    name = "wake_screen", description = "Wake up the screen if it is off. Holds a wake lock for a specified duration.", needsApproval = { true },
    parameters = { InputSchema.Obj(properties = buildJsonObject { putJsonObject("hold_ms") { put("type", "integer"); put("description", "Hold duration ms (500-30000). Default: 3000") } }) },
    execute = { args ->
        val holdMs = (args.jsonObject["hold_ms"]?.jsonPrimitive?.intOrNull ?: 3000).coerceIn(500, 30000)
        try {
            val pm = context.getSystemService(PowerManager::class.java)!!
            if (pm.isInteractive) return@Tool listOf(UIMessagePart.Text(buildJsonObject { put("success", true); put("was_off", false) }.toString()))
            @Suppress("DEPRECATION") val wl = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE, "Rikkahub:Wake")
            wl.acquire(holdMs.toLong())
            listOf(UIMessagePart.Text(buildJsonObject { put("success", true); put("was_off", true); put("hold_ms", holdMs) }.toString()))
        } catch (e: Exception) { listOf(UIMessagePart.Text(buildJsonObject { put("success", false); put("error", e.message ?: "") }.toString())) }
    }
)
