package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Bot
import me.rerere.rikkahub.data.ai.subagent.SubagentTranscriptStep
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull

/**
 * spawn_subagent 渲染器: 子代理任务卡片 (增强版).
 *
 * 设计目标: 让用户一眼看清每个子代理"是谁、在干嘛、进度如何、成功没"。
 *  - 档案配色: explore=蓝 🔍 / coder=绿 💻 / reviewer=琥珀 👁 / 其它=灰 🤖
 *  - 运行中: 实时计时器 + 呼吸状态点 + 不定进度条 + 当前工具高亮
 *  - 完成后: 步骤/工具调用/耗时统计 + 可展开的完整 transcript 时间线
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

    /** 档案视觉风格: emoji 图标 + 主题色 */
    private data class ProfileStyle(val emoji: String, val color: Color, val label: String)

    private fun styleOf(profile: String?): ProfileStyle = when (profile) {
        "explore" -> ProfileStyle("🔍", Color(0xFF4285F4), "调研")
        "coder" -> ProfileStyle("💻", Color(0xFF34A853), "编码")
        "reviewer" -> ProfileStyle("👁", Color(0xFFF9AB00), "审查")
        else -> ProfileStyle("🤖", Color(0xFF9AA0A6), profile ?: "子代理")
    }

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

    /** 从 transcript 提取最近一次工具调用名 (用于"正在调用 xxx") */
    private fun lastToolName(transcript: List<SubagentTranscriptStep>): String? =
        transcript.lastOrNull { it is SubagentTranscriptStep.ToolCall }
            ?.let { (it as SubagentTranscriptStep.ToolCall).name }

    /** 格式化秒数为 mm:ss */
    private fun fmtDuration(sec: Int): String =
        if (sec < 60) "${sec}s" else "${sec / 60}m ${sec % 60}s"

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

    override fun hasSummary(context: ToolUIContext): Boolean = true

    @Composable
    override fun Summary(context: ToolUIContext) {
        val meta = remember(context) { metaOf(context) }
        val profile = meta?.profile ?: context.arguments.getStringContent("profile_name")
        val style = remember(profile) { styleOf(profile) }
        val task = remember(context) {
            context.arguments.getStringContent("description")
                ?.takeIf { it.isNotBlank() }
                ?: context.arguments.getStringContent("task")?.lineSequence()?.firstOrNull()?.take(60)
        }

        // 运行中实时计时
        var elapsed by remember { mutableIntStateOf(0) }
        val streaming = meta?.streaming == true
        LaunchedEffect(streaming) {
            if (streaming) while (true) { delay(1000); elapsed++ }
        }
        // 呼吸动画 (运行中的状态点)
        val transition = rememberInfiniteTransition(label = "subagent_pulse")
        val pulse by transition.animateFloat(
            initialValue = 0.25f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
            label = "pulse",
        )

        val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(surfaceVariant.copy(alpha = 0.5f))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 第一行: 图标 + 档案名 + 状态徽章
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(style.color.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) { Text(text = style.emoji, style = MaterialTheme.typography.bodyMedium) }
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${profile ?: "subagent"} · ${style.label}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = style.color,
                    )
                    if (!task.isNullOrBlank()) Text(
                        text = task,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                StatusBadge(streaming = streaming, succeeded = meta?.succeeded != false, pulse = pulse, elapsed = elapsed, hasMeta = meta != null)
            }

            // 进度区
            when {
                streaming -> {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = style.color,
                    )
                    val current = meta?.transcript?.let { lastToolName(it) }
                    ActivityLine(
                        leading = "▶",
                        text = if (current != null) "正在调用 $current" else "思考中…",
                        color = style.color,
                        steps = meta?.steps ?: 0,
                    )
                }
                meta != null -> {
                    val toolCalls = meta.transcript.count { it is SubagentTranscriptStep.ToolCall }
                    val last = lastToolName(meta.transcript)
                    ActivityLine(
                        leading = if (meta.succeeded) "✓" else "✗",
                        text = buildString {
                            append("${meta.steps} 步骤 · $toolCalls 次工具调用")
                            if (last != null) append(" · 最后 → $last")
                        },
                        color = if (meta.succeeded) Color(0xFF34A853) else MaterialTheme.colorScheme.error,
                        steps = null,
                    )
                }
            }
        }
    }

    /** 右上角状态徽章: 运行中(呼吸点+计时) / 完成 / 失败 */
    @Composable
    private fun StatusBadge(streaming: Boolean, succeeded: Boolean, pulse: Float, elapsed: Int, hasMeta: Boolean) {
        val (dotColor, label) = when {
            streaming -> Color(0xFF4285F4) to fmtDuration(elapsed)
            !hasMeta -> Color(0xFF9AA0A6) to "等待"
            succeeded -> Color(0xFF34A853) to "完成"
            else -> Color(0xFFEA4335) to "失败"
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (streaming) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(dotColor.copy(alpha = pulse))
                )
                Spacer(Modifier.width(5.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = dotColor,
            )
        }
    }

    /** 单行活动信息 */
    @Composable
    private fun ActivityLine(leading: String, text: String, color: Color, steps: Int?) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = leading, style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(6.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (steps != null) Text(
                text = "$steps 步",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val meta = remember(context) { metaOf(context) }
        if (meta == null) {
            DefaultToolPreview(context = context)
            return
        }
        val style = remember(meta.profile) { styleOf(meta.profile) }
        val stateText = if (meta.streaming) "运行中" else if (meta.succeeded) "已完成" else "失败"
        Column(
            modifier = Modifier
                .fillMaxHeight(0.8f)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = style.emoji, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${meta.profile ?: "subagent"} — $stateText",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = style.color,
                )
            }
            HorizontalDivider()
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
