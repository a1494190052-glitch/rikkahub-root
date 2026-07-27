package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Bot
import me.rerere.rikkahub.data.ai.subagent.SubagentTranscriptStep
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull

/**
 * spawn_subagent 渲染器: 子代理任务卡片.
 * 折叠标题显示 profile 与状态; 摘要显示步骤数/工具调用数/最新动态;
 * 详情为完整 transcript 时间线 (推理 / 工具调用 / 文本).
 */
object SubagentToolUI : ToolUIRenderer {
    override val toolName: String = "spawn_subagent"

    private data class SubagentMeta(
        val profile: String?,
        val steps: Int,
        val streaming: Boolean,
        val succeeded: Boolean,
        val transcript: List<SubagentTranscriptStep>,
    )

    private fun metaOf(context: ToolUIContext): SubagentMeta? {
        val meta = context.tool.output.filterIsInstance<me.rerere.ai.ui.UIMessagePart.Text>()
            .firstOrNull()?.metadata ?: return null
        val transcript = meta["subagent_transcript"]?.let { el ->
            runCatching {
                JsonInstant.decodeFromJsonElement(ListSerializer(SubagentTranscriptStep.serializer()), el)
            }.getOrNull()
        }.orEmpty()
        if (transcript.isEmpty() && "subagent_profile" !in meta) return null
        return SubagentMeta(
            profile = meta["subagent_profile"]?.jsonPrimitiveOrNull?.contentOrNull,
            steps = meta["subagent_steps"]?.jsonPrimitiveOrNull?.intOrNull ?: transcript.size,
            streaming = meta["subagent_streaming"]?.jsonPrimitiveOrNull?.booleanOrNull == true,
            succeeded = meta["subagent_succeeded"]?.jsonPrimitiveOrNull?.booleanOrNull == true,
            transcript = transcript,
        )
    }

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Bot

    @Composable
    override fun title(context: ToolUIContext): String {
        val meta = remember(context) { metaOf(context) }
        val profile = meta?.profile
            ?: context.arguments.getStringContent("profile_name")
            ?: "subagent"
        val state = when {
            meta?.streaming == true -> " · 运行中"
            meta?.succeeded == true -> " · 完成"
            meta != null -> " · 失败"
            else -> ""
        }
        return "子代理 $profile$state"
    }

    override fun hasSummary(context: ToolUIContext): Boolean = metaOf(context) != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val meta = remember(context) { metaOf(context) } ?: return
        val toolCalls = remember(meta) { meta.transcript.count { it is SubagentTranscriptStep.ToolCall } }
        val lastActivity = remember(meta) {
            meta.transcript.lastOrNull()?.let { step ->
                when (step) {
                    is SubagentTranscriptStep.Text -> step.text
                    is SubagentTranscriptStep.ToolCall -> "→ ${step.name}"
                    is SubagentTranscriptStep.Reasoning -> step.text
                }
            }?.lineSequence()?.firstOrNull()?.take(80)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "${meta.steps} 步骤 · $toolCalls 次工具调用",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!lastActivity.isNullOrBlank()) {
                Text(
                    text = lastActivity,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val meta = remember(context) { metaOf(context) }
        if (meta == null) {
            DefaultToolPreview(context = context)
            return
        }
        Column(
            modifier = Modifier
                .fillMaxHeight(0.8f)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "子代理 ${meta.profile ?: ""} — ${if (meta.streaming) "运行中" else if (meta.succeeded) "已完成" else "失败"}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            meta.transcript.forEach { step ->
                when (step) {
                    is SubagentTranscriptStep.Reasoning -> Text(
                        text = step.text,
                        style = MaterialTheme.typography.bodySmall,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    is SubagentTranscriptStep.ToolCall -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "🔧 ${step.name}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (step.input.isNotBlank()) Text(
                            text = step.input.take(300),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (step.output.isNotBlank()) Text(
                            text = step.output.take(500),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 5,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    is SubagentTranscriptStep.Text -> Text(
                        text = step.text,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
