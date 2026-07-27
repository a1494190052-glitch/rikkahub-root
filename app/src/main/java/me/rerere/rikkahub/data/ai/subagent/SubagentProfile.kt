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
