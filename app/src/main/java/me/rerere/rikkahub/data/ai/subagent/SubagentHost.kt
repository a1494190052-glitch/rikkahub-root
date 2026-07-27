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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import kotlin.uuid.Uuid

private const val TAG = "SubagentHost"

private val SUMMARY_CONTINUATION_PROMPT = """
Your previous response was too brief for the parent agent to act on.
Please expand your summary: include the key findings, actions taken, outcomes, and any important
details the parent agent needs. Keep it focused and structured, but make it complete enough that
the parent agent does not need to re-run your work.
""".trimIndent()

private val NO_APPROVAL: (JsonElement) -> Boolean = { false }

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
            return SubagentResult(profileName = profile.name, summary = "", succeeded = false, error = "Subagent recursion depth limit reached ($maxDepth)", depth = depth)
        }

        val childModel = profile.chatModelId?.let { settings.findModelById(it) } ?: parentModel
        val childAssistant = buildChildAssistant(profile, parentAssistant)
        val rawChildTools = runCatching { buildChildTools(childAssistant, depth) }.getOrElse {
            Log.w(TAG, "spawn: buildChildTools failed: ${it.message}")
            emptyList()
        }
        val childTools = if (profile.excludedTools.isEmpty()) rawChildTools else rawChildTools.filter { it.name !in profile.excludedTools }

        var totalUsage: TokenUsage? = null
        var steps = 0

        // 总时长保护: 超时按失败返回(用户主动取消仍会作为 CancellationException 向上传播)
        val timedResult = withTimeoutOrNull(profile.timeoutSeconds.coerceAtLeast(1) * 1000L) {
            runCatching {
            Log.i(TAG, "spawn: subagent '${profile.name}' (depth=$depth) started")
            var messages = listOf(UIMessage.user(task))
            var run = runToCompletion(profile, settings, childModel, childAssistant, childTools, messages, onProgress)
            steps += 1
            totalUsage = mergeUsage(totalUsage, run.usage)
            messages = run.messages

            var summary = run.summary
            var countedMessageCount = run.messages.size
            var remainingContinuations = profile.summaryContinuationAttempts
            while (remainingContinuations > 0 && summary.length < profile.summaryMinLength) {
                remainingContinuations -= 1
                val continuationMessages = messages + UIMessage.user(SUMMARY_CONTINUATION_PROMPT)
                run = runToCompletion(profile, settings, childModel, childAssistant, emptyList(), continuationMessages, onProgress)
                steps += 1
                // 只累加本轮新增消息的 usage, 全量累加会把前轮已计数的 usage 重复计算
                totalUsage = mergeUsage(totalUsage, accumulateUsage(run.messages.drop(countedMessageCount)))
                countedMessageCount = run.messages.size
                messages = run.messages
                summary = run.summary
            }

            val transcript = buildTranscript(messages)
            val result = SubagentResult(
                profileName = profile.name, summary = summary.ifBlank { "(subagent produced no textual summary)" },
                succeeded = true, depth = depth, usage = totalUsage, steps = steps,
                toolCallCount = countToolCalls(messages), transcript = transcript,
            )
            logResult(result)
            result
            }.onFailure {
                if (it is CancellationException) throw it
                Log.e(TAG, "spawn: subagent '${profile.name}' failed: ${it.message}", it)
            }.getOrElse {
                SubagentResult(profileName = profile.name, summary = "", succeeded = false, error = it.message ?: it.javaClass.name, depth = depth, usage = totalUsage, steps = steps)
            }
        }
        return timedResult ?: run {
            Log.w(TAG, "spawn: subagent '${profile.name}' (depth=$depth) timed out after ${profile.timeoutSeconds}s")
            SubagentResult(profileName = profile.name, summary = "", succeeded = false, error = "subagent timed out after ${profile.timeoutSeconds}s", depth = depth, usage = totalUsage, steps = steps)
        }
    }

    private suspend fun runToCompletion(
        profile: SubagentProfile, settings: Settings, model: Model, assistant: Assistant,
        tools: List<Tool>, initialMessages: List<UIMessage>, onProgress: ((List<UIMessage>) -> Unit)?,
    ): RunCompletion {
        val progressScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var lastEmitTime = 0L
        var lastSignature = -1
        val minIntervalMs = 250L

        val finalMessages = try {
            generationHandler.generateText(
                settings = settings, model = model, messages = initialMessages, assistant = assistant,
                tools = tools, maxSteps = profile.maxSteps.coerceIn(1, 256), memories = emptyList(),
            ).onEach { chunk ->
                if (chunk is GenerationChunk.Messages && onProgress != null) {
                    val now = System.currentTimeMillis()
                    val signature = chunk.messages.sumOf { msg ->
                    if (msg.role == MessageRole.ASSISTANT) {
                        // parts 数量 + 文本总长度: 流式文本增长也能触发进度回调
                        msg.parts.size + msg.parts.filterIsInstance<UIMessagePart.Text>().sumOf { it.text.length } +
                            msg.parts.filterIsInstance<UIMessagePart.Reasoning>().sumOf { it.reasoning.length }
                    } else 0
                }
                    if (signature != lastSignature || now - lastEmitTime >= minIntervalMs) {
                        lastEmitTime = now; lastSignature = signature
                        val msgs = chunk.messages
                        progressScope.launch { onProgress.invoke(msgs) }
                    }
                }
            }.fold(initialMessages) { _, chunk -> when (chunk) { is GenerationChunk.Messages -> chunk.messages } }
        } finally { progressScope.cancel() }

        return RunCompletion(messages = finalMessages, summary = lastAssistantText(finalMessages), usage = accumulateUsage(finalMessages))
    }

    private fun buildChildAssistant(profile: SubagentProfile, parent: Assistant): Assistant {
        val localTools = buildList {
            if (profile.inheritTools) { addAll(parent.localTools); addAll(profile.extraLocalTools) }
            else addAll(profile.localTools)
            removeAll { it == LocalToolOption.AskUser }
        }.distinct()

        return parent.copy(
            id = Uuid.random(), name = profile.displayName,
            // profile 未配置系统提示时给兜底, 避免子代理在零系统提示下行为不可控
            systemPrompt = profile.systemPrompt.ifBlank { DEFAULT_CHILD_SYSTEM_PROMPT },
            temperature = profile.temperature ?: parent.temperature, topP = profile.topP ?: parent.topP,
            maxTokens = profile.maxTokens ?: parent.maxTokens, reasoningLevel = profile.reasoningLevel,
            contextMessageSize = 0, streamOutput = profile.streamOutput || parent.streamOutput,
            enableMemory = profile.enableMemory, useGlobalMemory = false, enableRecentChatsReference = false,
            allowConversationSystemPrompt = false, allowConversationPromptInjection = false,
            enableTimeReminder = false, modeInjectionIds = emptySet(), lorebookIds = emptySet(),
            localTools = localTools, presetMessages = emptyList(), quickMessageIds = emptySet(),
            // profile 配置了技能白名单则覆盖, 否则继承父助手
            enabledSkills = profile.enabledSkills.ifEmpty { parent.enabledSkills },
            regexes = emptyList(), customHeaders = parent.customHeaders, customBodies = parent.customBodies,
        )
    }

    private fun countToolCalls(messages: List<UIMessage>): Int {
        var n = 0
        for (message in messages) { if (message.role == MessageRole.ASSISTANT) n += message.parts.count { it is UIMessagePart.Tool } }
        return n
    }

    private fun lastAssistantText(messages: List<UIMessage>): String {
        for (message in messages.asReversed()) {
            if (message.role != MessageRole.ASSISTANT) continue
            val text = message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }
            if (text.isNotBlank()) return text.trim()
        }
        return ""
    }

    private fun accumulateUsage(messages: List<UIMessage>): TokenUsage? {
        var acc: TokenUsage? = null
        for (message in messages) { val u = message.usage ?: continue; acc = acc.merge(u) }
        return acc
    }

    private fun mergeUsage(acc: TokenUsage?, other: TokenUsage?): TokenUsage? = when { acc == null -> other; other == null -> acc; else -> acc.merge(other) }

    private fun logResult(result: SubagentResult) {
        val u = result.usage
        if (u != null) Log.i(TAG, "spawn: subagent '${result.profileName}' (depth=${result.depth}) finished in ${result.steps} step(s); tokens: prompt=${u.promptTokens}, completion=${u.completionTokens}, cached=${u.cachedTokens}, total=${u.totalTokens}")
        else Log.i(TAG, "spawn: subagent '${result.profileName}' (depth=${result.depth}) finished in ${result.steps} step(s); tokens: n/a")
    }

    private data class RunCompletion(val messages: List<UIMessage>, val summary: String, val usage: TokenUsage?)

    companion object {
        private const val DEFAULT_MAX_DEPTH = 2

        private const val DEFAULT_CHILD_SYSTEM_PROMPT =
            "You are a task-execution subagent. Complete the assigned task autonomously with the tools available to you, then return a concise but complete summary of what you did, what you found, and anything the parent agent needs to act on. Do not ask questions — proceed with reasonable defaults."

        fun buildTranscript(messages: List<UIMessage>, truncateToolOutput: Int = 0): List<SubagentTranscriptStep> {
            val steps = mutableListOf<SubagentTranscriptStep>()
            for (message in messages) {
                if (message.role != MessageRole.ASSISTANT) continue
                for (part in message.parts) {
                    when (part) {
                        is UIMessagePart.Reasoning -> {
                            if (part.reasoning.isNotBlank()) steps.add(SubagentTranscriptStep.Reasoning(text = part.reasoning, createdAt = System.currentTimeMillis()))
                        }
                        is UIMessagePart.Tool -> {
                            val outputText = part.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
                            steps.add(SubagentTranscriptStep.ToolCall(name = part.toolName, input = part.input, output = if (truncateToolOutput > 0) outputText.take(truncateToolOutput) else outputText, executed = part.isExecuted))
                        }
                        is UIMessagePart.Text -> { if (part.text.isNotBlank()) steps.add(SubagentTranscriptStep.Text(part.text.trim())) }
                        else -> {}
                    }
                }
            }
            return steps
        }

        /**
         * 子代理工具沙箱化:
         *  - 所有工具强制免审批(子代理环境无人可批, Pending 会永久卡死);
         *  - 但宿主 shell 类工具(root_shell / pty_exec / pty_session)额外包一层执行期闸门:
         *    ShellSafety 判定为 WRITE/BLOCKED 的命令直接拒绝, 让子代理把写操作交还父代理.
         *    只读命令照常放行, 不影响探索类子代理工作.
         */
        fun sandboxToolsForSubagent(tools: List<Tool>, allowHostShellWrite: Boolean = false): List<Tool> = tools.map { tool ->
            val sandboxed = tool.copy(needsApproval = NO_APPROVAL)
            if (!allowHostShellWrite && tool.name in HOST_SHELL_WRITE_GUARDED_TOOLS) {
                sandboxed.copy(execute = guardHostShellExecution(tool.name, sandboxed.execute))
            } else {
                sandboxed
            }
        }

        private val HOST_SHELL_WRITE_GUARDED_TOOLS: Set<String> = setOf("root_shell", "pty_exec", "pty_session")

        private fun guardHostShellExecution(
            toolName: String,
            original: suspend (JsonElement) -> List<UIMessagePart>,
        ): suspend (JsonElement) -> List<UIMessagePart> = { args ->
            if (toolName == "pty_exec" || toolName == "pty_session") {
                // pty 交互命令无法静态审计, 子代理内一律拒绝
                listOf(
                    UIMessagePart.Text(
                        """{"blocked": true, "reason": "interactive pty sessions are not allowed in a subagent", "message": "Interactive terminal sessions cannot be audited inside a subagent and were NOT started. Report the needed interaction in your summary so the parent agent can run it with user approval."}"""
                    )
                )
            } else {
                val command = args.jsonObject["command"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (me.rerere.rikkahub.data.ai.tools.local.ShellSafety.classify(command) !=
                    me.rerere.rikkahub.data.ai.tools.local.ShellRisk.READ_ONLY
                ) {
                    listOf(
                        UIMessagePart.Text(
                            """{"blocked": true, "reason": "write/blocked host shell commands are not allowed in a subagent", "message": "This command was classified as WRITE or BLOCKED and was NOT executed. Subagents may only run read-only host shell commands. Report the required write operation in your summary so the parent agent can execute it with user approval."}"""
                        )
                    )
                } else {
                    original(args)
                }
            }
        }
    }
}
