package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.tts.provider.TTSManager

class LocalTools(
    private val context: Context,
    private val eventBus: AppEventBus,
    private val ttsManager: TTSManager,
    private val settingsStore: SettingsStore,
    private val shellSessionManager: me.rerere.workspace.ShellSessionManager? = null,
    private val shellAuditLogger: me.rerere.rikkahub.service.shell.ShellAuditLogger? = null,
    private val ptySessionManager: me.rerere.rikkahub.service.shell.PtySessionManager? = null,
    private val isSubAgent: Boolean = false,
) {
    val javascriptTool by lazy { buildJavascriptTool() }

    val timeTool by lazy { buildTimeInfoTool() }

    val clipboardTool by lazy { buildClipboardTool(context) }

    val ttsTool by lazy { buildTextToSpeechTool(eventBus, ttsManager, settingsStore) }

    val askUserTool by lazy { buildAskUserTool() }

    val screenTimeTool by lazy { buildScreenTimeTool(context, eventBus) }

    val calendarQueryTool by lazy { buildCalendarQueryTool(context) }

    val calendarCreateTool by lazy { buildCalendarCreateTool(context) }

    val rootShellTool by lazy { buildRootShellTool(shellSessionManager, shellAuditLogger, isSubAgent) }

    val ptyExecTool by lazy { buildPtyExecTool(context, shellAuditLogger) }

    val ptySessionTool by lazy {
        ptySessionManager?.let { buildPtySessionTool(context, it, shellAuditLogger) }
    }

    /**
     * 子代理专用实例: 不带持久 shell 会话, root_shell 强制走一次性进程。
     * 并行子代理若共享 host_root 持久会话会互相污染 cwd / 环境变量。
     * 同时排除 pty_exec 和 pty_session: 强制人工审批, 子代理拿不到审批会卡死。
     */
    fun forSubAgent(): LocalTools =
        LocalTools(context, eventBus, ttsManager, settingsStore, null, shellAuditLogger, null, isSubAgent = true)

    val rootScreenshotTool by lazy { buildRootScreenshotTool(context) }

    val uiTreeTool by lazy { buildUiTreeTool(context) }

    fun getTools(options: List<LocalToolOption>): List<Tool> {
        val tools = mutableListOf<Tool>()
        if (options.contains(LocalToolOption.JavascriptEngine)) {
            tools.add(javascriptTool)
        }
        if (options.contains(LocalToolOption.TimeInfo)) {
            tools.add(timeTool)
        }
        if (options.contains(LocalToolOption.Clipboard)) {
            tools.add(clipboardTool)
        }
        if (options.contains(LocalToolOption.Tts)) {
            tools.add(ttsTool)
        }
        if (options.contains(LocalToolOption.AskUser)) {
            tools.add(askUserTool)
        }
        if (options.contains(LocalToolOption.ScreenTime)) {
            tools.add(screenTimeTool)
        }
        if (options.contains(LocalToolOption.Calendar)) {
            tools.add(calendarQueryTool)
            tools.add(calendarCreateTool)
        }
        if (options.contains(LocalToolOption.RootShell)) {
            tools.add(rootShellTool)
            if (!isSubAgent) {
                tools.add(ptyExecTool)
                ptySessionTool?.let { tools.add(it) }
            }
            tools.add(rootScreenshotTool)
            tools.add(uiTreeTool)
        }
        return tools
    }
}
