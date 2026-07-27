package me.rerere.rikkahub.ui.pages.memory

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Refresh01
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 记忆浏览器页面
 * 显示所有记忆（按助手分组），支持语义搜索、编辑、删除、整合
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryBrowserPage(
    memoriesFlow: Flow<List<MemoryEntity>>,
    onBack: () -> Unit,
    onSearch: suspend (String, Int) -> List<Pair<MemoryEntity, Float>>,
    onUpdateMemory: suspend (Int, String) -> Unit,
    onDeleteMemory: suspend (Int) -> Unit,
    onConsolidate: suspend (String) -> Int,
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val memories by memoriesFlow.collectAsState(initial = emptyList())

    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<Pair<MemoryEntity, Float>>?>(null) }
    var isSearching by remember { mutableStateOf(false) }
    var editingMemory by remember { mutableStateOf<MemoryEntity?>(null) }
    var editContent by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<MemoryEntity?>(null) }
    var isConsolidating by remember { mutableStateOf(false) }

    // 按 assistantId 分组
    val groupedMemories = memories.groupBy { it.assistantId }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Memory Browser") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(HugeIcons.ArrowLeft01, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            scope.launch {
                                isConsolidating = true
                                try {
                                    val totalConsolidated = groupedMemories.keys.sumOf { assistantId ->
                                        onConsolidate(assistantId)
                                    }
                                    snackbarHostState.showSnackbar(
                                        "Consolidated $totalConsolidated memories"
                                    )
                                } catch (e: Exception) {
                                    snackbarHostState.showSnackbar("Consolidation failed: ${e.message}")
                                } finally {
                                    isConsolidating = false
                                }
                            }
                        },
                        enabled = !isConsolidating
                    ) {
                        if (isConsolidating) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(HugeIcons.Refresh01, contentDescription = "Consolidate")
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // 搜索栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Semantic search memories...") },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                if (searchQuery.isBlank()) {
                                    searchResults = null
                                    return@IconButton
                                }
                                scope.launch {
                                    isSearching = true
                                    try {
                                        searchResults = onSearch(searchQuery, 10)
                                    } catch (e: Exception) {
                                        snackbarHostState.showSnackbar("Search failed: ${e.message}")
                                    } finally {
                                        isSearching = false
                                    }
                                }
                            },
                            enabled = !isSearching
                        ) {
                            if (isSearching) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(HugeIcons.Search01, contentDescription = "Search")
                            }
                        }
                    }
                )
            }

            // 搜索结果或分组列表
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val displayResults = searchResults
                if (displayResults != null) {
                    // 搜索结果模式
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Search Results (${displayResults.size})",
                                style = MaterialTheme.typography.titleMedium
                            )
                            TextButton(onClick = { searchResults = null }) {
                                Text("Clear")
                            }
                        }
                    }
                    items(displayResults, key = { "search_${it.first.id}" }) { (memory, score) ->
                        MemoryCard(
                            memory = memory,
                            score = score,
                            onEdit = {
                                editingMemory = memory
                                editContent = memory.content
                            },
                            onDelete = { deleteTarget = memory }
                        )
                    }
                } else {
                    // 分组浏览模式
                    groupedMemories.forEach { (assistantId, assistantMemories) ->
                        item(key = "header_$assistantId") {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = if (assistantId == "__global__") "🌐 Global" else "🤖 $assistantId",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                            Text(
                                text = "${assistantMemories.size} memories",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        items(assistantMemories, key = { "mem_${it.id}" }) { memory ->
                            MemoryCard(
                                memory = memory,
                                score = null,
                                onEdit = {
                                    editingMemory = memory
                                    editContent = memory.content
                                },
                                onDelete = { deleteTarget = memory }
                            )
                        }
                    }

                    if (memories.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "No memories yet",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 编辑对话框
    editingMemory?.let { memory ->
        AlertDialog(
            onDismissRequest = { editingMemory = null },
            title = { Text("Edit Memory #${memory.id}") },
            text = {
                OutlinedTextField(
                    value = editContent,
                    onValueChange = { editContent = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    maxLines = 10,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            try {
                                onUpdateMemory(memory.id, editContent)
                                snackbarHostState.showSnackbar("Memory updated")
                            } catch (e: Exception) {
                                snackbarHostState.showSnackbar("Update failed: ${e.message}")
                            }
                            editingMemory = null
                        }
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingMemory = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // 删除确认对话框
    deleteTarget?.let { memory ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete Memory") },
            text = { Text("Are you sure you want to delete this memory?\n\n\"${memory.content.take(100)}\"") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            try {
                                onDeleteMemory(memory.id)
                                snackbarHostState.showSnackbar("Memory deleted")
                            } catch (e: Exception) {
                                snackbarHostState.showSnackbar("Delete failed: ${e.message}")
                            }
                            deleteTarget = null
                        }
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun MemoryCard(
    memory: MemoryEntity,
    score: Float?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // 内容
            Text(
                text = memory.content,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 元数据行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 相似度分数
                    if (score != null) {
                        AssistChip(
                            onClick = {},
                            label = { Text("Score: ${"%.3f".format(score)}", style = MaterialTheme.typography.labelSmall) },
                        )
                    }

                    // 重要性
                    if (memory.importance > 0) {
                        AssistChip(
                            onClick = {},
                            label = { Text("★${memory.importance}", style = MaterialTheme.typography.labelSmall) },
                        )
                    }

                    // 来源
                    if (memory.source != "manual") {
                        AssistChip(
                            onClick = {},
                            label = { Text(memory.source, style = MaterialTheme.typography.labelSmall) },
                        )
                    }

                    // 嵌入状态
                    if (memory.embedding != null) {
                        AssistChip(
                            onClick = {},
                            label = { Text("🧬", style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }

                // 操作按钮
                Row {
                    IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                        Icon(
                            HugeIcons.Edit01,
                            contentDescription = "Edit",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(
                            HugeIcons.Delete01,
                            contentDescription = "Delete",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // 底部信息
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Created: ${dateFormat.format(Date(memory.createdAt))}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Accessed: ${memory.accessCount}x",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
