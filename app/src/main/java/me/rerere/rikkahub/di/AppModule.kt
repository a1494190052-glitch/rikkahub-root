package me.rerere.rikkahub.di

import kotlinx.serialization.json.Json
import me.rerere.highlight.Highlighter
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.subagent.SubagentHost
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.service.ChatNotificationManager
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.utils.EmojiData
import me.rerere.rikkahub.utils.EmojiUtils
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.SoundEffectPlayer
import me.rerere.rikkahub.utils.UpdateChecker
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.mcp.McpServerManager
import me.rerere.rikkahub.web.WebServerManager
import me.rerere.tts.provider.TTSManager
import org.koin.dsl.module

val appModule = module {
    single<Json> { JsonInstant }

    single { Highlighter(get()) }
    single { AppEventBus() }
    single { LocalTools(get(), get(), get(), get(), get(), get(), get(), get(), browserTabPool = get()) }

    // ---- 浏览器自动化 (OpenMinis 移植) ----
    single { me.rerere.rikkahub.browser.BrowserTabPool(get()) }

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

    single { me.rerere.rikkahub.service.shell.ShellAuditLogger(get<me.rerere.rikkahub.data.db.AppDatabase>().shellAuditDao()) }

    single { me.rerere.rikkahub.service.shell.PtySessionManager(context = get()) }

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
            rootModeProvider = { get<me.rerere.rikkahub.data.datastore.SettingsStore>().settingsFlow.value.workspaceRootMode },
            auditLogger = get(),
        )
    }

    single { UpdateChecker(get()) }
    single { AppScope() }
    single<EmojiData> { EmojiUtils.loadEmoji(get()) }
    single { TTSManager(get()) }
    single { SoundEffectPlayer(get()) }

    // ---- 子代理系统 (kimi-code) ----
    single { SubagentHost(generationHandler = get()) }

    single(createdAtStart = true) {
        ChatNotificationManager(context = get(), appScope = get(), eventBus = get(), settingsStore = get())
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
            shellAuditLogger = get(),
            subagentHost = get(),
            json = get(),
            acpRuntime = get(),
            acpAgentProfilesStore = get(),
        )
    }

    single {
        WebServerManager(context = get(), appScope = get(), chatService = get(), conversationRepo = get(), folderRepo = get(), settingsStore = get(), filesManager = get())
    }

    single {
        McpServerManager(context = get(), appScope = get(), localTools = get())
    }

    // ---- ACP agent (外部 agent 后端) ----
    single { me.rerere.rikkahub.acp.AcpAgentProfilesStore(context = get(), json = get()) }

    single {
        me.rerere.rikkahub.acp.AcpProcessFactory(
            context = get(),
            workspaceRootProvider = {
                get<me.rerere.rikkahub.data.repository.WorkspaceRepository>()
                    .listFlow().first()
                    .firstOrNull { it.shellStatus == me.rerere.workspace.WorkspaceShellStatus.READY.name }
                    ?.root
            },
        )
    }

    single {
        me.rerere.rikkahub.acp.AcpRuntime(
            scope = get<AppScope>(),
            processBuilderFactory = { profile ->
                get<me.rerere.rikkahub.acp.AcpProcessFactory>().build(profile)
            },
        )
    }
}
