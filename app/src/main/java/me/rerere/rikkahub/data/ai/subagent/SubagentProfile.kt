package me.rerere.rikkahub.data.ai.subagent

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.TokenUsage
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import kotlin.uuid.Uuid

/**
 * Subagent 配置档 —— 移植自 kimi-code 的 ResolvedAgentProfile。
 */
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
    val enableMemory: Boolean = false,
    val summaryMinLength: Int = DEFAULT_SUMMARY_MIN_LENGTH,
    val summaryContinuationAttempts: Int = DEFAULT_SUMMARY_CONTINUATION_ATTEMPTS,
    val streamOutput: Boolean = false,
) {
    init {
        require(name.isNotBlank()) { "Subagent profile name must not be blank" }
        require(name.matches(IdentifierRegex)) {
            "Subagent profile name must be lowercase letters/digits/underscore: $name"
        }
    }

    companion object {
        val IdentifierRegex = Regex("^[a-z][a-z0-9_]*$")

        val FILE_MUTATING_TOOLS: Set<String> = setOf("workspace_write_file", "workspace_edit_file")

        val FULLY_READONLY_EXCLUDED_TOOLS: Set<String> = FILE_MUTATING_TOOLS + "workspace_shell"

        const val DEFAULT_SUMMARY_MIN_LENGTH = 0
        const val DEFAULT_SUMMARY_CONTINUATION_ATTEMPTS = 0

        val BUILTIN: List<SubagentProfile> = listOf(
            SubagentProfile(
                name = "explore",
                displayName = "Explorer",
                description = "Explore and gather information autonomously. Use for research, reading files, searching, and producing a factual summary.",
                excludedTools = FULLY_READONLY_EXCLUDED_TOOLS,
                systemPrompt = """
You are an exploration subagent. Your job is to autonomously investigate the task
using the tools available to you, then return a concise but complete factual summary.
Do not ask the user questions — make reasonable assumptions and proceed.

Read-only discipline: you only have read-only tools available. You investigate; you do not
modify the workspace.

Verify, don't assert: before reporting a root cause or conclusion, state the concrete
verification you ran and its output. A hypothesis dressed as a root cause is not a result.
Always end with a structured summary of your findings.
""".trimIndent(),
                maxSteps = 16,
            ),
            SubagentProfile(
                name = "coder",
                displayName = "Coder",
                description = "Execute a well-scoped coding task autonomously and report results. Use for writing or modifying files, running shell commands, and verifying outcomes.",
                systemPrompt = """
You are a coding subagent. Complete the assigned task autonomously using your tools.
Make changes, verify them, and report what you did and whether it succeeded.
Return a concise summary of changes and verification results.
Do not ask the user questions — proceed with reasonable defaults.
""".trimIndent(),
                maxSteps = 20,
            ),
            SubagentProfile(
                name = "reviewer",
                displayName = "Reviewer",
                description = "Review / critique an artifact or plan and return structured feedback. Read-only oriented; does not make changes.",
                systemPrompt = """
You are a review subagent. Analyze the subject described in the task, optionally use
read-only tools to inspect it, and return structured feedback: strengths, issues,
and concrete suggestions. Do not modify anything unless explicitly asked.
""".trimIndent(),
                inheritTools = true,
                excludedTools = FULLY_READONLY_EXCLUDED_TOOLS,
                maxSteps = 12,
            ),
        )
    }
}

fun mergeSubagentProfiles(
    custom: List<SubagentProfile>,
    disabledBuiltin: Set<String> = emptySet(),
): List<SubagentProfile> {
    val byName = LinkedHashMap<String, SubagentProfile>()
    SubagentProfile.BUILTIN
        .filter { it.name !in disabledBuiltin }
        .forEach { byName[it.name] = it }
    custom.forEach { byName[it.name] = it }
    return byName.values.toList()
}

fun upsertSubagentProfile(
    custom: List<SubagentProfile>,
    profile: SubagentProfile,
): List<SubagentProfile> {
    val exists = custom.any { it.name == profile.name }
    return if (exists) {
        custom.map { if (it.name == profile.name) profile else it }
    } else {
        custom + profile
    }
}

fun removeSubagentProfile(
    custom: List<SubagentProfile>,
    name: String,
): List<SubagentProfile> = custom.filterNot { it.name == name }

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
)

@Serializable
sealed interface SubagentTranscriptStep {
    @Serializable
    @SerialName("reasoning")
    data class Reasoning(
        val text: String,
        val createdAt: Long = 0,
    ) : SubagentTranscriptStep

    @Serializable
    @SerialName("tool_call")
    data class ToolCall(
        val name: String,
        val input: String,
        val output: String,
        val executed: Boolean,
        val childTranscript: List<SubagentTranscriptStep> = emptyList(),
    ) : SubagentTranscriptStep

    @Serializable
    @SerialName("text")
    data class Text(
        val text: String,
    ) : SubagentTranscriptStep
}
