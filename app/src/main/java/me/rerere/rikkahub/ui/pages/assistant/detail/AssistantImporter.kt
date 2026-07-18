package me.rerere.rikkahub.ui.pages.assistant.detail

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import com.dokar.sonner.ToasterState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.export.CharacterCard
import me.rerere.rikkahub.data.export.CharacterCardParser
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.context.LocalToaster
import org.koin.compose.koinInject

@Composable
fun AssistantImporter(
    modifier: Modifier = Modifier,
    onUpdate: (Assistant) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        SillyTavernImporter(onImport = onUpdate)
    }
}

/**
 * 待确认的导入: 解析出的角色卡 + 聊天背景
 */
private class PendingImport(
    val card: CharacterCard,
    val background: String?,
)

@Composable
private fun SillyTavernImporter(
    onImport: (Assistant) -> Unit
) {
    val context = LocalContext.current
    val filesManager: FilesManager = koinInject()
    val settingsStore: SettingsStore = koinInject()
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    var isLoading by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<PendingImport?>(null) }

    fun handleUri(uri: Uri) {
        isLoading = true
        scope.launch {
            try {
                pendingImport = parseCardFromUri(context, uri, filesManager)
            } catch (exception: Exception) {
                exception.printStackTrace()
                toaster.show(
                    message = exception.message
                        ?: context.getString(R.string.assistant_importer_import_failed),
                    type = ToastType.Error
                )
            } finally {
                isLoading = false
            }
        }
    }

    val pngPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { handleUri(it) } }

    val jsonPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { handleUri(it) } }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = {
                pngPickerLauncher.launch(arrayOf("image/png"))
            },
            enabled = !isLoading
        ) {
            AutoAIIcon(name = "tavern", modifier = Modifier.padding(end = 8.dp))
            Text(text = if (isLoading) stringResource(R.string.assistant_importer_importing) else stringResource(R.string.assistant_importer_import_tavern_png))
        }

        OutlinedButton(
            onClick = {
                jsonPickerLauncher.launch(
                    arrayOf(
                        "application/json",
                        "application/zip",
                        "application/x-zip-compressed",
                        "application/octet-stream",
                    )
                )
            },
            enabled = !isLoading
        ) {
            AutoAIIcon(name = "tavern", modifier = Modifier.padding(end = 8.dp))
            Text(text = if (isLoading) stringResource(R.string.assistant_importer_importing) else stringResource(R.string.assistant_importer_import_tavern_json))
        }
    }

    // 预览确认对话框
    pendingImport?.let { pending ->
        CharacterCardPreviewDialog(
            pending = pending,
            onDismiss = { pendingImport = null },
            onConfirm = { greetingIndex ->
                pendingImport = null
                scope.launch {
                    try {
                        // 内嵌世界书 + post_history_instructions 落库
                        val lorebook = pending.card.toLorebook()
                        val lorebookIds = if (lorebook != null) {
                            settingsStore.update { settings ->
                                settings.copy(lorebooks = settings.lorebooks + lorebook)
                            }
                            setOf(lorebook.id)
                        } else {
                            emptySet()
                        }
                        onImport(
                            pending.card.toAssistant(
                                greetingIndex = greetingIndex,
                                background = pending.background,
                                lorebookIds = lorebookIds,
                            )
                        )
                        toaster.show(context.getString(R.string.assistant_importer_import_success))
                    } catch (exception: Exception) {
                        exception.printStackTrace()
                        toaster.show(
                            message = exception.message
                                ?: context.getString(R.string.assistant_importer_import_failed),
                            type = ToastType.Error
                        )
                    }
                }
            }
        )
    }
}

