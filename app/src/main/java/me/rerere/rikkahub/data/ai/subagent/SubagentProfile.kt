package me.rerere.rikkahub.data.ai.subagent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.TokenUsage
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import kotlin.uuid.Uuid

@Serializable
data class SubagentProfile(
    val name: String,
    val displayName: String = name,
    val description: String = "",
    val systemPrompt: String = "",
    val chatModelId: Uuid? = null,
    val temperature: Float? = null,
    val topP: Float? = null,
    val maxTokens: Int? = null,
    val reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
    val maxSteps: Int = 32,
    val inheritTools: Boolean = true,
    val localTools: List<LocalToolOption> = emptyList(),
    val enabledSkills: Set<String> = emptySet(),
    val mcpServerIds: Set<Uuid> = emptySet(),
    val excludedTools: Set<String> = emptySet(),
    val extraLocalTools: List<LocalToolOption> = emptyList(),
    /**
     * 显式放权: true = 子代理可自动执行 root shell 写命令(无需审批);
     * false(默认) = 子代理只能跑只读宿主命令, 写操作被拒绝并回报父代理.
     * 高危开关, 只对充分信任的 profile 开启.
     */
    val allowHostShellWrite: Boolean = false,
    /** 子代理单次 spawn 的总时长上限(秒), 超时按失败返回, 防止失控烧 token */
    val timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
    /** token 预算: 累计 total tokens 超过即中断, 按失败返回; 0 = 不限 */
    val maxTotalTokens: Int = 0,
    /**
     * 结构化返回: 非空时要求子代理最终输出匹配该 JSON Schema 的 JSON.
     * 校验失败会自动追问一次要求修正.
     */
    val outputSchema: String = "",
    val enableMemory: Boolean = false,
    val summaryMinLength: Int = DEFAULT_SUMMARY_MIN_LENGTH,
    val summaryContinuationAttempts: Int = DEFAULT_SUMMARY_CONTINUATION_ATTEMPTS,
    val streamOutput: Boolean = false,
) {
    init {
        require(name.isNotBlank()) { "Subagent profile name must not be blank" }
        require(name.matches(IdentifierRegex)) { "Subagent profile name must be lowercase letters/digits/underscore: $name" }
    }

    companion object {
        val IdentifierRegex = Regex("^[a-z][a-z0-9_]*$")
        val FILE_MUTATING_TOOLS: Set<String> = setOf("workspace_write_file", "workspace_edit_file")
        /** 具身/宿主高危工具: root shell、pty 交互终端、截屏、UI 树读取, 只读 profile 一律排除 */
        val HOST_SHELL_TOOLS: Set<String> = setOf("root_shell", "pty_exec", "pty_session", "root_screenshot", "ui_tree")
        val FULLY_READONLY_EXCLUDED_TOOLS: Set<String> = FILE_MUTATING_TOOLS + "workspace_shell" + HOST_SHELL_TOOLS
        const val DEFAULT_SUMMARY_MIN_LENGTH = 0
        const val DEFAULT_SUMMARY_CONTINUATION_ATTEMPTS = 0
        const val DEFAULT_TIMEOUT_SECONDS = 600

        /**
         * coder 子代理系统提示: 强制"推前验证"纪律。
         * 子代理本身拥有 search_code / get_file_contents / get_check_runs 等全部验证工具,
         * 此提示把"验证"从软建议变为硬要求, 从源头减少编译失败。
         */
        private val CODER_SYSTEM_PROMPT = """
You are a coding subagent. Complete the assigned task autonomously; do not ask questions — proceed with reasonable defaults.

MANDATORY verification before you finish (skipping these is the #1 cause of build failures):
1. Before calling ANY method/property on a class from a library or another file, CONFIRM it exists: use mcp__github__search_code or get_file_contents to grep the defining class for that symbol. Never assume an API exists from memory.
2. If you change a function signature, grep ALL call sites (search_code for "functionName(") and update every one.
3. After editing a file, read it back (get_file_contents) to confirm your change actually landed and the file is not truncated or corrupted.
4. Prefer pushing to a feature branch over main. When the task involves compilation, verify the result via GitHub Actions check runs when feasible, and fix failures before reporting success.
5. For large files (>30KB), do NOT read/rewrite the whole file. Locate the exact region and make targeted edits.

Report concisely: files changed + summary, what you verified, and any remaining risk.
""".trimIndent()

        /**
         * researcher 子代理系统提示（Deep Research 深度研究）：
         * 多轮联网搜索 + 交叉验证 + 结构化报告。可派 explore 子代理并行调研。
         */
        private val RESEARCHER_SYSTEM_PROMPT = """
You are a deep-research subagent. Thoroughly investigate a topic and produce a comprehensive, well-sourced report.

Methodology (follow rigorously):
1. DECOMPOSE: Break the research question into 3-6 focused sub-questions.
2. SEARCH BROADLY: For each sub-question, run multiple search_web queries with varied keywords. Never stop at the first result.
3. GO DEEP: Use scrape_web to read the most relevant pages in full. Prioritize primary sources, official docs, and reputable outlets.
4. CROSS-VERIFY: Corroborate key claims across at least 2 independent sources. Explicitly flag claims you could not verify.
5. PARALLELIZE: For independent sub-questions, spawn explore subagents to investigate concurrently.
6. SYNTHESIZE: Integrate findings into a coherent, structured report.

Output format (Markdown):
## 研究结论 (TL;DR)
[3-5 sentence executive summary answering the core question]

## 详细发现
[Organized by sub-question, with specifics and data]

## 来源与可信度
[List key sources with URLs; note confidence level and any conflicting information]

## 未解问题
[What remains uncertain or warrants further investigation]

Rules:
- Cite source URLs for factual claims.
- Clearly distinguish verified facts from analysis or opinion.
- Note when sources conflict or information is uncertain — do not paper over disagreement.
- Prefer depth and accuracy on the actual question over superficial breadth.
""".trimIndent()

        /**
         * coordinator 子代理系统提示（多 Agent 集群协作）：
         * 任务分解 → 并行派发给专精子代理 → 聚合验证。重在编排而非亲自执行。
         */
        private val COORDINATOR_SYSTEM_PROMPT = """
You are a coordinator subagent that orchestrates complex tasks by decomposing them and delegating to specialized subagents, then synthesizing the results. Your role is to PLAN and COORDINATE, not to do the leaf work yourself.

Methodology:
1. ANALYZE the task; decompose it into independent or sequential subtasks.
2. DELEGATE each subtask to the best-fit specialist by spawning subagents:
   - explore: read code/files, gather facts, quick lookups
   - researcher: deep multi-source investigation
   - coder: write/modify code, builds, file edits
   - reviewer: critique, code review, second opinions
3. PARALLELIZE independent subtasks — spawn multiple subagents in the SAME response for concurrent execution (this is the key to speed).
4. AGGREGATE: reconcile findings, resolve conflicts, integrate into one coherent deliverable.
5. VERIFY the combined result actually satisfies the original goal before reporting.

Rules:
- Brief each subagent fully and self-containedly (they have ZERO context of your conversation).
- Prefer parallel spawns for independent subtasks.
- After subagents return, YOU synthesize — never just concatenate their raw outputs.
- If a subtask fails, decide whether to retry, reassign to another profile, or work around it.
- For code changes, delegate to a coder, then verify the result (e.g., check CI) before reporting success.
- Your final output is the integrated deliverable, not a log of delegations.
""".trimIndent()

        val BUILTIN: List<SubagentProfile> = listOf(
            SubagentProfile(
                name = "explore", displayName = "Explorer",
                description = "Explore and gather information autonomously. Use for research, reading files, searching, and producing a factual summary.",
                excludedTools = FULLY_READONLY_EXCLUDED_TOOLS,
                systemPrompt = "You are an exploration subagent. Investigate autonomously using read-only tools, then return a concise but complete factual summary. Verify before asserting — state the concrete verification you ran and its output. Always end with a structured summary.",
                maxSteps = 16,
            ),
            SubagentProfile(
                name = "coder", displayName = "Coder",
                description = "Execute a well-scoped coding task autonomously and report results.",
                systemPrompt = CODER_SYSTEM_PROMPT,
                maxSteps = 30,
                timeoutSeconds = 900,
            ),
            SubagentProfile(
                name = "reviewer", displayName = "Reviewer",
                description = "Review / critique an artifact or plan and return structured feedback. Read-only.",
                systemPrompt = "You are a review subagent. Analyze the subject, optionally use read-only tools to inspect it, and return structured feedback: strengths, issues, and concrete suggestions.",
                inheritTools = true, excludedTools = FULLY_READONLY_EXCLUDED_TOOLS, maxSteps = 12,
            ),
            SubagentProfile(
                name = "researcher", displayName = "Researcher",
                description = "Deep research: thoroughly investigate a topic via multi-round web search and scraping, cross-verify sources, and produce a comprehensive cited report. Use for complex questions requiring breadth, depth, and source verification.",
                systemPrompt = RESEARCHER_SYSTEM_PROMPT,
                inheritTools = true, excludedTools = FULLY_READONLY_EXCLUDED_TOOLS,
                maxSteps = 40, timeoutSeconds = 1200,
            ),
            SubagentProfile(
                name = "coordinator", displayName = "Coordinator",
                description = "Orchestrate complex tasks by decomposing them and delegating to specialized subagents (explore/researcher/coder/reviewer) in parallel, then aggregating and verifying results. Use for large multi-faceted tasks benefiting from parallel specialized work.",
                systemPrompt = COORDINATOR_SYSTEM_PROMPT,
                inheritTools = true, excludedTools = FILE_MUTATING_TOOLS + HOST_SHELL_TOOLS,
                maxSteps = 30, timeoutSeconds = 1200,
            ),
        )
    }
}

