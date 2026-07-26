package me.rerere.rikkahub.ui.pages.extensions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.ShellAuditEntity
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ShellAuditPage(vm: ShellAuditVM = koinViewModel()) {
    val items by vm.items.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.shell_audit_page_title)) },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { vm.clearAll() }) {
                        Icon(HugeIcons.Delete02, stringResource(R.string.shell_audit_clear))
                    }
                },
            )
        },
    ) { innerPadding ->
        if (items.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding).padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.shell_audit_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = innerPadding + PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items, key = { it.id }) { entry ->
                    ShellAuditCard(
                        entry = entry,
                        onKill = { entry.taskId?.let(vm::killTask) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ShellAuditCard(
    entry: ShellAuditEntity,
    onKill: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusDot(entry.status)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        entry.command,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = if (expanded) 20 else 2,
                    )
                }
                if (entry.status == ShellAuditEntity.STATUS_RUNNING) {
                    if (entry.taskId != null) {
                        IconButton(onClick = onKill, modifier = Modifier.size(28.dp)) {
                            Icon(HugeIcons.Delete02, stringResource(R.string.shell_audit_kill), modifier = Modifier.size(18.dp))
                        }
                    } else {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    sourceLabel(entry.source),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    formatTime(entry.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                entry.durationMs?.let {
                    Text(
                        "${it}ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                entry.exitCode?.let {
                    Text(
                        "exit=$it",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (it == 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (expanded && !entry.outputPreview.isNullOrBlank()) {
                Text(
                    entry.outputPreview.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatusDot(status: String) {
    val color = when (status) {
        ShellAuditEntity.STATUS_RUNNING -> MaterialTheme.colorScheme.primary
        ShellAuditEntity.STATUS_DONE -> MaterialTheme.colorScheme.tertiary
        ShellAuditEntity.STATUS_BLOCKED -> MaterialTheme.colorScheme.error
        ShellAuditEntity.STATUS_TIMEOUT, ShellAuditEntity.STATUS_ERROR -> MaterialTheme.colorScheme.error
        ShellAuditEntity.STATUS_KILLED -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(modifier = Modifier.size(8.dp).background(color, CircleShape))
}

@Composable
private fun sourceLabel(source: String): String = when (source) {
    ShellAuditEntity.SOURCE_AI_WORKSPACE -> stringResource(R.string.shell_audit_source_workspace)
    ShellAuditEntity.SOURCE_AI_ROOT -> stringResource(R.string.shell_audit_source_root)
    ShellAuditEntity.SOURCE_AI_BACKGROUND -> stringResource(R.string.shell_audit_source_background)
    ShellAuditEntity.SOURCE_SCHEDULED_TASK -> stringResource(R.string.shell_audit_source_scheduled)
    else -> source
}

private fun formatTime(ts: Long): String =
    SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ts))
