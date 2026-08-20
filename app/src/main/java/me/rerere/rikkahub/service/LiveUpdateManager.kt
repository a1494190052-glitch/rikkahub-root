package me.rerere.rikkahub.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat
import me.rerere.rikkahub.R

/**
 * Manages Live Update / Dynamic Island notifications for agent status display.
 *
 * Adapted from OpenMinis AgentForegroundService + DynamicIslandSupport:
 * - On Android 16+ (API 36, Baklava): uses Notification.ProgressStyle with
 *   FLAG_PROMOTED_ONGOING for the "dynamic island" / always-visible status chip.
 * - On older Android: falls back to a regular progress notification via
 *   NotificationCompat.
 *
 * Simple object singleton — no DI needed. Channel ID is "agent_live_status"
 * which does not conflict with existing RikkaHub channels:
 *   chat_completed, chat_live_update, web_server, scheduled_task,
 *   floating_task, rikkahub_ai_tool.
 */
object LiveUpdateManager {

    private const val TAG = "LiveUpdateManager"
    private const val CHANNEL_ID = "agent_live_status"
    private const val CHANNEL_NAME = "Agent Live Status"
    private const val NOTIFICATION_ID = 9100

    /**
     * Framework extras key read by Notification.isRequestPromotedOngoing()
     * (Android 16). Not exported as a public SDK constant; value verified from
     * the on-device framework.jar (const-string "android.requestPromotedOngoing").
     * This is the *request* flag — the system sets FLAG_PROMOTED_ONGOING on the
     * notification AFTER it decides to promote.
     */
    private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

    @Volatile
    private var channelCreated = false

    /**
     * Updates the agent status notification.
     *
     * @param context Application or service context.
     * @param text Status text to display (e.g. "Running tool: web_search").
     * @param progress Determinate progress 0.0–1.0, or null for indeterminate.
     */
    fun updateStatus(context: Context, text: String, progress: Float? = null) {
        try {
            ensureChannel(context)

            val notification = if (isDynamicIslandCapable(context)) {
                buildPromotedNotification(context, text, progress)
            } else {
                buildFallbackNotification(context, text, progress)
            }

            val nm = context.getSystemService(NotificationManager::class.java)
            nm?.notify(NOTIFICATION_ID, notification)
        } catch (t: Throwable) {
            Log.e(TAG, "updateStatus failed", t)
        }
    }

    /**
     * Dismisses the agent status notification.
     */
    fun dismiss(context: Context) {
        try {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm?.cancel(NOTIFICATION_ID)
            Log.d(TAG, "Notification dismissed")
        } catch (t: Throwable) {
            Log.e(TAG, "dismiss failed", t)
        }
    }

    /**
     * True when this device can post promoted ("dynamic island") notifications
     * right now. Runtime-safe on all API levels — returns false pre-36 without
     * touching any 36-only symbol.
     *
     * A device supports the dynamic island only when BOTH:
     *  1. It runs Android 16+ (SDK_INT >= 36 / BAKLAVA), so the ProgressStyle +
     *     canPostPromotedNotifications APIs exist, AND
     *  2. canPostPromotedNotifications() returns true (system + per-app user grant).
     */
    private fun isDynamicIslandCapable(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 36) return false
        return try {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm?.canPostPromotedNotifications() == true
        } catch (t: Throwable) {
            // Defensive: some early/partial Baklava builds may throw if the
            // feature isn't fully wired. Treat any failure as "not capable".
            Log.w(TAG, "canPostPromotedNotifications() failed: ${t.message}")
            false
        }
    }

    /**
     * Android 16+ promoted notification with ProgressStyle for dynamic island.
     *
     * Uses the native Notification.Builder (androidx.core lacks these APIs).
     * The promoted-notification contract requires: ongoing, contentTitle,
     * a supported style (ProgressStyle), NOT a group summary, NOT colorized,
     * and channel importance >= LOW.
     */
    private fun buildPromotedNotification(
        context: Context,
        text: String,
        progress: Float?
    ): Notification {
        // A ProgressStyle only counts as promotable when it carries at least one
        // segment with positive length. We always seed one full-length segment
        // and set indeterminate when progress is null (open-ended operations).
        val progressStyle = Notification.ProgressStyle()
            .addProgressSegment(Notification.ProgressStyle.Segment(100))
            .setProgressIndeterminate(progress == null)

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setStyle(progressStyle)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            // Explicitly NOT colorized and NOT a group summary — both would
            // disqualify the notification from promotion.
            .setColorized(false)
            // Short text shown in the status-bar chip when promoted
            .setShortCriticalText(text.take(30))

        // Request "dynamic island" promotion via the framework extras key.
        builder.addExtras(Bundle().apply {
            putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true)
        })

        val notification = builder.build()

        // Diagnostic: verify promotion preconditions are met
        if (!notification.hasPromotableCharacteristics()) {
            val flags = notification.flags
            Log.w(
                TAG,
                "Promoted notification lacks promotable characteristics — diag: " +
                    "requestPromotedOngoing=${notification.extras.getBoolean(EXTRA_REQUEST_PROMOTED_ONGOING)} " +
                    "FLAG_ONGOING=${(flags and Notification.FLAG_ONGOING_EVENT) != 0} " +
                    "hasTitle=${!notification.extras.getCharSequence(Notification.EXTRA_TITLE).isNullOrEmpty()} " +
                    "smallIcon=${notification.smallIcon != null} " +
                    "isGroupSummary=${(flags and Notification.FLAG_GROUP_SUMMARY) != 0} " +
                    "style=${notification.extras.getString(Notification.EXTRA_TEMPLATE)}"
            )
        } else {
            Log.d(TAG, "Promoted notification OK — hasPromotableCharacteristics=true")
        }

        return notification
    }

    /**
     * Fallback notification for pre-Android-16 devices using NotificationCompat.
     * Shows a standard progress bar (determinate or indeterminate).
     */
    private fun buildFallbackNotification(
        context: Context,
        text: String,
        progress: Float?
    ): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setSilent(true)

        if (progress != null) {
            // Determinate: map 0.0–1.0 to 0–100
            val pct = (progress.coerceIn(0f, 1f) * 100).toInt()
            builder.setProgress(100, pct, false)
        } else {
            // Indeterminate: open-ended operation
            builder.setProgress(0, 0, true)
        }

        return builder.build()
    }

    /**
     * Creates the notification channel (idempotent). Uses LOW importance so
     * status updates don't make sound or vibrate on every tick.
     */
    private fun ensureChannel(context: Context) {
        if (channelCreated) return
        synchronized(this) {
            if (channelCreated) return
            try {
                val nm = context.getSystemService(NotificationManager::class.java) ?: return
                if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                    val channel = NotificationChannel(
                        CHANNEL_ID,
                        CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = "Real-time agent task progress and status"
                        setShowBadge(false)
                    }
                    nm.createNotificationChannel(channel)
                    Log.i(TAG, "Created notification channel: $CHANNEL_ID")
                }
                channelCreated = true
            } catch (t: Throwable) {
                Log.e(TAG, "ensureChannel failed", t)
            }
        }
    }
}
