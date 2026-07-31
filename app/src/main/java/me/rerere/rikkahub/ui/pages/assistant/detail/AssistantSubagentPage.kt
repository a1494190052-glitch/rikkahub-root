package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.nestedScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.rikkahub.data.ai.subagent.SubagentProfile
import me.rerere.rikkahub.data.ai.subagent.mergeSubagentProfiles
import me.rerere.rikkahub.data.ai.subagent.removeSubagentProfile
import me.rerere.rikkahub.data.ai.subagent.upsertSubagentProfile
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.theme.LargeFlexibleTopAppBar
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/** 子代理 Profile 管理页：查看/启用内置 profile，创建/编辑/删除自定义 profile */
@Composable
fun AssistantSubagentPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(parameters = { parametersOf(id) })
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var editing by remember { mutableStateOf<SubagentProfile?>(null) }
    var creating by remember { mutableStateOf(false) }

    val customNames = remember(assistant) { assistant.subagentProfiles.map { it.name }.toSet() }
    val merged = remember(assistant) {
        mergeSubagentProfiles(assistant.subagentProfiles, assistant.disabledBuiltinSubagents)
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("子代理 Profiles") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = true }) {
                Icon(HugeIcons.Add01, null)
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    text = "子代理是可并行/嵌套派发的独立 AI 助手。内置 profile 可开关，自定义 profile 可编辑参数。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }
            item {
                Text(
                    text = "自定义",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                )
            }
            if (customNames.isEmpty()) {
                item {
                    Text(
                        text = "暂无自定义 profile，点右下角 + 创建",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            } else {
                merged.filter { it.name in customNames }.forEach { profile ->
                    item(key = "custom_${profile.name}") {
                        ProfileCard(
                            profile = profile,
                            isBuiltin = false,
                            enabled = true,
                            onEdit = { editing = profile },
                            onDelete = {
                                vm.update(
                                    assistant.copy(
                                        subagentProfiles = removeSubagentProfile(assistant.subagentProfiles, profile.name)
                                    )
                                )
                            },
                        )
                    }
                }
            }
            item {
                Text(
                    text = "内置",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 4.dp, top = 12.dp),
                )
            }
            SubagentProfile.BUILTIN.forEach { builtin ->
                val enabled = builtin.name !in assistant.disabledBuiltinSubagents
                val effective = merged.find { it.name == builtin.name } ?: builtin
                item(key = "builtin_${builtin.name}") {
                    ProfileCard(
                        profile = effective,
                        isBuiltin = true,
                        enabled = enabled,
                        onToggle = { on ->
                            val newDisabled = if (on) {
                                assistant.disabledBuiltinSubagents - builtin.name
                            } else {
                                assistant.disabledBuiltinSubagents + builtin.name
                            }
                            vm.update(assistant.copy(disabledBuiltinSubagents = newDisabled))
                        },
                    )
                }
            }
            item { Spacer(Modifier.height(72.dp)) }
        }
    }

    if (creating || editing != null) {
        val initial = editing
        ProfileEditDialog(
            isNew = creating,
            initialName = initial?.name ?: "",
            initialDisplayName = initial?.displayName ?: "",
            initialDescription = initial?.description ?: "",
            initialSystemPrompt = initial?.systemPrompt ?: "",
            initialTemperature = initial?.temperature?.toString() ?: "",
            initialMaxSteps = initial?.maxSteps?.toString() ?: "32",
            initialTimeoutSeconds = initial?.timeoutSeconds?.toString() ?: "600",
            initialMaxTotalTokens = initial?.maxTotalTokens?.takeIf { it > 0 }?.toString() ?: "",
            initialOutputSchema = initial?.outputSchema ?: "",
            initialAllowHostShellWrite = initial?.allowHostShellWrite == true,
            onDismiss = { creating = false; editing = null },
            onSave = { profile ->
                vm.update(
                    assistant.copy(
                        subagentProfiles = upsertSubagentProfile(assistant.subagentProfiles, profile)
                    )
                )
                creating = false
                editing = null
            },
        )
    }
}