fun mergeSubagentProfiles(custom: List<SubagentProfile>, disabledBuiltin: Set<String> = emptySet()): List<SubagentProfile> {
    val byName = LinkedHashMap<String, SubagentProfile>()
    SubagentProfile.BUILTIN.filter { it.name !in disabledBuiltin }.forEach { byName[it.name] = it }
    custom.forEach { byName[it.name] = it }
    return byName.values.toList()
}

fun upsertSubagentProfile(custom: List<SubagentProfile>, profile: SubagentProfile): List<SubagentProfile> {
    val exists = custom.any { it.name == profile.name }
    return if (exists) custom.map { if (it.name == profile.name) profile else it } else custom + profile
}

fun removeSubagentProfile(custom: List<SubagentProfile>, name: String): List<SubagentProfile> = custom.filterNot { it.name == name }

@Serializable
data class SubagentResult(
    @SerialName("profile_name") val profileName: String,
    @SerialName("summary") val summary: String,
    @SerialName("succeeded") val succeeded: Boolean,
    @SerialName("error") val error: String? = null,
    @SerialName("depth") val depth: Int = 0,
    @SerialName("usage") val usage: TokenUsage? = null,
    @SerialName("steps") val steps: Int = 0,
    @SerialName("tool_call_count") val toolCallCount: Int = 0,
    @SerialName("transcript") val transcript: List<SubagentTranscriptStep> = emptyList(),
    /** 会话 id, 可用 resume_subagent 追问(仅成功的 spawn 返回) */
    @SerialName("session_id") val sessionId: String? = null,
)

@Serializable
sealed interface SubagentTranscriptStep {
    @Serializable @SerialName("reasoning") data class Reasoning(val text: String, val createdAt: Long = 0) : SubagentTranscriptStep
    @Serializable @SerialName("tool_call") data class ToolCall(val name: String, val input: String, val output: String, val executed: Boolean, val childTranscript: List<SubagentTranscriptStep> = emptyList()) : SubagentTranscriptStep
    @Serializable @SerialName("text") data class Text(val text: String) : SubagentTranscriptStep
}
