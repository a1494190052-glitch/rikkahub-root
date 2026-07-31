package me.rerere.rikkahub.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.core.ReasoningLevel
import me.rerere.rikkahub.data.ai.subagent.SubagentProfile
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.utils.SimpleCache
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

@Serializable
data class Assistant(
    val id: Uuid = Uuid.random(),
    val chatModelId: Uuid? = null,
    val name: String = "",
    val avatar: Avatar = Avatar.Dummy,
    val useAssistantAvatar: Boolean = false,
    val tags: List<Uuid> = emptyList(),
    val systemPrompt: String = "",
    val temperature: Float? = null,
    val topP: Float? = null,
    val contextMessageSize: Int = 0,
    val streamOutput: Boolean = true,
    val enableMemory: Boolean = false,
    val useGlobalMemory: Boolean = false,
    val enableRecentChatsReference: Boolean = false,
    val messageTemplate: String = "{{ message }}",
    val presetMessages: List<UIMessage> = emptyList(),
    val quickMessageIds: Set<Uuid> = emptySet(),
    val regexes: List<AssistantRegex> = emptyList(),
    val reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
    val maxTokens: Int? = null,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBodies: List<CustomBody> = emptyList(),
    val mcpServers: Set<Uuid> = emptySet(),
    val localTools: List<LocalToolOption> = listOf(LocalToolOption.TimeInfo),
    val enableWebSearch: Boolean = false,
    val workspaceId: Uuid? = null,
    val background: String? = null,
    val backgroundOpacity: Float = 1.0f,
    val useGradientBackground: Boolean = false,
    val modeInjectionIds: Set<Uuid> = emptySet(),
    val lorebookIds: Set<Uuid> = emptySet(),
    val enabledSkills: Set<String> = emptySet(),
    val enableTimeReminder: Boolean = false,
    val allowConversationSystemPrompt: Boolean = false,
    val allowConversationPromptInjection: Boolean = false,
    // ---- 子代理系统 (kimi-code) ----
    val enableSubagents: Boolean = false,
    val subagentProfiles: List<SubagentProfile> = emptyList(),
    val disabledBuiltinSubagents: Set<String> = emptySet(),
    val subagentMaxDepth: Int = 2,
)

@Serializable
data class QuickMessage(
    val id: Uuid = Uuid.random(),
    val title: String = "",
    val content: String = "",
)

@Serializable
data class AssistantMemory(
    val id: Int,
    val content: String = "",
)

@Serializable
enum class AssistantAffectScope {
    USER,
    ASSISTANT,
}

@Serializable
data class AssistantRegex(
    val id: Uuid,
    val name: String = "",
    val enabled: Boolean = true,
    val findRegex: String = "",
    val replaceString: String = "",
    val affectingScope: Set<AssistantAffectScope> = setOf(),
    val visualOnly: Boolean = false,
    val promptOnly: Boolean = false,
    val minDepth: Int? = null,
    val maxDepth: Int? = null,
)

enum class RegexApplyMode { VISUAL, OUTPUT, PROMPT }

private val regexCache = SimpleCache.builder<String, Result<Regex>>()
    .expireAfterWrite(10, TimeUnit.MINUTES)
    .build()

private fun compileRegexCached(pattern: String): Regex? {
    regexCache.getIfPresent(pattern)?.let { return it.getOrNull() }
    val result = runCatching { Regex(pattern) }.onFailure { it.printStackTrace() }
    regexCache.put(pattern, result)
    return result.getOrNull()
}

internal fun resolveJsReplacement(replacement: String, match: MatchResult): String {
    val out = StringBuilder(replacement.length + 16)
    var i = 0
    while (i < replacement.length) {
        val c = replacement[i]
        if (c == '$' && i + 1 < replacement.length) {
            when (val next = replacement[i + 1]) {
                '$' -> { out.append('$'); i += 2 }
                '&' -> { out.append(match.value); i += 2 }
                in '0'..'9' -> {
                    var num = next - '0'
                    var consumed = 2
                    if (i + 2 < replacement.length && replacement[i + 2] in '0'..'9') {
                        val twoDigit = num * 10 + (replacement[i + 2] - '0')
                        if (twoDigit <= match.groupValues.size - 1) { num = twoDigit; consumed = 3 }
                    }
                    if (num in 1..match.groupValues.size - 1) out.append(match.groupValues[num])
                    i += consumed
                }
                else -> { out.append(c); i += 1 }
            }
        } else if (c == '\\' && i + 1 < replacement.length && replacement[i + 1] == '$') {
            out.append('$'); i += 2
        } else {
            out.append(c); i += 1
        }
    }
    return out.toString()
}

