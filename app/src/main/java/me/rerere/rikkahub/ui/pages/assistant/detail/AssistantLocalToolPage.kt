package me.rerere.rikkahub.ui.pages.assistant.detail

import android.Manifest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.permission.PermissionInfo
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.hasUsageStatsPermission
import me.rerere.rikkahub.utils.openUsageAccessSettings
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun AssistantLocalToolPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(parameters = { parametersOf(id) })
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.assistant_page_tab_local_tools)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        AssistantLocalToolContent(
            innerPadding = innerPadding,
            assistant = assistant,
            toolApprovalOverrides = settings.toolApprovalOverrides,
            onUpdate = { vm.update(it) },
            onToggleApproval = { toolName, require -> vm.updateToolApproval(toolName, require) }
        )
    }
}

@Composable
private fun AssistantLocalToolContent(
    innerPadding: PaddingValues,
    assistant: Assistant,
    toolApprovalOverrides: Map<String, Boolean>,
    onUpdate: (Assistant) -> Unit,
    onToggleApproval: (String, Boolean) -> Unit,
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val permissionRequiredText = stringResource(R.string.assistant_page_local_tools_screen_time_permission_required)

    val calendarPermissionState = rememberPermissionState(
        permissions = setOf(
            PermissionInfo(Manifest.permission.READ_CALENDAR, displayName = { Text(stringResource(R.string.permission_calendar_read)) }, usage = { Text(stringResource(R.string.permission_calendar_read_desc)) }, required = true),
            PermissionInfo(Manifest.permission.WRITE_CALENDAR, displayName = { Text(stringResource(R.string.permission_calendar_write)) }, usage = { Text(stringResource(R.string.permission_calendar_write_desc)) }, required = true),
        )
    )
    PermissionManager(permissionState = calendarPermissionState)

    fun toggleLocalTool(option: LocalToolOption, enabled: Boolean) {
        if (enabled && option == LocalToolOption.ScreenTime && !context.hasUsageStatsPermission()) {
            toaster.show(message = permissionRequiredText, type = ToastType.Warning)
            context.openUsageAccessSettings()
        }
        if (enabled && option == LocalToolOption.Calendar && !calendarPermissionState.allPermissionsGranted) {
            calendarPermissionState.requestPermissions()
            return
        }
        val newLocalTools = if (enabled) assistant.localTools + option else assistant.localTools - option
        onUpdate(assistant.copy(localTools = newLocalTools))
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(innerPadding).imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CardGroup {
            ToolItem(R.string.assistant_page_local_tools_javascript_engine_title, R.string.assistant_page_local_tools_javascript_engine_desc, LocalToolOption.JavascriptEngine, assistant, ::toggleLocalTool)
            ToolItem(R.string.assistant_page_local_tools_time_info_title, R.string.assistant_page_local_tools_time_info_desc, LocalToolOption.TimeInfo, assistant, ::toggleLocalTool)
            ToolItem(R.string.assistant_page_local_tools_clipboard_title, R.string.assistant_page_local_tools_clipboard_desc, LocalToolOption.Clipboard, assistant, ::toggleLocalTool)
            ToolItem(R.string.assistant_page_local_tools_tts_title, R.string.assistant_page_local_tools_tts_desc, LocalToolOption.Tts, assistant, ::toggleLocalTool)
            ToolItem(R.string.assistant_page_local_tools_ask_user_title, R.string.assistant_page_local_tools_ask_user_desc, LocalToolOption.AskUser, assistant, ::toggleLocalTool)
            ToolItem(R.string.assistant_page_local_tools_screen_time_title, R.string.assistant_page_local_tools_screen_time_desc, LocalToolOption.ScreenTime, assistant, ::toggleLocalTool)
            ToolItem(R.string.assistant_page_local_tools_calendar_title, R.string.assistant_page_local_tools_calendar_desc, LocalToolOption.Calendar, assistant, ::toggleLocalTool)
            ToolItem(R.string.assistant_page_local_tools_root_shell_title, R.string.assistant_page_local_tools_root_shell_desc, LocalToolOption.RootShell, assistant, ::toggleLocalTool)
            ToolItem(R.string.assistant_page_local_tools_sub_agents_title, R.string.assistant_page_local_tools_sub_agents_desc, LocalToolOption.SubAgents, assistant, ::toggleLocalTool)
            ToolItem(R.string.assistant_page_local_tools_scheduler_title, R.string.assistant_page_local_tools_scheduler_desc, LocalToolOption.Scheduler, assistant, ::toggleLocalTool)
            // 橘瓣移植系统工具
            ToolItem(R.string.assistant_page_local_tools_battery_title, R.string.assistant_page_local_tools_battery_desc, LocalToolOption.Battery, assistant, ::toggleLocalTool)
            ToolItem(R.string.assistant_page_local_tools_brightness_title, R.string.assistant_page_local_tools_brightness_desc, LocalToolOption.Brightness, assistant, ::toggleLocalTool)
            ToolItem(R.string.assistant_page_local_tools_torch_title, R.string.assistant_page_local_tools_torch_desc, LocalToolOption.Torch, assistant, ::toggleLocalTool)
            ToolItem(R.string.assistant_page_local_tools_vibrate_title, R.string.assistant_page_local_tools_vibrate_desc, LocalToolOption.Vibrate, assistant, ::toggleLocalTool)
            ToolItem(R.string.assistant_page_local_tools_volume_title, R.string.assistant_page_local_tools_volume_desc, LocalToolOption.Volume, assistant, ::toggleLocalTool)
            ToolItem(R.string.assistant_page_local_tools_wake_screen_title, R.string.assistant_page_local_tools_wake_screen_desc, LocalToolOption.WakeScreen, assistant, ::toggleLocalTool)
            ToolItem(R.string.assistant_page_local_tools_wifi_info_title, R.string.assistant_page_local_tools_wifi_info_desc, LocalToolOption.WifiInfo, assistant, ::toggleLocalTool)
            ToolItem(R.string.assistant_page_local_tools_telephony_info_title, R.string.assistant_page_local_tools_telephony_info_desc, LocalToolOption.TelephonyInfo, assistant, ::toggleLocalTool)
            ToolItem(R.string.assistant_page_local_tools_storage_info_title, R.string.assistant_page_local_tools_storage_info_desc, LocalToolOption.StorageInfo, assistant, ::toggleLocalTool)
            ToolItem(R.string.assistant_page_local_tools_toast_title, R.string.assistant_page_local_tools_toast_desc, LocalToolOption.Toast, assistant, ::toggleLocalTool)
            ToolItem(R.string.assistant_page_local_tools_post_notification_title, R.string.assistant_page_local_tools_post_notification_desc, LocalToolOption.PostNotification, assistant, ::toggleLocalTool)
            ToolItem(R.string.assistant_page_local_tools_share_title, R.string.assistant_page_local_tools_share_desc, LocalToolOption.Share, assistant, ::toggleLocalTool)
            ToolItem(R.string.assistant_page_local_tools_scan_media_title, R.string.assistant_page_local_tools_scan_media_desc, LocalToolOption.ScanMedia, assistant, ::toggleLocalTool)
        }

        CardGroup(title = { Text(stringResource(R.string.assistant_page_local_tools_approval_section_title)) }) {
            item(
                headlineContent = { Text(stringResource(R.string.assistant_page_local_tools_approval_root_shell_title)) },
                supportingContent = { Text(stringResource(R.string.assistant_page_local_tools_approval_root_shell_desc)) },
                trailingContent = { Switch(checked = toolApprovalOverrides["root_shell"] != false, onCheckedChange = { onToggleApproval("root_shell", it) }) }
            )
            item(
                headlineContent = { Text(stringResource(R.string.assistant_page_local_tools_approval_pty_exec_title)) },
                supportingContent = { Text(stringResource(R.string.assistant_page_local_tools_approval_pty_exec_desc)) },
                trailingContent = { Switch(checked = toolApprovalOverrides["pty_exec"] != false, onCheckedChange = { onToggleApproval("pty_exec", it) }) }
            )
            item(
                headlineContent = { Text(stringResource(R.string.assistant_page_local_tools_approval_pty_session_title)) },
                supportingContent = { Text(stringResource(R.string.assistant_page_local_tools_approval_pty_session_desc)) },
                trailingContent = { Switch(checked = toolApprovalOverrides["pty_session"] != false, onCheckedChange = { onToggleApproval("pty_session", it) }) }
            )
        }
    }
}

@Composable
private fun ToolItem(title: Int, desc: Int, option: LocalToolOption, assistant: Assistant, onToggle: (LocalToolOption, Boolean) -> Unit) {
    CardGroup(Modifier) {
        item(
            headlineContent = { Text(stringResource(title)) },
            supportingContent = { Text(stringResource(desc)) },
            trailingContent = { Switch(checked = assistant.localTools.contains(option), onCheckedChange = { onToggle(option, it) }) }
        )
    }
}
