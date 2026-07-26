package me.rerere.rikkahub.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.math.roundToInt
import kotlin.uuid.Uuid

private const val TAG = "FloatingTaskService"
private const val CHANNEL_ID = "floating_task"
private const val NOTIFICATION_ID = 4207

/**
 * 二改：任务悬浮窗。
 * 当 AI 正在执行任务（生成回复 / 调用工具）且用户开启了悬浮窗开关时，
 * 在屏幕上显示一个可拖动的小浮标，实时展示当前状态（如"正在执行: root_shell"），
 * 点击可跳回 App。用于跨应用自动化任务时观察 AI 的进展。
 */
class FloatingTaskService : Service(), KoinComponent {
    private val chatService: ChatService by inject()
    private val settingsStore: SettingsStore by inject()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var statusText: TextView? = null
    private var statusJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForegroundCompat()
        observeState()
    }

    override fun onDestroy() {
        hideOverlay()
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    private fun observeState() {
        scope.launch {
            combine(
                ConversationSession.activeGenerationIds,
                settingsStore.settingsFlow,
            ) { ids, settings -> ids to settings.floatingTaskWindowEnabled }
                .collect { (ids, enabled) ->
                    if (enabled && ids.isNotEmpty() && Settings.canDrawOverlays(this@FloatingTaskService)) {
                        showOverlay()
                        watchStatus(ids.last())
                    } else {
                        hideOverlay()
                    }
                }
        }
    }

    private fun watchStatus(conversationId: Uuid) {
        if (statusJob?.isActive == true) return
        statusJob = scope.launch {
            combine(
                chatService.getConversationFlow(conversationId),
                chatService.getProcessingStatusFlow(conversationId),
            ) { conversation, processing ->
                val pendingTool = conversation.currentMessages
                    .lastOrNull()
                    ?.parts
                    ?.filterIsInstance<UIMessagePart.Tool>()
                    ?.lastOrNull { !it.isExecuted }
                when {
                    pendingTool != null -> getString(R.string.floating_task_running_tool, pendingTool.toolName)
                    !processing.isNullOrBlank() -> processing
                    else -> getString(R.string.floating_task_working)
                }
            }.collect { text ->
                statusText?.text = text
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun showOverlay() {
        if (overlayView != null) return
        val view = buildOverlayView()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(160)
        }
        runCatching {
            windowManager.addView(view, params)
            overlayView = view
        }
    }

    private fun hideOverlay() {
        statusJob?.cancel()
        statusJob = null
        overlayView?.let { runCatching { windowManager.removeView(it) } }
        overlayView = null
        statusText = null
    }

    private fun buildOverlayView(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(0xF2222222.toInt())
                setStroke(dp(1), 0x33FFFFFF)
            }
            setPadding(dp(12), dp(8), dp(14), dp(8))
        }
        val dot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF7C5CFF.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                marginEnd = dp(8)
            }
        }
        val text = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            maxWidth = dp(200)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            text = getString(R.string.floating_task_working)
        }
        statusText = text
        card.addView(dot)
        card.addView(text)

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        card.setOnTouchListener { v, event ->
            val lp = v.layoutParams as WindowManager.LayoutParams
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = lp.x
                    startY = lp.y
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).roundToInt()
                    val dy = (event.rawY - downY).roundToInt()
                    if (kotlin.math.abs(dx) > dp(6) || kotlin.math.abs(dy) > dp(6)) moved = true
                    if (moved) {
                        lp.x = startX + dx
                        lp.y = startY + dy
                        runCatching { windowManager.updateViewLayout(v, lp) }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        runCatching {
                            packageManager.getLaunchIntentForPackage(packageName)
                                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                ?.let { startActivity(it) }
                        }
                    }
                    true
                }

                else -> false
            }
        }
        return card
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.floating_task_notification_channel),
            NotificationManager.IMPORTANCE_MIN,
        )
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle(getString(R.string.floating_task_notification_title))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()

    private fun startForegroundCompat() {
        runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        }.onFailure {
            runCatching { startForeground(NOTIFICATION_ID, buildNotification()) }
        }
    }
}
