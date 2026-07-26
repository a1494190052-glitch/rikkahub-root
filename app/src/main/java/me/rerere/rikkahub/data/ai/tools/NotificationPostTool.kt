package me.rerere.rikkahub.data.ai.tools

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
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

private const val CHANNEL_ID = "rikkahub_ai_tool"

fun createNotificationPostTool(context: Context): Tool = Tool(
    name = "post_notification", description = "Post a system notification to the user.", needsApproval = { true },
    parameters = { InputSchema.Obj(properties = buildJsonObject { putJsonObject("title") { put("type", "string"); put("description", "Notification title") }; putJsonObject("body") { put("type", "string"); put("description", "Notification body") }; putJsonObject("id") { put("type", "integer"); put("description", "Notification ID") } }, required = listOf("title")) },
    execute = { args ->
        val params = args.jsonObject; val title = params["title"]?.jsonPrimitive?.contentOrNull; val body = params["body"]?.jsonPrimitive?.contentOrNull ?: ""; val id = params["id"]?.jsonPrimitive?.intOrNull ?: (System.currentTimeMillis() / 1000).toInt()
        if (title.isNullOrBlank()) return@Tool listOf(UIMessagePart.Text(buildJsonObject { put("success", false); put("error", "Missing 'title'") }.toString()))
        try {
            val mgr = NotificationManagerCompat.from(context)
            if (!mgr.areNotificationsEnabled()) return@Tool listOf(UIMessagePart.Text(buildJsonObject { put("success", false); put("error", "Notifications disabled") }.toString()))
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm?.getNotificationChannel(CHANNEL_ID) == null) nm?.createNotificationChannel(NotificationChannel(CHANNEL_ID, "AI Tools", NotificationManager.IMPORTANCE_DEFAULT))
            mgr.notify(id, NotificationCompat.Builder(context, CHANNEL_ID).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(body).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_DEFAULT).build())
            listOf(UIMessagePart.Text(buildJsonObject { put("success", true); put("notification_id", id) }.toString()))
        } catch (e: Exception) { listOf(UIMessagePart.Text(buildJsonObject { put("success", false); put("error", e.message ?: "") }.toString())) }
    }
)
