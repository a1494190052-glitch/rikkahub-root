package me.rerere.rikkahub.ui.pages.extensions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.ScheduledTaskEntity
import me.rerere.rikkahub.service.scheduler.TaskScheduler
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.uuid.Uuid

@Composable
fun ScheduledTasksPage(vm: ScheduledTasksVM = koinViewModel()) {
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var showCreateDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.scheduled_tasks_page_title)) },
                navigationIcon = { BackButton() },
            )
        },
        floatingActionButton = {
            OutlinedButton(onClick = { showCreateDialog = true }) {
                Icon(HugeIcons.Add01, null)
                Text(stringResource(R.string.scheduled_tasks_new))
            }
        },
    ) { innerPadding ->
        if (tasks.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding).padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.scheduled_tasks_empty),
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
                items(tasks, key = { it.id }) { task ->
                    ScheduledTaskCard(
                        task = task,
                        assistantName = settings.assistants.find { it.id.toString() == task.assistantId }?.name ?: "",
                        onToggle = { vm.setEnabled(task.id, it) },
                        onDelete = { vm.delete(task.id) },
                    )
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateScheduledTaskDialog(
            assistants = settings.assistants,
            onDismiss = { showCreateDialog = false },
            onCreate = {
                vm.create(it)
                showCreateDialog = false
            },
        )
    }
}

