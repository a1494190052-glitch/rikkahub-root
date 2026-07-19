package me.rerere.rikkahub.di

import com.google.firebase.Firebase
import com.google.firebase.analytics.analytics
import com.google.firebase.crashlytics.crashlytics
import kotlinx.serialization.json.Json
import me.rerere.highlight.Highlighter
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.service.ChatNotificationManager
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.utils.EmojiData
import me.rerere.rikkahub.utils.EmojiUtils
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.SoundEffectPlayer
import me.rerere.rikkahub.utils.UpdateChecker
import me.rerere.rikkahub.web.WebServerManager
import me.rerere.tts.provider.TTSManager
import org.koin.dsl.module

val appModule = module {
    single<Json> { JsonInstant }

    single {
        Highlighter(get())
    }

    single {
        AppEventBus()
    }

    single {
        LocalTools(get(), get(), get(), get(), get(), get(), get())
    }

    // 持久 Shell 会话注册表(workspace/root 复用, 空闲自动回收)
    single {
        val context: android.content.Context = get()
        me.rerere.workspace.ShellSessionManager(
            baseDir = java.io.File(context.filesDir, "workspaces"),
            nativeLibraryDir = java.io.File(context.applicationInfo.nativeLibraryDir),
            extraBindMounts = listOf(
                me.rerere.workspace.WorkspaceBindMount(
                    source = java.io.File(context.filesDir, me.rerere.rikkahub.data.files.FileFolders.SKILLS).apply { mkdirs() },
                    target = "/skills",
                ),
                me.rerere.workspace.WorkspaceBindMount(
                    source = java.io.File(context.filesDir, me.rerere.rikkahub.data.files.FileFolders.TOOL_OUTPUTS).apply { mkdirs() },
                    target = "/tool_outputs",
                ),
                me.rerere.workspace.WorkspaceBindMount(
                    source = java.io.File(context.filesDir, me.rerere.rikkahub.data.files.FileFolders.UPLOAD).apply { mkdirs() },
                    target = "/upload",
                ),
            ),
            rootModeProvider = {
                get<me.rerere.rikkahub.data.datastore.SettingsStore>().settingsFlow.value.workspaceRootMode
            },
        )
    }

    // Shell 审计日志
    single {
        me.rerere.rikkahub.service.shell.ShellAuditLogger(get<me.rerere.rikkahub.data.db.AppDatabase>().shellAuditDao())
    }

    // 持久 PTY 会话组管理器 (L2: AI 多轮交互终端)
    single {
        me.rerere.rikkahub.service.shell.PtySessionManager(context = get())
    }

    // 后台 Shell 任务管理器
    single {
        val context: android.content.Context = get()
        me.rerere.rikkahub.service.shell.BackgroundShellManager(
            filesDir = context.filesDir,
            workspacesDir = java.io.File(context.filesDir, "workspaces"),
            prootRunner = me.rerere.workspace.ProotShellRunner(
                nativeLibraryDir = java.io.File(context.applicationInfo.nativeLibraryDir),
                extraBindMounts = listOf(
                    me.rerere.workspace.WorkspaceBindMount(
                        source = java.io.File(context.filesDir, me.rerere.rikkahub.data.files.FileFolders.SKILLS).apply { mkdirs() },
                        target = "/skills",
                    ),
                    me.rerere.workspace.WorkspaceBindMount(
                        source = java.io.File(context.filesDir, me.rerere.rikkahub.data.files.FileFolders.TOOL_OUTPUTS).apply { mkdirs() },
                        target = "/tool_outputs",
                    ),
                    me.rerere.workspace.WorkspaceBindMount(
                        source = java.io.File(context.filesDir, me.rerere.rikkahub.data.files.FileFolders.UPLOAD).apply { mkdirs() },
                        target = "/upload",
                    ),
                ),
            ),
            rootModeProvider = {
                get<me.rerere.rikkahub.data.datastore.SettingsStore>().settingsFlow.value.workspaceRootMode
            },
            auditLogger = get(),
        )
    }

    single {
        UpdateChecker(get())
    }

    single {
        AppScope()
    }

    single<EmojiData> {
        EmojiUtils.loadEmoji(get())
    }

    single {
        TTSManager(get())
    }

    single {
        Firebase.crashlytics
    }

    single {
        Firebase.analytics
    }

    single {
        SoundEffectPlayer(get())
    }

    // 生成通知与业务解耦：ChatService 只发事件，通知由这里消费；
    // createdAtStart 保证进程启动即订阅，否则后台生成的事件会因无订阅者而丢失
    single(createdAtStart = true) {
        ChatNotificationManager(
            context = get(),
            appScope = get(),
            eventBus = get(),
            settingsStore = get(),
        )
    }

    single {
        ChatService(
            context = get(),
            appScope = get(),
            appEventBus = get(),
            settingsStore = get(),
            conversationRepo = get(),
            memoryRepository = get(),
            generationHandler = get(),
            templateTransformer = get(),
            providerManager = get(),
            localTools = get(),
            subAgentExecutor = get(),
            scheduledTaskRepository = get(),
            mcpManager = get(),
            filesManager = get(),
            skillManager = get(),
            workspaceRepository = get(),
            folderRepository = get(),
            shellSessionManager = get(),
            backgroundShellManager = get(),
            shellAuditLogger = get()
        )
    }

    single {
        WebServerManager(
            context = get(),
            appScope = get(),
            chatService = get(),
            conversationRepo = get(),
            folderRepo = get(),
            settingsStore = get(),
            filesManager = get()
        )
    }
}