@Composable
private fun CharacterCardPreviewDialog(
    pending: PendingImport,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val card = pending.card
    var selectedGreeting by remember { mutableIntStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.assistant_importer_preview_title))
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                // 名称 / 作者 / 版本
                Text(card.name, style = MaterialTheme.typography.titleMedium)
                val metaLine = buildList {
                    if (card.creator.isNotBlank()) add(card.creator)
                    if (card.characterVersion.isNotBlank()) add("v${card.characterVersion}")
                }.joinToString(" · ")
                if (metaLine.isNotBlank()) {
                    Text(
                        metaLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 标签
                if (card.tags.isNotEmpty()) {
                    Text(
                        card.tags.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // 描述预览
                if (card.description.isNotBlank()) {
                    Text(
                        card.description.trim(),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // 世界书条目数
                if (card.lorebookEntries.isNotEmpty()) {
                    Text(
                        stringResource(
                            R.string.assistant_importer_lorebook_entries,
                            card.lorebookEntries.size
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 正则脚本数
                if (card.regexes.isNotEmpty()) {
                    Text(
                        stringResource(
                            R.string.assistant_importer_regex_scripts,
                            card.regexes.size
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 开场白选择
                if (card.greetings.isNotEmpty()) {
                    Text(
                        stringResource(R.string.assistant_importer_select_greeting),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    card.greetings.forEachIndexed { index, greeting ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selectedGreeting == index,
                                    onClick = { selectedGreeting = index }
                                )
                        ) {
                            RadioButton(
                                selected = selectedGreeting == index,
                                onClick = { selectedGreeting = index }
                            )
                            Text(
                                greeting.trim(),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.heightIn(min = 32.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selectedGreeting) }) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

/**
 * 从 Uri 解析角色卡 (PNG / JSON / CHARX/ZIP 卡包)
 */
private suspend fun parseCardFromUri(
    context: Context,
    uri: Uri,
    filesManager: FilesManager,
): PendingImport = withContext(Dispatchers.IO) {
    val mime = filesManager.getFileMimeType(uri)
    when {
        mime == "image/png" -> {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error(context.getString(R.string.assistant_importer_read_json_failed))
            val card = CharacterCardParser.parsePng(bytes).getOrThrow()
            // PNG 同时作为聊天背景
            val background = filesManager.createChatFilesByContents(listOf(uri)).first().toString()
            PendingImport(card, background)
        }

        mime == "application/json" -> {
            val json = context.contentResolver.openInputStream(uri)?.bufferedReader()
                .use { it?.readText() }
                ?: error(context.getString(R.string.assistant_importer_read_json_failed))
            val card = CharacterCardParser.parseJson(json).getOrThrow()
            PendingImport(card, null)
        }

        // CHARX / ZIP 卡包 (或未知类型, 按 zip 尝试)
        else -> parseCharx(context, uri)
    }
}

private const val MAX_ZIP_ENTRY_SIZE = 64 * 1024 * 1024 // 防 zip bomb

/**
 * 解析 CHARX/ZIP 卡包
 *
 * 规范: 根目录 card.json + 素材; 兼容包内直接放 PNG 卡
 */
private fun parseCharx(context: Context, uri: Uri): PendingImport {
    val input = context.contentResolver.openInputStream(uri)
        ?: error(context.getString(R.string.assistant_importer_read_json_failed))
    input.use { raw ->
        val zip = java.util.zip.ZipInputStream(raw.buffered())
        var entry = zip.nextEntry
        var cardJson: String? = null
        var pngBytes: ByteArray? = null
        while (entry != null) {
            if (!entry.isDirectory) {
                val name = entry.name.substringAfterLast('/')
                when {
                    // card.json 最优先
                    name.equals("card.json", ignoreCase = true) ->
                        if (cardJson == null) cardJson = readZipEntryText(zip)

                    // 宽松: 首个 json (部分打包工具命名不规范)
                    name.endsWith(".json", ignoreCase = true) && cardJson == null ->
                        cardJson = readZipEntryText(zip)

                    // 首个 PNG (卡面/内嵌卡)
                    name.endsWith(".png", ignoreCase = true) && pngBytes == null ->
                        pngBytes = readZipEntryBytes(zip)
                }
            }
            zip.closeEntry()
            entry = zip.nextEntry
        }
        // card.json 优先; 失败则回退 PNG 内嵌卡
        cardJson?.let { json ->
            CharacterCardParser.parseJson(json).getOrNull()?.let { card ->
                return PendingImport(card, null)
            }
        }
        pngBytes?.let { bytes ->
            CharacterCardParser.parsePng(bytes).getOrNull()?.let { card ->
                return PendingImport(card, null)
            }
        }
        error(context.getString(R.string.assistant_importer_no_card_in_archive))
    }
}

private fun readZipEntryText(zip: java.util.zip.ZipInputStream): String =
    String(readZipEntryBytes(zip), Charsets.UTF_8)

private fun readZipEntryBytes(zip: java.util.zip.ZipInputStream): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val count = zip.read(buffer)
        if (count < 0) break
        total += count
        if (total > MAX_ZIP_ENTRY_SIZE) error("Zip entry too large")
        out.write(buffer, 0, count)
    }
    return out.toByteArray()
}