@Composable
private fun ScheduledTaskCard(
    task: ScheduledTaskEntity,
    assistantName: String,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(task.title, style = MaterialTheme.typography.titleSmall)
                Text(
                    buildString {
                        append(assistantName)
                        append(" · ")
                        append(
                            when (task.type) {
                                ScheduledTaskEntity.TYPE_DAILY -> stringResource(
                                    R.string.scheduled_tasks_type_daily
                                ) + " %02d:%02d".format(task.timeMinutes / 60, task.timeMinutes % 60)

                                ScheduledTaskEntity.TYPE_WEEKLY -> stringResource(
                                    R.string.scheduled_tasks_type_weekly
                                ) + " (${task.weekDays}) %02d:%02d".format(task.timeMinutes / 60, task.timeMinutes % 60)

                                ScheduledTaskEntity.TYPE_INTERVAL -> stringResource(
                                    R.string.scheduled_tasks_type_interval
                                ) + " ${task.intervalMinutes}min"

                                else -> task.type
                            }
                        )
                        val next = TaskScheduler.computeNextTrigger(task)
                        if (next != null && task.enabled) {
                            append(" · ")
                            append(stringResource(R.string.scheduled_tasks_next))
                            append(" ")
                            append(SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(next)))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = task.enabled, onCheckedChange = onToggle)
            IconButton(onClick = onDelete) {
                Icon(HugeIcons.Delete02, null, tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun CreateScheduledTaskDialog(
    assistants: List<me.rerere.rikkahub.data.model.Assistant>,
    onDismiss: () -> Unit,
    onCreate: (ScheduledTaskEntity) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(ScheduledTaskEntity.TYPE_DAILY) }
    var actionType by remember { mutableStateOf(ScheduledTaskEntity.ACTION_LLM) }
    var time by remember { mutableStateOf("08:00") }
    var weekdays by remember { mutableStateOf("1,2,3,4,5") }
    var interval by remember { mutableStateOf("60") }
    var assistantId by remember { mutableStateOf(assistants.firstOrNull()?.id?.toString() ?: "") }
    var assistantMenuExpanded by remember { mutableStateOf(false) }

    // 格式 + 范围双校验: \d{1,2}:\d{2} 会放过 99:99, Calendar lenient 会把 99 小时溢出到数天后
    val timeValid = time.matches(Regex("""\d{1,2}:\d{2}""")) && time.split(':').let {
        (it.getOrNull(0)?.toIntOrNull() ?: -1) in 0..23 && (it.getOrNull(1)?.toIntOrNull() ?: -1) in 0..59
    }
    val intervalValid = interval.toIntOrNull()?.let { it >= 15 } == true
    val valid = title.isNotBlank() && prompt.isNotBlank() && assistantId.isNotBlank() &&
            when (type) {
                ScheduledTaskEntity.TYPE_DAILY, ScheduledTaskEntity.TYPE_WEEKLY -> timeValid
                ScheduledTaskEntity.TYPE_INTERVAL -> intervalValid
                else -> true
            }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scheduled_tasks_new)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title, onValueChange = { title = it },
                    label = { Text(stringResource(R.string.scheduled_tasks_field_title)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                // 动作类型: LLM 提示词 / Shell 命令
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        ScheduledTaskEntity.ACTION_LLM to R.string.scheduled_tasks_action_llm,
                        ScheduledTaskEntity.ACTION_SHELL to R.string.scheduled_tasks_action_shell,
                    ).forEach { (a, label) ->
                        FilterChip(
                            selected = actionType == a,
                            onClick = { actionType = a },
                            label = { Text(stringResource(label)) },
                        )
                    }
                }
                OutlinedTextField(
                    value = prompt, onValueChange = { prompt = it },
                    label = {
                        Text(
                            stringResource(
                                if (actionType == ScheduledTaskEntity.ACTION_SHELL) {
                                    R.string.scheduled_tasks_field_shell_command
                                } else {
                                    R.string.scheduled_tasks_field_prompt
                                }
                            )
                        )
                    },
                    minLines = 2, modifier = Modifier.fillMaxWidth(),
                )
                // 助手选择
                Box {
                    OutlinedButton(onClick = { assistantMenuExpanded = true }) {
                        Text(
                            assistants.find { it.id.toString() == assistantId }?.name
                                ?: stringResource(R.string.scheduled_tasks_field_assistant)
                        )
                    }
                    DropdownMenu(
                        expanded = assistantMenuExpanded,
                        onDismissRequest = { assistantMenuExpanded = false },
                    ) {
                        assistants.forEach { a ->
                            DropdownMenuItem(
                                text = { Text(a.name) },
                                onClick = {
                                    assistantId = a.id.toString()
                                    assistantMenuExpanded = false
                                },
                            )
                        }
                    }
                }
                // 类型选择
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        ScheduledTaskEntity.TYPE_DAILY to R.string.scheduled_tasks_type_daily,
                        ScheduledTaskEntity.TYPE_WEEKLY to R.string.scheduled_tasks_type_weekly,
                        ScheduledTaskEntity.TYPE_INTERVAL to R.string.scheduled_tasks_type_interval,
                    ).forEach { (t, label) ->
                        FilterChip(
                            selected = type == t,
                            onClick = { type = t },
                            label = { Text(stringResource(label)) },
                        )
                    }
                }
                if (type == ScheduledTaskEntity.TYPE_DAILY || type == ScheduledTaskEntity.TYPE_WEEKLY) {
                    OutlinedTextField(
                        value = time, onValueChange = { time = it },
                        label = { Text(stringResource(R.string.scheduled_tasks_field_time)) },
                        singleLine = true, isError = !timeValid, modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (type == ScheduledTaskEntity.TYPE_WEEKLY) {
                    OutlinedTextField(
                        value = weekdays, onValueChange = { weekdays = it },
                        label = { Text(stringResource(R.string.scheduled_tasks_field_weekdays)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (type == ScheduledTaskEntity.TYPE_INTERVAL) {
                    OutlinedTextField(
                        value = interval, onValueChange = { interval = it },
                        label = { Text(stringResource(R.string.scheduled_tasks_field_interval)) },
                        singleLine = true, isError = !intervalValid, modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    // 范围兜底: 即使 timeValid 误判, 入库的 timeMinutes 也必须落在 0..1439
                    val (h, m) = time.split(':').let {
                        ((it.getOrNull(0)?.toIntOrNull() ?: 8).coerceIn(0, 23)) to
                            ((it.getOrNull(1)?.toIntOrNull() ?: 0).coerceIn(0, 59))
                    }
                    onCreate(
                        ScheduledTaskEntity(
                            id = Uuid.random().toString(),
                            assistantId = assistantId,
                            title = title.trim(),
                            prompt = prompt.trim(),
                            type = type,
                            timeMinutes = h * 60 + m,
                            weekDays = weekdays.trim(),
                            intervalMinutes = interval.toIntOrNull() ?: 60,
                            actionType = actionType,
                        )
                    )
                },
            ) { Text(stringResource(R.string.scheduled_tasks_create)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
