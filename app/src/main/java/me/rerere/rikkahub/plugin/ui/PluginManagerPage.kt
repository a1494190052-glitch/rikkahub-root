package me.rerere.rikkahub.plugin.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toFile
import me.rerere.rikkahub.plugin.model.PluginInfo
import org.koin.androidx.compose.koinViewModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginManagerPage(
    onNavigateBack: () -> Unit = {},
    viewModel: PluginViewModel = koinViewModel(),
) {
    val plugins by viewModel.plugins.collectAsState()
    val importState by viewModel.importState.collectAsState()
    val operationMessage by viewModel.operationMessage.collectAsState()
    val context = LocalContext.current

    var showDeleteDialog by remember { mutableStateOf<String?>(null) }
    var showConfigDialog by remember { mutableStateOf<String?>(null) }

    // File picker for importing plugins
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            // Copy to cache file for processing
            try {
                val inputStream = context.contentResolver.openInputStream(it)
                val cacheFile = File(context.cacheDir, "plugin_import_${System.currentTimeMillis()}.zip")
                cacheFile.outputStream().use { output ->
                    inputStream?.copyTo(output)
                }
                inputStream?.close()
                viewModel.importPlugin(cacheFile)
            } catch (e: Exception) {
                // Error handled by ViewModel
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Plugins") },
                navigationIcon = {
                    TextButton(onClick = onNavigateBack) {
                        Text("Back")
                    }
                },
                actions = {
                    TextButton(onClick = { viewModel.refresh() }) {
                        Text("Refresh")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { importLauncher.launch("application/zip") }
            ) {
                Text("+", style = MaterialTheme.typography.headlineSmall)
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Import progress indicator
            if (importState is PluginViewModel.ImportState.Importing) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Importing plugin...")
                }
            }

            // Operation message
            operationMessage?.let { message ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = message,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = { viewModel.clearMessage() }) {
                            Text("Dismiss")
                        }
                    }
                }
            }

            if (plugins.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = "No plugins installed",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Tap + to import a plugin from a zip file",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(plugins, key = { it.id }) { plugin ->
                        PluginCard(
                            plugin = plugin,
                            onToggle = { viewModel.togglePlugin(plugin.id) },
                            onConfigure = { showConfigDialog = plugin.id },
                            onDelete = { showDeleteDialog = plugin.id },
                        )
                    }
                }
            }
        }
    }

    // Delete confirmation dialog
    showDeleteDialog?.let { pluginId ->
        val plugin = plugins.find { it.id == pluginId }
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("Delete Plugin") },
            text = { Text("Are you sure you want to delete '${plugin?.name ?: pluginId}'? This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deletePlugin(pluginId)
                        showDeleteDialog = null
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Config dialog
    showConfigDialog?.let { pluginId ->
        val plugin = plugins.find { it.id == pluginId }
        if (plugin != null) {
            PluginConfigDialog(
                plugin = plugin,
                onDismiss = { showConfigDialog = null },
                onSave = { config ->
                    viewModel.updateConfig(pluginId, config)
                    showConfigDialog = null
                }
            )
        }
    }
}

@Composable
private fun PluginCard(
    plugin: PluginInfo,
    onToggle: () -> Unit,
    onConfigure: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = plugin.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "v${plugin.version}" + if (plugin.author.isNotBlank()) " by ${plugin.author}" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Switch(
                    checked = plugin.enabled,
                    onCheckedChange = { onToggle() },
                )
            }

            if (plugin.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = plugin.description,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Status indicators
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusChip(
                    text = if (plugin.loaded) "Loaded" else "Not loaded",
                    positive = plugin.loaded,
                )
                StatusChip(
                    text = "${plugin.toolCount} tools",
                    positive = plugin.toolCount > 0,
                )
            }

            // Expandable details
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide details" else "Show details")
            }

            AnimatedVisibility(visible = expanded) {
                Column {
                    if (plugin.toolNames.isNotEmpty()) {
                        Text(
                            text = "Tools:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        plugin.toolNames.forEach { toolName ->
                            Text(
                                text = "  - $toolName",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }

                    if (plugin.permissions.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Permissions:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        plugin.permissions.forEach { perm ->
                            Text(
                                text = "  - $perm",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (plugin.configValues.isNotEmpty()) {
                            TextButton(onClick = onConfigure) {
                                Text("Configure")
                            }
                        }
                        TextButton(onClick = onDelete) {
                            Text("Delete", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(text: String, positive: Boolean) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (positive) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun PluginConfigDialog(
    plugin: PluginInfo,
    onDismiss: () -> Unit,
    onSave: (Map<String, String>) -> Unit,
) {
    val configState = remember {
        mutableStateMapOf<String, String>().apply {
            putAll(plugin.configValues)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Configure: ${plugin.name}") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                configState.forEach { (key, value) ->
                    OutlinedTextField(
                        value = value,
                        onValueChange = { configState[key] = it },
                        label = { Text(key) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(configState.toMap()) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
