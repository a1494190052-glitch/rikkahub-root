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
import me.rerere.rikkahub.data.ai.tools.local.ShellRisk
import me.rerere.rikkahub.data.ai.tools.local.ShellSafety
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
    /** 一次成功 spawn 后交给调用方持久化的会话数据, 供 resume 追问 */
    data class SubagentSessionData(
        val profile: SubagentProfile,
        val assistant: Assistant,
        val model: Model,
        val messages: List<UIMessage>,
    )

    /** token 预算超支(非用户取消): 中断子代理并按失败返回 */
    class TokenBudgetExceededException(val budget: Int) : Exception("token budget exceeded (maxTotalTokens=$budget)")

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
        onSessionComplete: ((sessionId: String, session: SubagentSessionData) -> Unit)? = null,
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

            // 结构化输出: schema 非空时校验最终 summary 是合法 JSON, 否则追问一次修正
            if (profile.outputSchema.isNotBlank() && extractJson(summary) == null) {
                run = runToCompletion(
                    profile, settings, childModel, childAssistant, emptyList(),
                    messages + UIMessage.user(SCHEMA_RETRY_PROMPT), onProgress,
                )
                steps += 1
                totalUsage = mergeUsage(totalUsage, accumulateUsage(run.messages.drop(countedMessageCount)))
                countedMessageCount = run.messages.size
                messages = run.messages
                summary = run.summary
            }
            val structured = profile.outputSchema.isNotBlank()
            if (structured) summary = extractJson(summary) ?: summary

            val transcript = buildTranscript(messages)
            val sessionId = Uuid.random().toString()
            val result = SubagentResult(
                profileName = profile.name, summary = summary.ifBlank { "(subagent produced no textual summary)" },
                succeeded = true, depth = depth, usage = totalUsage, steps = steps,
                toolCallCount = countToolCalls(messages), transcript = transcript,
                sessionId = sessionId,
            )
            onSessionComplete?.invoke(sessionId, SubagentSessionData(profile, childAssistant, childModel, messages))
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

    /**
     * 追问一个已完成的子代理会话: 在原有消息上下文上继续一轮.
     * 返回 (结果, 更新后的完整消息列表) — 调用方需用新消息列表更新会话存储.
     */
    suspend fun resume(
        session: SubagentSessionData,
        followUp: String,
        settings: Settings,
        buildChildTools: suspend (childAssistant: Assistant, depth: Int) -> List<Tool>,
        onProgress: ((List<UIMessage>) -> Unit)? = null,
    ): Pair<SubagentResult, List<UIMessage>> {
        val profile = session.profile
        val rawChildTools = runCatching { buildChildTools(session.assistant, 0) }.getOrElse { emptyList() }
        val childTools = if (profile.excludedTools.isEmpty()) rawChildTools else rawChildTools.filter { it.name !in profile.excludedTools }

        val timedResult = withTimeoutOrNull(profile.timeoutSeconds.coerceAtLeast(1) * 1000L) {
            runCatching {
                val run = runToCompletion(
                    profile, settings, session.model, session.assistant, childTools,
                    session.messages + UIMessage.user(followUp), onProgress,
                )
                var summary = run.summary
                if (profile.outputSchema.isNotBlank()) summary = extractJson(summary) ?: summary
                val result = SubagentResult(
                    profileName = profile.name, summary = summary.ifBlank { "(subagent produced no textual summary)" },
                    succeeded = true, depth = 0, usage = run.usage, steps = 1,
                    toolCallCount = countToolCalls(run.messages.drop(session.messages.size)),
                    transcript = buildTranscript(run.messages.drop(session.messages.size)),
                )
                result to run.messages
            }.getOrElse {
                if (it is CancellationException) throw it
                SubagentResult(profileName = profile.name, summary = "", succeeded = false, error = it.message ?: it.javaClass.name) to session.messages
            }
        }
        return timedResult ?: (SubagentResult(
            profileName = profile.name, summary = "", succeeded = false,
            error = "subagent timed out after ${profile.timeoutSeconds}s",
        ) to session.messages)
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
                if (chunk is GenerationChunk.Messages && profile.maxTotalTokens > 0) {
                    // token 预算: 累计(含 prompt 回显)超过上限即中断, 按失败返回
                    val used = chunk.messages.drop(initialMessages.size)
                        .mapNotNull { it.usage }.fold(0) { acc, u -> acc + u.totalTokens }
                    if (used > profile.maxTotalTokens) throw TokenBudgetExceededException(profile.maxTotalTokens)
                }
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

        // profile 未配置系统提示时给兜底, 避免子代理在零系统提示下行为不可控
        val basePrompt = profile.systemPrompt.ifBlank { DEFAULT_CHILD_SYSTEM_PROMPT }
        // 结构化输出: 把 schema 要求写进系统提示
        val effectivePrompt = if (profile.outputSchema.isNotBlank()) {
            basePrompt + "\n\nYour FINAL response must be a single valid JSON value conforming to this JSON Schema (no markdown fences, no prose around it):\n" + profile.outputSchema
        } else basePrompt

        return parent.copy(
            id = Uuid.random(), name = profile.displayName,
            systemPrompt = effectivePrompt,
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

        private const val SCHEMA_RETRY_PROMPT =
            "Your previous response was not valid JSON. Reply with ONLY the JSON value conforming to the required schema — no markdown fences, no explanation."

        /** 从模型输出中提取 JSON: 去 markdown 围栏, 截取首个 {/[ 到末尾尝试解析 */
        fun extractJson(text: String): String? {
            val stripped = text.trim()
                .removePrefix("```json").removePrefix("```JSON").removePrefix("```")
                .removeSuffix("```").trim()
            val start = stripped.indexOfFirst { it == '{' || it == '[' }
            if (start < 0) return null
            val candidate = stripped.substring(start)
            return runCatching {
                kotlinx.serialization.json.Json.parseToJsonElement(candidate)
                candidate
            }.getOrNull()
        }

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
         *
         * v2: 增强版 — 使用 classifyDeep 进行多层检测, 结构化警告, 审计日志
         */
        fun sandboxToolsForSubagent(
            tools: List<Tool>,
            allowHostShellWrite: Boolean = false,
            workspaceRootMode: Boolean = false,
        ): List<Tool> = tools.map { tool ->
            val sandboxed = tool.copy(needsApproval = NO_APPROVAL)
            // root 模式下 workspace_shell 实际是宿主机 root shell, 与 root_shell 同等防护
            val guarded = tool.name in HOST_SHELL_WRITE_GUARDED_TOOLS ||
                (workspaceRootMode && tool.name in WORKSPACE_SHELL_TOOLS)
            if (!allowHostShellWrite && guarded) {
                sandboxed.copy(execute = guardHostShellExecution(tool.name, sandboxed.execute))
            } else {
                sandboxed
            }
        }

        private val HOST_SHELL_WRITE_GUARDED_TOOLS: Set<String> = setOf("root_shell", "pty_exec", "pty_session")
        private val WORKSPACE_SHELL_TOOLS: Set<String> = setOf("workspace_shell", "workspace_shell_bg")

        /**
         * 第三层: 运行时拦截 — 增强版
         *  - 命令执行前记录审计日志 (Android Log)
         *  - 使用 classifyDeep 进行多层检测
         *  - BLOCKED: 结构化警告 + 绕过指标
         *  - WRITE: 观察模式, 记录但不执行, 返回 dry-run 结果
         *  - READ_ONLY: 放行
         */
        private fun guardHostShellExecution(
            toolName: String,
            original: suspend (JsonElement) -> List<UIMessagePart>,
        ): suspend (JsonElement) -> List<UIMessagePart> = { args ->
            if (toolName == "pty_exec" || toolName == "pty_session") {
                // pty 交互命令无法静态审计, 子代理内一律拒绝
                Log.w(TAG, "[ShellGuard] BLOCKED pty session in subagent: tool=$toolName")
                listOf(
                    UIMessagePart.Text(
                        """{"blocked": true, "reason": "interactive pty sessions are not allowed in a subagent", "message": "Interactive terminal sessions cannot be audited inside a subagent and were NOT started. Report the needed interaction in your summary so the parent agent can run it with user approval."}"""
                    )
                )
            } else {
                val command = args.jsonObject["command"]?.jsonPrimitive?.contentOrNull.orEmpty()

                // 审计日志: 记录所有子代理 shell 命令
                Log.i(TAG, "[ShellGuard] audit: tool=$toolName command=${command.take(200)}")

                // 使用深度分类
                val deepResult = ShellSafety.classifyDeep(command)

                when (deepResult.overall) {
                    ShellRisk.BLOCKED -> {
                        // 结构化警告: 包含拒绝原因和绕过指标
                        Log.w(TAG, "[ShellGuard] BLOCKED: tool=$toolName reason=${deepResult.blockReason} indicators=${deepResult.bypassIndicators}")
                        val indicatorsJson = deepResult.bypassIndicators.joinToString("", "[", "]") { "\"$it\"" }
                        val segmentsJson = deepResult.segments.joinToString("", "[", "]") { seg ->
                            """{"command": "${seg.command.take(100).replace("\"", "\\\"")}", "risk": "${seg.risk}", "reason": "${(seg.reason ?: "").replace("\"", "\\\"")}"}"""
                        }
                        listOf(
                            UIMessagePart.Text(
                                """{"blocked": true, "risk": "BLOCKED", "reason": "${(deepResult.blockReason ?: "high-risk command").replace("\"", "\\\"")}", "bypass_indicators": $indicatorsJson, "segments": $segmentsJson, "message": "This command was classified as BLOCKED (destructive/dangerous) and was NOT executed. The dynamic multi-layer guard detected: ${deepResult.blockReason ?: "high-risk pattern"}. Report this operation in your summary so the parent agent can evaluate it with user approval."}"""
                            )
                        )
                    }
                    ShellRisk.WRITE -> {
                        // 观察模式: 记录但不执行, 返回 dry-run 结果
                        Log.w(TAG, "[ShellGuard] DRY-RUN (WRITE): tool=$toolName command=${command.take(200)} indicators=${deepResult.bypassIndicators}")
                        val indicatorsJson = deepResult.bypassIndicators.joinToString("", "[", "]") { "\"$it\"" }
                        listOf(
                            UIMessagePart.Text(
                                """{"blocked": true, "risk": "WRITE", "dry_run": true, "reason": "write/mutating commands are not allowed in a subagent without explicit permission", "bypass_indicators": $indicatorsJson, "message": "This command was classified as WRITE (mutating) and was NOT executed (observation mode). Subagents may only run read-only host shell commands. Report the required write operation in your summary so the parent agent can execute it with user approval."}"""
                            )
                        )
                    }
                    ShellRisk.READ_ONLY -> {
                        // 放行: 只读命令直接执行
                        Log.d(TAG, "[ShellGuard] ALLOWED (READ_ONLY): tool=$toolName command=${command.take(100)}")
                        original(args)
                    }
                }
            }
        }
    }
}
