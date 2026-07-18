package me.rerere.rikkahub

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.runtime.Composer
import androidx.compose.runtime.tooling.ComposeStackTraceMode
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import me.rerere.rikkahub.data.files.FileFolders
import java.io.File
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import me.rerere.common.android.appTempFolder
import com.whl.quickjs.android.QuickJSLoader
import me.rerere.rikkahub.di.appModule
import me.rerere.rikkahub.di.dataSourceModule
import me.rerere.rikkahub.di.repositoryModule
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.di.viewModelModule
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.service.WebServerService
import me.rerere.rikkahub.utils.CrashHandler
import me.rerere.rikkahub.utils.DatabaseUtil
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceManager
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.androidx.workmanager.koin.workManagerFactory
import org.koin.core.context.startKoin

private const val TAG = "RikkaHubApp"

// 二改：预置「搞机助手」
private const val ROOT_PRESET_ASSISTANT_NAME = "搞机助手"
private const val ROOT_PRESET_ASSISTANT_PROMPT = """你是「搞机助手」，一个拥有 Android 设备 root 权限的自动化专家，可以通过工具直接操控这台手机。

可用工具：
- root_ui_tree：获取当前屏幕的 UI 控件树（元素文字、中心坐标、可点击性），定位元素时优先使用
- root_screenshot：截取当前屏幕图片，当控件树为空或界面复杂（游戏/自绘界面）时使用
- root_shell：以 root 执行任意 shell 命令

执行界面自动化任务时必须遵守：
1. 先获取 UI 树或截图确认当前界面状态，再决定下一步；绝不盲目点击
2. 打开应用：monkey -p <包名> -c android.intent.category.LAUNCHER 1；不知道包名时用 pm list packages | grep <关键词> 查找
3. 常用操作：点击 input tap x y（坐标取 UI 树中心点）；滑动 input swipe x1 y1 x2 y2 300；返回 input keyevent 4；主页 input keyevent 3；输入 input text "内容"（对中文支持差，输入中文时说明限制或改用其他方式）
4. 每执行一步界面操作后，重新获取 UI 树或截图验证结果；失败则分析原因换策略，同一策略最多重试 2 次
5. 等待界面加载用 sleep 1~3 秒
6. 任务完成后截图确认最终状态，简洁汇报：做了什么、结果如何
7. 涉及删除数据、清空应用、重启、修改系统文件等危险操作时，必须先向用户说明并获得明确同意
8. 你操作的是真实设备，命令立即生效，谨慎但果断"""

const val CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID = "chat_completed"
const val CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID = "chat_live_update"
const val WEB_SERVER_NOTIFICATION_CHANNEL_ID = "web_server"

class RikkaHubApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger()
            androidContext(this@RikkaHubApp)
            workManagerFactory()
            modules(appModule, viewModelModule, dataSourceModule, repositoryModule)
        }
        this.createNotificationChannel()

        // set cursor window size to 32MB
        DatabaseUtil.setCursorWindowSize(32 * 1024 * 1024)

        // install crash handler
        CrashHandler.install(this)

        // Init QuickJS native library
        QuickJSLoader.init()

        // delete temp files
        deleteTempFiles()

        // cleanup stale tool output files
        cleanupToolOutputs()

        // cleanup workspace temp dirs (proot + rootfs /tmp)
        cleanupWorkspaceTempDirs()

        // check workspace integrity (mark workspaces with missing files as broken after backup restore)
        checkWorkspaceIntegrity()

        // sync upload files to DB
        syncManagedFiles()

        // Start WebServer if enabled in settings
        startWebServerIfEnabled()
        startFloatingTaskServiceIfEnabled()
        seedRootPresetAssistant()

        // 定时任务: 启动时重排（闹钟不持久化，可能被系统清除）
        rescheduleScheduledTasks()

        // Increment launch count
        incrementLaunchCount()

        // Composer.setDiagnosticStackTraceMode(ComposeStackTraceMode.Auto)
    }

    private fun rescheduleScheduledTasks() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                val repo = get<me.rerere.rikkahub.service.scheduler.ScheduledTaskRepository>()
                me.rerere.rikkahub.service.scheduler.TaskScheduler.rescheduleAll(
                    this@RikkaHubApp,
                    repo.getEnabled()
                )
            }.onFailure {
                Log.e(TAG, "rescheduleScheduledTasks failed", it)
            }
        }
    }

    private fun incrementLaunchCount() {
        get<AppScope>().launch {
            runCatching {
                val store = get<SettingsStore>()
                val current = store.settingsFlowRaw.first()
                store.update(current.copy(launchCount = current.launchCount + 1))
                Log.i(TAG, "incrementLaunchCount: ${store.settingsFlowRaw.first().launchCount}")
            }.onFailure {
                Log.e(TAG, "incrementLaunchCount failed", it)
            }
        }
    }

    private fun cleanupWorkspaceTempDirs() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<WorkspaceManager>().cleanupAllTempDirs()
            }.onFailure {
                Log.e(TAG, "cleanupWorkspaceTempDirs failed", it)
            }
        }
    }

    private fun checkWorkspaceIntegrity() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<WorkspaceRepository>().checkIntegrity()
            }.onFailure {
                Log.e(TAG, "checkWorkspaceIntegrity failed", it)
            }
        }
    }

    private fun deleteTempFiles() {
        get<AppScope>().launch(Dispatchers.IO) {
            val dir = appTempFolder
            if (dir.exists()) {
                dir.deleteRecursively()
            }
        }
    }

    private fun cleanupToolOutputs() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                val dir = File(filesDir, FileFolders.TOOL_OUTPUTS)
                if (dir.exists()) {
                    dir.deleteRecursively()
                }
            }
        }
    }

    private fun syncManagedFiles() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<FilesManager>().syncFolder()
            }.onFailure {
                Log.e(TAG, "syncManagedFiles failed", it)
            }
        }
    }

    private fun seedRootPresetAssistant() {
        get<AppScope>().launch {
            runCatching {
                delay(500)
                val store = get<SettingsStore>()
                val settings = store.settingsFlowRaw.first()
                if (settings.assistants.none { it.name == ROOT_PRESET_ASSISTANT_NAME }) {
                    store.update(
                        settings.copy(
                            assistants = settings.assistants + Assistant(
                                name = ROOT_PRESET_ASSISTANT_NAME,
                                systemPrompt = ROOT_PRESET_ASSISTANT_PROMPT,
                                enableMemory = true,
                                useGlobalMemory = true,
                                localTools = listOf(
                                    LocalToolOption.TimeInfo,
                                    LocalToolOption.RootShell,
                                ),
                            )
                        )
                    )
                    Log.i(TAG, "seeded root preset assistant")
                }
            }.onFailure {
                Log.e(TAG, "seedRootPresetAssistant failed", it)
            }
        }
    }

    private fun startFloatingTaskServiceIfEnabled() {
        get<AppScope>().launch {
            runCatching {
                delay(500)
                val settings = get<SettingsStore>().settingsFlowRaw.first()
                if (settings.floatingTaskWindowEnabled &&
                    android.provider.Settings.canDrawOverlays(this@RikkaHubApp)
                ) {
                    startForegroundService(
                        Intent(this@RikkaHubApp, me.rerere.rikkahub.service.FloatingTaskService::class.java)
                    )
                }
            }.onFailure {
                Log.e(TAG, "startFloatingTaskServiceIfEnabled failed", it)
            }
        }
    }

    private fun startWebServerIfEnabled() {
        get<AppScope>().launch {
            runCatching {
                delay(500)
                val settings = get<SettingsStore>().settingsFlowRaw.first()
                if (settings.webServerEnabled) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            this@RikkaHubApp,
                            android.Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        Log.w(TAG, "startWebServerIfEnabled: notification permission not granted, skipping")
                        return@launch
                    }
                    if (Build.VERSION.SDK_INT >= 37 &&
                        !settings.webServerLocalhostOnly &&
                        ContextCompat.checkSelfPermission(
                            this@RikkaHubApp,
                            android.Manifest.permission.ACCESS_LOCAL_NETWORK
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        Log.w(TAG, "startWebServerIfEnabled: local network permission not granted, skipping")
                        return@launch
                    }
                    val intent = Intent(this@RikkaHubApp, WebServerService::class.java).apply {
                        action = WebServerService.ACTION_START
                        putExtra(WebServerService.EXTRA_PORT, settings.webServerPort)
                        putExtra(WebServerService.EXTRA_LOCALHOST_ONLY, settings.webServerLocalhostOnly)
                    }
                    startForegroundService(intent)
                }
            }.onFailure {
                Log.e(TAG, "startWebServerIfEnabled failed", it)
            }
        }
    }

    private fun createNotificationChannel() {
        val notificationManager = NotificationManagerCompat.from(this)
        val chatCompletedChannel = NotificationChannelCompat
            .Builder(
                CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_HIGH
            )
            .setName(getString(R.string.notification_channel_chat_completed))
            .setVibrationEnabled(true)
            .build()
        notificationManager.createNotificationChannel(chatCompletedChannel)

        val chatLiveUpdateChannel = NotificationChannelCompat
            .Builder(
                CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_LOW
            )
            .setName(getString(R.string.notification_channel_chat_live_update))
            .setVibrationEnabled(false)
            .build()
        notificationManager.createNotificationChannel(chatLiveUpdateChannel)

        val webServerChannel = NotificationChannelCompat
            .Builder(WEB_SERVER_NOTIFICATION_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(getString(R.string.notification_channel_web_server))
            .setVibrationEnabled(false)
            .setShowBadge(false)
            .build()
        notificationManager.createNotificationChannel(webServerChannel)

        // 定时任务主动消息渠道 (高重要性: 主动消息需要提醒)
        val scheduledTaskChannel = NotificationChannelCompat
            .Builder(
                me.rerere.rikkahub.service.scheduler.SCHEDULED_TASK_NOTIFICATION_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_HIGH
            )
            .setName(getString(R.string.notification_channel_scheduled_task))
            .setVibrationEnabled(true)
            .build()
        notificationManager.createNotificationChannel(scheduledTaskChannel)
    }

    override fun onTerminate() {
        super.onTerminate()
        get<AppScope>().cancel()
        stopService(Intent(this, WebServerService::class.java))
    }
}

class AppScope : CoroutineScope by CoroutineScope(
    SupervisorJob()
        + Dispatchers.Main
        + CoroutineName("AppScope")
        + CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "AppScope exception", e)
    }
)