fun String.replaceRegexes(
    assistant: Assistant?,
    scope: AssistantAffectScope,
    mode: RegexApplyMode = RegexApplyMode.OUTPUT,
    depth: Int? = null,
): String {
    if (assistant == null) return this
    if (assistant.regexes.isEmpty()) return this
    return assistant.regexes.fold(this) { acc, regex ->
        val modeMatch = when (mode) {
            RegexApplyMode.VISUAL -> regex.visualOnly
            RegexApplyMode.OUTPUT -> !regex.visualOnly && !regex.promptOnly
            RegexApplyMode.PROMPT -> regex.promptOnly
        }
        val depthMatch = if (mode == RegexApplyMode.PROMPT && depth != null) {
            (regex.minDepth?.let { depth >= it } ?: true) && (regex.maxDepth?.let { depth <= it } ?: true)
        } else true
        if (regex.enabled && modeMatch && depthMatch && regex.affectingScope.contains(scope)) {
            val compiled = compileRegexCached(regex.findRegex) ?: return@fold acc
            try {
                acc.replace(compiled) { match -> resolveJsReplacement(regex.replaceString, match) }
            } catch (e: Exception) {
                e.printStackTrace()
                acc
            }
        } else acc
    }
}

@Serializable
enum class InjectionPosition {
    @SerialName("before_system_prompt") BEFORE_SYSTEM_PROMPT,
    @SerialName("after_system_prompt") AFTER_SYSTEM_PROMPT,
    @SerialName("top_of_chat") TOP_OF_CHAT,
    @SerialName("bottom_of_chat") BOTTOM_OF_CHAT,
    @SerialName("at_depth") AT_DEPTH,
}

@Serializable
sealed class PromptInjection {
    abstract val id: Uuid
    abstract val name: String
    abstract val enabled: Boolean
    abstract val priority: Int
    abstract val position: InjectionPosition
    abstract val content: String
    abstract val injectDepth: Int
    abstract val role: MessageRole

    @Serializable
    @SerialName("mode")
    data class ModeInjection(
        override val id: Uuid = Uuid.random(),
        override val name: String = "",
        override val enabled: Boolean = true,
        override val priority: Int = 0,
        override val position: InjectionPosition = InjectionPosition.AFTER_SYSTEM_PROMPT,
        override val content: String = "",
        override val injectDepth: Int = 4,
        override val role: MessageRole = MessageRole.USER,
    ) : PromptInjection()

    @Serializable
    @SerialName("regex")
    data class RegexInjection(
        override val id: Uuid = Uuid.random(),
        override val name: String = "",
        override val enabled: Boolean = true,
        override val priority: Int = 0,
        override val position: InjectionPosition = InjectionPosition.AFTER_SYSTEM_PROMPT,
        override val content: String = "",
        override val injectDepth: Int = 4,
        override val role: MessageRole = MessageRole.USER,
        val keywords: List<String> = emptyList(),
        val useRegex: Boolean = false,
        val caseSensitive: Boolean = false,
        val scanDepth: Int = 4,
        val constantActive: Boolean = false,
    ) : PromptInjection()
}

@Serializable
data class Lorebook(
    val id: Uuid = Uuid.random(),
    val name: String = "",
    val description: String = "",
    val enabled: Boolean = true,
    val entries: List<PromptInjection.RegexInjection> = emptyList(),
)

/**
 * 编译后的正则缓存：isTriggered 每次匹配都重新 Regex.compile 非常昂贵
 * （每条消息、每个 keyword 都编译一次）。缓存后同一 keyword 只编译一次。
 * 10 分钟过期防止无界增长；ConcurrentHashMap 线程安全。
 */
private val regexCache = SimpleCache.builder<String, Regex>()
    .expireAfterWrite(10, TimeUnit.MINUTES)
    .build()

fun PromptInjection.RegexInjection.isTriggered(context: String): Boolean {
    if (!enabled) return false
    if (constantActive) return true
    if (keywords.isEmpty()) return false
    return keywords.any { keyword ->
        if (useRegex) {
            try {
                val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
                val cacheKey = keyword + '\u0000' + (if (caseSensitive) 1 else 0)
                val regex = regexCache.getIfPresent(cacheKey)
                    ?: Regex(keyword, options).also { regexCache.put(cacheKey, it) }
                regex.containsMatchIn(context)
            } catch (e: Exception) { false }
        } else {
            if (caseSensitive) context.contains(keyword) else context.contains(keyword, ignoreCase = true)
        }
    }
}

fun extractContextForMatching(messages: List<UIMessage>, scanDepth: Int): String {
    return messages.takeLast(scanDepth).joinToString("\n") { it.toText() }
}

fun getTriggeredInjections(
    injections: List<PromptInjection.RegexInjection>,
    context: String
): List<PromptInjection.RegexInjection> {
    return injections.filter { it.isTriggered(context) }.sortedByDescending { it.priority }
}