@Composable
private fun ProfileCard(
    profile: SubagentProfile,
    isBuiltin: Boolean,
    enabled: Boolean,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onToggle: ((Boolean) -> Unit)? = null,
) {
    CardGroup {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = profile.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    if (isBuiltin) {
                        Text(
                            text = "内置",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    if (profile.allowHostShellWrite) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "⚠️ 高危",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Text(
                    text = "@${profile.name}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (profile.description.isNotBlank()) {
                    Text(
                        text = profile.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = buildString {
                        append("步数上限 ${profile.maxSteps}")
                        if (profile.timeoutSeconds > 0) append(" · 超时 ${profile.timeoutSeconds}s")
                        if (profile.maxTotalTokens > 0) append(" · ${profile.maxTotalTokens / 1000}k token")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    if (isBuiltin && onToggle != null) {
                        Switch(checked = enabled, onCheckedChange = onToggle)
                    } else {
                        if (onEdit != null) {
                            IconButton(onClick = onEdit) { Icon(HugeIcons.Edit01, "编辑") }
                        }
                        if (onDelete != null) {
                            IconButton(onClick = onDelete) { Icon(HugeIcons.Delete01, "删除") }
                        }
                    }
                }
            }
        }
    }
}

/** 编辑/新建表单（字段级状态，避免 SubagentProfile init 校验在中间态抛错） */
@Composable
private fun ProfileEditDialog(
    isNew: Boolean,
    initialName: String,
    initialDisplayName: String,
    initialDescription: String,
    initialSystemPrompt: String,
    initialTemperature: String,
    initialMaxSteps: String,
    initialTimeoutSeconds: String,
    initialMaxTotalTokens: String,
    initialOutputSchema: String,
    initialAllowHostShellWrite: Boolean,
    onDismiss: () -> Unit,
    onSave: (SubagentProfile) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var displayName by remember { mutableStateOf(initialDisplayName) }
    var description by remember { mutableStateOf(initialDescription) }
    var systemPrompt by remember { mutableStateOf(initialSystemPrompt) }
    var temperature by remember { mutableStateOf(initialTemperature) }
    var maxSteps by remember { mutableStateOf(initialMaxSteps) }
    var timeoutSeconds by remember { mutableStateOf(initialTimeoutSeconds) }
    var maxTotalTokens by remember { mutableStateOf(initialMaxTotalTokens) }
    var outputSchema by remember { mutableStateOf(initialOutputSchema) }
    var allowHostShellWrite by remember { mutableStateOf(initialAllowHostShellWrite) }
    var error by remember { mutableStateOf<String?>(null) }

    fun buildProfile(): SubagentProfile? {
        val trimmedName = name.trim()
        if (!SubagentProfile.IdentifierRegex.matches(trimmedName)) {
            error = "名称须为小写字母开头，可含数字/下划线（如 my_agent）"
            return null
        }
        return SubagentProfile(
            name = trimmedName,
            displayName = displayName.trim().ifBlank { trimmedName },
            description = description.trim(),
            systemPrompt = systemPrompt.trim(),
            temperature = temperature.trim().toFloatOrNull(),
            maxSteps = maxSteps.trim().toIntOrNull() ?: 32,
            timeoutSeconds = timeoutSeconds.trim().toIntOrNull() ?: 600,
            maxTotalTokens = maxTotalTokens.trim().toIntOrNull() ?: 0,
            outputSchema = outputSchema.trim(),
            allowHostShellWrite = allowHostShellWrite,
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "新建子代理 Profile" else "编辑 Profile") },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .height(480.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    label = { Text("名称 (唯一标识)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("显示名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("描述（给主 AI 看的用途说明）") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    label = { Text("系统提示词") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = temperature,
                        onValueChange = { temperature = it },
                        label = { Text("temperature (可选)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = maxSteps,
                        onValueChange = { maxSteps = it },
                        label = { Text("步数上限") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = timeoutSeconds,
                        onValueChange = { timeoutSeconds = it },
                        label = { Text("超时(秒)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = maxTotalTokens,
                        onValueChange = { maxTotalTokens = it },
                        label = { Text("token 预算(0=不限)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = outputSchema,
                    onValueChange = { outputSchema = it },
                    label = { Text("输出 JSON Schema (可选)") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("允许自动执行 root 写命令", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "⚠️ 高危：开启后子代理无需审批即可执行 root 写操作",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Switch(checked = allowHostShellWrite, onCheckedChange = { allowHostShellWrite = it })
                }
                if (error != null) {
                    Text(error!!, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { buildProfile()?.let(onSave) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
