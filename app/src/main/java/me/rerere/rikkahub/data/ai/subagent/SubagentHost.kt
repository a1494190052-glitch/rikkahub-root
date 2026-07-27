package me.rerere.rikkahub.data.ai.subagent

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.Tool
import me.rerere.ai.core.merge
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.model.Assistant
import kotlin.time.Instant
import kotlin.uuid.Uuid

private const val TAG = "SubagentHost"

/**
 * 当摘要过短时，追问子代理扩写的一段提示词。
 * 移植自 kimi-code 的 summary-continuation.md。
 */
private val SUMMARY_CONTINUATION_PROMPT = """
Your previous response was too brief for the parent agent to act on.
Please expand your summary: include the key findings, actions taken, outcomes, and any important
details the parent agent needs. Keep it focused and structured, but make it complete enough that
the parent agent does not need to re-run your work.
""".trimIndent()

/** 永远返回 false 的审批策略 —— 子代理自主运行不触发 HITL。 */
private val NO_APPROVAL: (JsonElement) -> Boolean = { false }

/**
 * 决定摘要追问（continuation）轮应使用的工具集。
 * 恒返回空列表 —— 追问轮是一次纯文本扩写，不应再发起工具调用。
 */
internal fun selectContinuationTools(childTools: List<Tool>): List<Tool> = emptyList()

/**
 * Subagent 宿主 —— 移植自 kimi-code 的 SessionSubagentHost。
 *
 * 它负责把一个 SubagentProfile + 任务描述，编译成一个子 Assistant，
 * 复用 GenerationHandler 跑一段独立的 generation 循环，并把最终摘要返回。
 */
