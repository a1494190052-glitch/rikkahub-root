package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.os.Environment
import android.os.StatFs
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

fun createStorageInfoTool(context: Context): Tool = Tool(
    name = "get_storage_info", description = "Get internal and external storage space usage info (total, free, used bytes).", needsApproval = { true },
    parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
    execute = { _ ->
        try {
            val result = buildJsonObject {
                put("success", true)
                try { val s = StatFs(Environment.getDataDirectory().path); putJsonObject("internal") { put("total_bytes", s.totalBytes); put("free_bytes", s.freeBytes); put("used_bytes", s.totalBytes - s.freeBytes) } } catch (e: Exception) { putJsonObject("internal") { put("error", e.message ?: "") } }
                try { if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) { val s = StatFs(Environment.getExternalStorageDirectory().path); putJsonObject("external") { put("total_bytes", s.totalBytes); put("free_bytes", s.freeBytes); put("used_bytes", s.totalBytes - s.freeBytes) } } else put("external", JsonNull) } catch (e: Exception) { putJsonObject("external") { put("error", e.message ?: "") } }
            }
            listOf(UIMessagePart.Text(result.toString()))
        } catch (e: Exception) { listOf(UIMessagePart.Text(buildJsonObject { put("success", false); put("error", e.message ?: "") }.toString())) }
    }
)
