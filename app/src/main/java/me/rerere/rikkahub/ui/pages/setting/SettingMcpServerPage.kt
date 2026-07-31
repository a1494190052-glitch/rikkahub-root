package me.rerere.rikkahub.ui.pages.setting

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Play
import me.rerere.hugeicons.stroke.Stop
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.mcp.McpServerManager
import me.rerere.rikkahub.service.McpServerService
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.permission.PermissionLocalNetwork
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.PermissionNotification
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject
import java.security.SecureRandom

private fun generateMcpToken(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}

@Composable
fun SettingMcpServerPage() {
    val mcpServerManager: McpServerManager = koinInject()
    val settingsStore: SettingsStore = koinInject()
    val settings = LocalSettings.current.settings
    val serverState by mcpServerManager.state.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val toaster = LocalToaster.current
    val copiedText = stringResource(R.string.copied)
    var portText by remember(settings.mcpServerPort) {
        mutableStateOf(settings.mcpServerPort.toString())
    }
    var tokenVisible by remember { mutableStateOf(false) }

    // Ensure a token exists so the user never runs auth-enabled with a blank token.
    LaunchedEffect(Unit) {
        if (settings.mcpServerToken.isBlank()) {
            settingsStore.update { it.copy(mcpServerToken = generateMcpToken()) }
        }
    }

    val permissionState = rememberPermissionState(
        permissions = buildSet {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(PermissionNotification)
            }
            if (Build.VERSION.SDK_INT >= 37 && !settings.mcpServerLocalhostOnly) {
                add(PermissionLocalNetwork)
            }
        },
    )
    PermissionManager(permissionState = permissionState)

    var pendingStart by remember { mutableStateOf(false) }

    fun startMcpServer() {
        val intent = Intent(context, McpServerService::class.java).apply {
            action = McpServerService.ACTION_START
            putExtra(McpServerService.EXTRA_PORT, settings.mcpServerPort)
            putExtra(McpServerService.EXTRA_LOCALHOST_ONLY, settings.mcpServerLocalhostOnly)
        }
        context.startForegroundService(intent)
        scope.launch {
            settingsStore.update {
                it.copy(
                    mcpServerEnabled = true,
                    // never start auth-enabled with a blank token
                    mcpServerToken = it.mcpServerToken.ifBlank { generateMcpToken() },
                )
            }
        }
    }

    LaunchedEffect(permissionState.allPermissionsGranted) {
        if (pendingStart && permissionState.allPermissionsGranted) {
            pendingStart = false
            startMcpServer()
        }
    }

    fun copyText(text: String) {
        clipboardManager.setText(AnnotatedString(text))
        toaster.show(copiedText)
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_page_mcp_server)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    if (serverState.isLoading) return@ExtendedFloatingActionButton
                    if (!serverState.isRunning) {
                        if (permissionState.allPermissionsGranted) {
                            startMcpServer()
                        } else {
                            pendingStart = true
                            permissionState.requestPermissions()
                        }
                    } else {
                        val intent = Intent(context, McpServerService::class.java).apply {
                            action = McpServerService.ACTION_STOP
                        }
                        context.startService(intent)
                        scope.launch {
                            settingsStore.update { it.copy(mcpServerEnabled = false) }
                        }
                    }
                },
                icon = {
                    if (serverState.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 3.dp,
                        )
                    } else {
                        Icon(
                            imageVector = if (serverState.isRunning) HugeIcons.Stop else HugeIcons.Play,
                            contentDescription = null,
                        )
                    }
                },
                text = {
                    Text(
                        if (serverState.isRunning) {
                            stringResource(R.string.setting_page_mcp_server_stop)
                        } else {
                            stringResource(R.string.setting_page_mcp_server_start)
                        }
                    )
                },
                containerColor = if (serverState.isRunning) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.primaryContainer
                },
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CardGroup(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_mcp_server_port)) },
                        supportingContent = { Text(stringResource(R.string.setting_page_mcp_server_port_desc)) },
                        trailingContent = {
                            TextField(
                                value = portText,
                                onValueChange = { value ->
                                    portText = value.filter { it.isDigit() }
                                    val port = portText.toIntOrNull()
                                    if (port != null && port in 1024..65535) {
                                        scope.launch {
                                            settingsStore.update { it.copy(mcpServerPort = port) }
                                        }
                                    }
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                isError = portText.toIntOrNull()?.let { it !in 1024..65535 } ?: true,
                                modifier = Modifier.width(100.dp),
                                enabled = !serverState.isRunning,
                                shape = CircleShape,
                                colors = TextFieldDefaults.colors(
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    errorIndicatorColor = Color.Transparent,
                                    disabledIndicatorColor = Color.Transparent
                                )
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_mcp_server_localhost_only)) },
                        supportingContent = { Text(stringResource(R.string.setting_page_mcp_server_localhost_only_desc)) },
                        trailingContent = {
                            Switch(
                                checked = settings.mcpServerLocalhostOnly,
                                onCheckedChange = { checked ->
                                    scope.launch {
                                        settingsStore.update { it.copy(mcpServerLocalhostOnly = checked) }
                                    }
                                },
                                // Requires a service restart to take effect.
                                enabled = !serverState.isRunning,
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_mcp_server_auth_enable)) },
                        supportingContent = { Text(stringResource(R.string.setting_page_mcp_server_auth_enable_desc)) },
                        trailingContent = {
                            Switch(
                                checked = settings.mcpServerAuthEnabled,
                                onCheckedChange = { checked ->
                                    scope.launch {
                                        settingsStore.update {
                                            it.copy(
                                                mcpServerAuthEnabled = checked,
                                                mcpServerToken = it.mcpServerToken.ifBlank { generateMcpToken() },
                                            )
                                        }
                                    }
                                },
                                enabled = !serverState.isRunning,
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_mcp_server_token)) },
                        supportingContent = { Text(stringResource(R.string.setting_page_mcp_server_token_desc)) },
                        trailingContent = {
                            TextField(
                                value = settings.mcpServerToken,
                                onValueChange = {},
                                readOnly = true,
                                visualTransformation = if (tokenVisible) {
                                    VisualTransformation.None
                                } else {
                                    PasswordVisualTransformation()
                                },
                                trailingIcon = {
                                    IconButton(onClick = { tokenVisible = !tokenVisible }) {
                                        Icon(
                                            imageVector = if (tokenVisible) HugeIcons.ViewOff else HugeIcons.View,
                                            contentDescription = null
                                        )
                                    }
                                },
                                singleLine = true,
                                modifier = Modifier.width(180.dp),
                                shape = CircleShape,
                                colors = TextFieldDefaults.colors(
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    errorIndicatorColor = Color.Transparent,
                                    disabledIndicatorColor = Color.Transparent
                                )
                            )
                        },
                    )
                    item(
                        onClick = {
                            // Takes effect on next (re)start; ignore taps while running.
                            if (serverState.isRunning) return@item
                            scope.launch {
                                settingsStore.update { it.copy(mcpServerToken = generateMcpToken()) }
                            }
                        },
                        headlineContent = { Text(stringResource(R.string.setting_page_mcp_server_token_regenerate)) },
                        supportingContent = { Text(stringResource(R.string.setting_page_mcp_server_token_regenerate_desc)) },
                    )
                    item(
                        onClick = { copyText(settings.mcpServerToken) },
                        headlineContent = { Text(stringResource(R.string.setting_page_mcp_server_token_copy)) },
                        supportingContent = { Text(stringResource(R.string.setting_page_mcp_server_token_copy_desc)) },
                    )

                    if (serverState.isRunning) {
                        val port = serverState.port
                        if (!serverState.localhostOnly) {
                            val lanUrl = "http://${serverState.address ?: "localhost"}:$port/mcp"
                            item(
                                onClick = { copyText(lanUrl) },
                                headlineContent = { Text(stringResource(R.string.setting_page_mcp_server_lan_address)) },
                                supportingContent = { Text(lanUrl) },
                            )

                            if (serverState.hostname != null) {
                                val mdnsUrl = "http://${serverState.hostname}:$port/mcp"
                                item(
                                    onClick = { copyText(mdnsUrl) },
                                    headlineContent = { Text(stringResource(R.string.setting_page_mcp_server_mdns_address)) },
                                    supportingContent = { Text(mdnsUrl) },
                                )
                            }
                        }

                        val localUrl = "http://localhost:$port/mcp"
                        item(
                            onClick = { copyText(localUrl) },
                            headlineContent = { Text(stringResource(R.string.setting_page_mcp_server_local_address)) },
                            supportingContent = { Text(localUrl) },
                        )
                    }

                    item(
                        headlineContent = {
                            Text(
                                text = stringResource(R.string.setting_page_mcp_server_address_note),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        },
                        supportingContent = {
                            Text(
                                text = stringResource(R.string.setting_page_mcp_server_address_note_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                    item(
                        headlineContent = {
                            Text(
                                text = stringResource(R.string.setting_page_mcp_server_approval_note),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        },
                        supportingContent = {
                            Text(
                                text = stringResource(R.string.setting_page_mcp_server_approval_note_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                    if (serverState.error != null) {
                        item(
                            headlineContent = {
                                Text(
                                    text = stringResource(R.string.setting_page_mcp_server_error),
                                    color = MaterialTheme.colorScheme.error
                                )
                            },
                            supportingContent = {
                                Text(
                                    text = serverState.error ?: "",
                                    color = MaterialTheme.colorScheme.error
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}