class SubagentHost(
    private val generationHandler: GenerationHandler,
) {
    suspend fun spawn(
        profile: SubagentProfile,
        task: String,
        settings: Settings,
        parentAssistant: Assistant,
        parentModel: Model,
        buildChildTools: suspend (childAssistant: Assistant, depth: Int) -> List<Tool>,
        depth: Int = 0,
        maxDepth: Int = DEFAULT_MAX_DEPTH,
        onProgress: ((List<UIMessage>) -> Unit)? = null,
    ): SubagentResult {
        if (depth >= maxDepth) {
            return SubagentResult(
                profileName = profile.name,
                summary = "",
                succeeded = false,
                error = "Subagent recursion depth limit reached ($maxDepth)",
                depth = depth,
            )
        }

        val childModel = profile.chatModelId
            ?.let { settings.findModelById(it) }
            ?: parentModel

        val childAssistant = buildChildAssistant(profile, parentAssistant)
        val rawChildTools = runCatching {
            buildChildTools(childAssistant, depth)
        }.getOrElse {
            Log.w(TAG, "spawn: buildChildTools failed: ${it.message}")
            emptyList()
        }
        val childTools = if (profile.excludedTools.isEmpty()) {
            rawChildTools
        } else {
            rawChildTools.filter { it.name !in profile.excludedTools }
        }

        var totalUsage: TokenUsage? = null
        var steps = 0

        return runCatching {
            Log.i(TAG, "spawn: subagent '${profile.name}' (depth=$depth) started")

            var messages = listOf(UIMessage.user(task))
            var run = runToCompletion(
                profile = profile,
                settings = settings,
                model = childModel,
                assistant = childAssistant,
                tools = childTools,
                initialMessages = messages,
                onProgress = onProgress,
            )
            steps += 1
            totalUsage = mergeUsage(totalUsage, run.usage)
            messages = run.messages

            var summary = run.summary
            var remainingContinuations = profile.summaryContinuationAttempts
            while (remainingContinuations > 0 && summary.length < profile.summaryMinLength) {
                remainingContinuations -= 1
                val continuationMessages = messages + UIMessage.user(SUMMARY_CONTINUATION_PROMPT)
                run = runToCompletion(
                    profile = profile,
                    settings = settings,
                    model = childModel,
                    assistant = childAssistant,
                    tools = selectContinuationTools(childTools),
                    initialMessages = continuationMessages,
                    onProgress = onProgress,
                )
                steps += 1
                totalUsage = mergeUsage(totalUsage, run.usage)
                messages = run.messages
                summary = run.summary
            }

            val transcript = buildTranscript(messages)

            val result = SubagentResult(
                profileName = profile.name,
                summary = summary.ifBlank { "(subagent produced no textual summary)" },
                succeeded = true,
                depth = depth,
                usage = totalUsage,
                steps = steps,
                toolCallCount = countToolCalls(messages),
                transcript = transcript,
            )
            logResult(result)
            result
        }.onFailure {
            if (it is CancellationException) throw it
            Log.e(TAG, "spawn: subagent '${profile.name}' failed: ${it.message}", it)
        }.getOrElse {
            SubagentResult(
                profileName = profile.name,
                summary = "",
                succeeded = false,
                error = it.message ?: it.javaClass.name,
                depth = depth,
                usage = totalUsage,
                steps = steps,
            )
        }
    }

    private suspend fun runToCompletion(
        profile: SubagentProfile,
        settings: Settings,
        model: Model,
        assistant: Assistant,
        tools: List<Tool>,
        initialMessages: List<UIMessage>,
        onProgress: ((List<UIMessage>) -> Unit)?,
    ): RunCompletion {
        val progressScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var lastEmitTime = 0L
        var lastSignature = -1
        val minIntervalMs = 250L

        val finalMessages = try {
            generationHandler.generateText(
                settings = settings,
                model = model,
                messages = initialMessages,
                assistant = assistant,
                tools = tools,
                maxSteps = profile.maxSteps.coerceIn(1, 256),
                memories = emptyList(),
            ).onEach { chunk ->
                if (chunk is GenerationChunk.Messages && onProgress != null) {
                    val now = System.currentTimeMillis()
                    val signature = chunk.messages.sumOf { msg ->
                        if (msg.role == MessageRole.ASSISTANT) msg.parts.size else 0
                    }
                    if (signature != lastSignature || now - lastEmitTime >= minIntervalMs) {
                        lastEmitTime = now
                        lastSignature = signature
                        val msgs = chunk.messages
                        progressScope.launch { onProgress.invoke(msgs) }
                    }
                }
            }.fold(initialMessages) { _, chunk ->
                when (chunk) {
                    is GenerationChunk.Messages -> chunk.messages
                }
            }
        } finally {
            progressScope.cancel()
        }

        return RunCompletion(
            messages = finalMessages,
            summary = lastAssistantText(finalMessages),
            usage = accumulateUsage(finalMessages),
        )
    }

    private fun buildChildAssistant(
        profile: SubagentProfile,
        parent: Assistant,
    ): Assistant {
        val localTools = buildList {
            if (profile.inheritTools) {
                addAll(parent.localTools)
                addAll(profile.extraLocalTools)
            } else {
                addAll(profile.localTools)
            }
            removeAll { it == LocalToolOption.AskUser }
        }.distinct()

        return parent.copy(
            id = Uuid.random(),
            name = profile.displayName,
            systemPrompt = profile.systemPrompt,
            temperature = profile.temperature ?: parent.temperature,
            topP = profile.topP ?: parent.topP,
            maxTokens = profile.maxTokens ?: parent.maxTokens,
            reasoningLevel = profile.reasoningLevel,
            contextMessageSize = 0,
            streamOutput = profile.streamOutput || parent.streamOutput,
            enableMemory = profile.enableMemory,
            useGlobalMemory = false,
            enableRecentChatsReference = false,
            allowConversationSystemPrompt = false,
            allowConversationPromptInjection = false,
            enableTimeReminder = false,
            modeInjectionIds = emptySet(),
            lorebookIds = emptySet(),
            localTools = localTools,
            presetMessages = emptyList(),
            quickMessageIds = emptySet(),
            regexes = emptyList(),
            customHeaders = parent.customHeaders,
            customBodies = parent.customBodies,
        )
    }

    private fun countToolCalls(messages: List<UIMessage>): Int {
        var n = 0
        for (message in messages) {
            if (message.role != MessageRole.ASSISTANT) continue
            n += message.parts.count { it is UIMessagePart.Tool }
        }
        return n
    }

    private fun lastAssistantText(messages: List<UIMessage>): String {
        for (message in messages.asReversed()) {
            if (message.role != MessageRole.ASSISTANT) continue
            val text = message.parts
                .filterIsInstance<UIMessagePart.Text>()
                .joinToString("") { it.text }
            if (text.isNotBlank()) return text.trim()
        }
        return ""
    }

    private fun accumulateUsage(messages: List<UIMessage>): TokenUsage? {
        var acc: TokenUsage? = null
        for (message in messages) {
            val u = message.usage ?: continue
            acc = acc.merge(u)
        }
        return acc
    }

    private fun mergeUsage(acc: TokenUsage?, other: TokenUsage?): TokenUsage? {
        if (acc == null) return other
        if (other == null) return acc
        return acc.merge(other)
    }

    private fun logResult(result: SubagentResult) {
        val u = result.usage
        if (u != null) {
            Log.i(TAG, "spawn: subagent '${result.profileName}' (depth=${result.depth}) finished in ${result.steps} step(s); tokens: prompt=${u.promptTokens}, completion=${u.completionTokens}, cached=${u.cachedTokens}, total=${u.totalTokens}")
        } else {
            Log.i(TAG, "spawn: subagent '${result.profileName}' (depth=${result.depth}) finished in ${result.steps} step(s); tokens: n/a")
        }
    }

    private data class RunCompletion(
        val messages: List<UIMessage>,
        val summary: String,
        val usage: TokenUsage?,
    )

    companion object {
        private const val DEFAULT_MAX_DEPTH = 2

        fun buildTranscript(
            messages: List<UIMessage>,
            truncateToolOutput: Int = 0,
        ): List<SubagentTranscriptStep> {
            val steps = mutableListOf<SubagentTranscriptStep>()
            for (message in messages) {
                if (message.role != MessageRole.ASSISTANT) continue
                for (part in message.parts) {
                    when (part) {
                        is UIMessagePart.Reasoning -> {
                            if (part.reasoning.isNotBlank()) {
                                steps.add(SubagentTranscriptStep.Reasoning(
                                    text = part.reasoning,
                                    createdAt = part.createdAt.toEpochMilliseconds(),
                                ))
                            }
                        }
                        is UIMessagePart.Tool -> {
                            val outputText = part.output
                                .filterIsInstance<UIMessagePart.Text>()
                                .joinToString("\n") { it.text }
                            steps.add(SubagentTranscriptStep.ToolCall(
                                name = part.toolName,
                                input = part.input,
                                output = if (truncateToolOutput > 0) outputText.take(truncateToolOutput) else outputText,
                                executed = part.isExecuted,
                            ))
                        }
                        is UIMessagePart.Text -> {
                            if (part.text.isNotBlank()) {
                                steps.add(SubagentTranscriptStep.Text(part.text.trim()))
                            }
                        }
                        else -> {}
                    }
                }
            }
            return steps
        }

        fun sandboxToolsForSubagent(tools: List<Tool>): List<Tool> = tools.map { tool ->
            tool.copy(needsApproval = NO_APPROVAL)
        }
    }
}
