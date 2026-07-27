package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.tools.createBatteryTool
import me.rerere.rikkahub.data.ai.tools.createGetBrightnessTool
import me.rerere.rikkahub.data.ai.tools.createSetBrightnessTool
import me.rerere.rikkahub.data.ai.tools.createMediaScannerTool
import me.rerere.rikkahub.data.ai.tools.createNotificationPostTool
import me.rerere.rikkahub.data.ai.tools.createShareTool
import me.rerere.rikkahub.data.ai.tools.createStorageInfoTool
import me.rerere.rikkahub.data.ai.tools.createTelephonyInfoTool
import me.rerere.rikkahub.data.ai.tools.createToastTool
import me.rerere.rikkahub.data.ai.tools.createTorchTool
import me.rerere.rikkahub.data.ai.tools.createVibrateTool
import me.rerere.rikkahub.data.ai.tools.createGetVolumeTool
import me.rerere.rikkahub.data.ai.tools.createSetVolumeTool
import me.rerere.rikkahub.data.ai.tools.createWakeScreenTool
import me.rerere.rikkahub.data.ai.tools.createWifiInfoTool
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
    private val mcpManager: me.rerere.rikkahub.data.ai.mcp.McpManager? = null,
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

    private fun toolApprovalOverride(name: String): Boolean? =
        settingsStore.settingsFlow.value.toolApprovalOverrides[name]

    val rootShellTool by lazy { buildRootShellTool(shellSessionManager, shellAuditLogger, isSubAgent) { toolApprovalOverride("root_shell") } }
    val ptyExecTool by lazy { buildPtyExecTool(context, shellAuditLogger) { toolApprovalOverride("pty_exec") } }
    val ptySessionTool by lazy { ptySessionManager?.let { buildPtySessionTool(context, it, shellAuditLogger) { toolApprovalOverride("pty_session") } } }
    val mcpManagerTool by lazy { buildMcpManagerTool(settingsStore, mcpManager) }
    val rootScreenshotTool by lazy { buildRootScreenshotTool(context) }
    val uiTreeTool by lazy { buildUiTreeTool(context) }

    // 橘瓣移植系统工具
    val batteryTool by lazy { createBatteryTool(context) }
    val getBrightnessTool by lazy { createGetBrightnessTool(context) }
    val setBrightnessTool by lazy { createSetBrightnessTool(context) }
    val torchTool by lazy { createTorchTool(context) }
    val vibrateTool by lazy { createVibrateTool(context) }
    val getVolumeTool by lazy { createGetVolumeTool(context) }
    val setVolumeTool by lazy { createSetVolumeTool(context) }
    val wakeScreenTool by lazy { createWakeScreenTool(context) }
    val wifiInfoTool by lazy { createWifiInfoTool(context) }
    val telephonyInfoTool by lazy { createTelephonyInfoTool(context) }
    val storageInfoTool by lazy { createStorageInfoTool(context) }
    val toastTool by lazy { createToastTool(context) }
    val notificationPostTool by lazy { createNotificationPostTool(context) }
    val shareTool by lazy { createShareTool(context) }
    val mediaScannerTool by lazy { createMediaScannerTool(context) }

    fun forSubAgent(): LocalTools =
        LocalTools(context, eventBus, ttsManager, settingsStore, null, shellAuditLogger, null, isSubAgent = true)

    fun getTools(options: List<LocalToolOption>): List<Tool> {
        val tools = mutableListOf<Tool>()
        if (!isSubAgent) { tools.add(mcpManagerTool) }
        if (options.contains(LocalToolOption.JavascriptEngine)) tools.add(javascriptTool)
        if (options.contains(LocalToolOption.TimeInfo)) tools.add(timeTool)
        if (options.contains(LocalToolOption.Clipboard)) tools.add(clipboardTool)
        if (options.contains(LocalToolOption.Tts)) tools.add(ttsTool)
        if (options.contains(LocalToolOption.AskUser)) tools.add(askUserTool)
        if (options.contains(LocalToolOption.ScreenTime)) tools.add(screenTimeTool)
        if (options.contains(LocalToolOption.Calendar)) { tools.add(calendarQueryTool); tools.add(calendarCreateTool) }
        if (options.contains(LocalToolOption.RootShell)) {
            tools.add(rootShellTool)
            if (!isSubAgent) { tools.add(ptyExecTool); ptySessionTool?.let { tools.add(it) } }
            tools.add(rootScreenshotTool); tools.add(uiTreeTool)
        }
        // 橘瓣移植系统工具
        if (options.contains(LocalToolOption.Battery)) tools.add(batteryTool)
        if (options.contains(LocalToolOption.Brightness)) { tools.add(getBrightnessTool); tools.add(setBrightnessTool) }
        if (options.contains(LocalToolOption.Torch)) tools.add(torchTool)
        if (options.contains(LocalToolOption.Vibrate)) tools.add(vibrateTool)
        if (options.contains(LocalToolOption.Volume)) { tools.add(getVolumeTool); tools.add(setVolumeTool) }
        if (options.contains(LocalToolOption.WakeScreen)) tools.add(wakeScreenTool)
        if (options.contains(LocalToolOption.WifiInfo)) tools.add(wifiInfoTool)
        if (options.contains(LocalToolOption.TelephonyInfo)) tools.add(telephonyInfoTool)
        if (options.contains(LocalToolOption.StorageInfo)) tools.add(storageInfoTool)
        if (options.contains(LocalToolOption.Toast)) tools.add(toastTool)
        if (options.contains(LocalToolOption.PostNotification)) tools.add(notificationPostTool)
        if (options.contains(LocalToolOption.Share)) tools.add(shareTool)
        if (options.contains(LocalToolOption.ScanMedia)) tools.add(mediaScannerTool)
        return tools
    }
}
