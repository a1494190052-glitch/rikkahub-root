package me.rerere.rikkahub.data.export

import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.AssistantRegex
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import java.util.zip.Inflater
import kotlin.uuid.Uuid

/**
 * SillyTavern 角色卡 (V1/V2/V3) 全保真解析器
 *
 * 支持:
 * - JSON 卡片: V1 (扁平) / V2 / V3 (spec + data 包装)
 * - PNG 卡片: tEXt / zTXt chunk, 关键字 "chara" / "ccv3"
 * - 全字段: description / personality / scenario / first_mes / mes_example /
 *   system_prompt / post_history_instructions / alternate_greetings / character_book
 *
 * {{char}} / {{user}} 占位符保留字面量, 由 PlaceholderTransformer 在发送时替换。
 */
data class CharacterCard(
    val name: String,
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    val firstMes: String = "",
    val mesExample: String = "",
    val systemPrompt: String = "",
    val postHistoryInstructions: String = "",
    val alternateGreetings: List<String> = emptyList(),
    val creatorNotes: String = "",
    val creator: String = "",
    val characterVersion: String = "",
    val tags: List<String> = emptyList(),
    val lorebookEntries: List<PromptInjection.RegexInjection> = emptyList(),
    /** ST 正则脚本 (extensions.regex_scripts), 已映射为原生 AssistantRegex */
    val regexes: List<AssistantRegex> = emptyList(),
    /** ST 作者注释 (extensions.depth_prompt) */
    val depthPrompt: String = "",
    val depthPromptDepth: Int = 4,
    val depthPromptRole: MessageRole = MessageRole.SYSTEM,
) {
    /** 所有开场白 (first_mes + alternate_greetings) */
    val greetings: List<String>
        get() = buildList {
            if (firstMes.isNotBlank()) add(firstMes)
            addAll(alternateGreetings.filter { it.isNotBlank() })
        }

    /**
     * ST 保真的系统提示词组装
     *
     * 顺序与 SillyTavern 默认一致:
     * main prompt (system_prompt 或 ST 默认) -> description -> personality -> scenario -> mes_example
     */
    fun buildSystemPrompt(): String = buildString {
        val main = systemPrompt.ifBlank { DEFAULT_ST_MAIN_PROMPT }
        append(main.trim())
        if (description.isNotBlank()) {
            append("\n\n")
            append(description.trim())
        }
        if (personality.isNotBlank()) {
            append("\n\n")
            append("$name's personality: ")
            append(personality.trim())
        }
        if (scenario.isNotBlank()) {
            append("\n\n")
            append("Scenario: ")
            append(scenario.trim())
        }
        if (mesExample.isNotBlank()) {
            append("\n\n")
            append("Example messages:\n")
            append(mesExample.trim())
        }
    }

    /**
     * 转换为 Assistant (系统提示词 + 开场白)
     *
     * @param greetingIndex 选中的开场白下标 (对应 [greetings])
     * @param background 聊天背景 (PNG 导入时为图片自身)
     * @param lorebookIds 内嵌世界书导入后的 ID 集合
     */
    fun toAssistant(
        greetingIndex: Int = 0,
        background: String? = null,
        lorebookIds: Set<Uuid> = emptySet(),
    ): Assistant {
        return Assistant(
            name = name,
            systemPrompt = buildSystemPrompt(),
            presetMessages = greetings.getOrNull(greetingIndex)?.let { greeting ->
                listOf(me.rerere.ai.ui.UIMessage.assistant(greeting))
            } ?: emptyList(),
            background = background,
            lorebookIds = lorebookIds,
            regexes = regexes.map { it.copy(id = Uuid.random()) },
        )
    }

    /**
     * 内嵌世界书 + post_history_instructions 转换为 Lorebook
     *
     * post_history_instructions 映射为常驻 depth-0 注入 (ST jailbreak 行为),
     * 与 character_book 条目一起放进同一本 Lorebook。
     */
    fun toLorebook(): Lorebook? {
        val entries = buildList {
            addAll(lorebookEntries.map { it.copy(id = Uuid.random()) })
            // depth_prompt (作者注释) -> @Depth 注入
            if (depthPrompt.isNotBlank()) {
                add(
                    PromptInjection.RegexInjection(
                        id = Uuid.random(),
                        name = "Author's Note (Depth Prompt)",
                        enabled = true,
                        priority = 90,
                        position = InjectionPosition.AT_DEPTH,
                        content = depthPrompt.trim(),
                        injectDepth = depthPromptDepth,
                        role = depthPromptRole,
                        keywords = emptyList(),
                        constantActive = true,
                    )
                )
            }
            if (postHistoryInstructions.isNotBlank()) {
                add(
                    PromptInjection.RegexInjection(
                        id = Uuid.random(),
                        name = "Post-History Instructions",
                        enabled = true,
                        priority = 100,
                        position = InjectionPosition.AT_DEPTH,
                        content = postHistoryInstructions.trim(),
                        injectDepth = 0,
                        role = MessageRole.SYSTEM,
                        keywords = emptyList(),
                        constantActive = true,
                    )
                )
            }
        }
        if (entries.isEmpty()) return null
        return Lorebook(
            id = Uuid.random(),
            name = "$name's Lorebook",
            description = "Imported from character card",
            enabled = true,
            entries = entries,
        )
    }

    companion object {
        /** SillyTavern 经典默认 main prompt */
        const val DEFAULT_ST_MAIN_PROMPT =
            "Write {{char}}'s next reply in a fictional roleplay chat between {{char}} and {{user}}. " +
                "Write 1 reply only in internet RP style, italicize actions, and avoid quotation marks. " +
                "Use markdown. Be proactive, creative, and drive the plot and conversation forward. " +
                "Write at least 1 paragraph, up to 4. Always stay in character and avoid repetition."
    }
}

object CharacterCardParser {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 从 PNG 字节中解析角色卡
     *
     * 手写 chunk 扫描, 支持 tEXt 与 zTXt, 关键字 "chara" (V1/V2) 与 "ccv3" (V3)
     */
    fun parsePng(bytes: ByteArray): Result<CharacterCard> = runCatching {
        val jsonString = extractPngCharacterJson(bytes)
            ?: error("No character data found in PNG (missing chara/ccv3 chunk)")
        parseJson(jsonString).getOrThrow()
    }

    /**
     * 从 JSON 字符串解析角色卡 (自动识别 V1/V2/V3)
     */
    fun parseJson(jsonString: String): Result<CharacterCard> = runCatching {
        val root = json.parseToJsonElement(jsonString).jsonObject
        val spec = root["spec"]?.jsonPrimitive?.contentOrNull
        val data: JsonObject = when {
            // V2/V3: spec + data 包装
            spec == "chara_card_v2" || spec == "chara_card_v3" ->
                root["data"]?.jsonObject ?: error("Missing data field")
            // 无 spec 但带 data 对象, 宽容处理
            root["data"] is JsonObject && root["name"] == null ->
                root["data"]!!.jsonObject
            // V1: 扁平结构
            else -> root
        }
        data.toCharacterCard()
    }

    private fun JsonObject.toCharacterCard(): CharacterCard {
        return CharacterCard(
            name = str("name") ?: error("Missing name field"),
            description = str("description").orEmpty(),
            personality = str("personality").orEmpty(),
            scenario = str("scenario").orEmpty(),
            firstMes = str("first_mes").orEmpty(),
            mesExample = str("mes_example").orEmpty(),
            systemPrompt = str("system_prompt").orEmpty(),
            postHistoryInstructions = str("post_history_instructions").orEmpty(),
            alternateGreetings = strList("alternate_greetings"),
            creatorNotes = str("creator_notes").orEmpty(),
            creator = str("creator").orEmpty(),
            characterVersion = str("character_version").orEmpty(),
            tags = strList("tags"),
            lorebookEntries = parseCharacterBook(this["character_book"]),
            regexes = parseRegexScripts(this["extensions"]),
            depthPrompt = parseDepthPrompt(this["extensions"])?.first ?: "",
            depthPromptDepth = parseDepthPrompt(this["extensions"])?.second ?: 4,
            depthPromptRole = parseDepthPrompt(this["extensions"])?.third ?: MessageRole.SYSTEM,
        )
    }

    // region extensions (regex_scripts / depth_prompt)

    private fun parseDepthPrompt(extensions: JsonElement?): Triple<String, Int, MessageRole>? {
        val ext = extensions as? JsonObject ?: return null
        val dp = ext["depth_prompt"] as? JsonObject ?: return null
        val prompt = dp.str("prompt") ?: return null
        if (prompt.isBlank()) return null
        val role = when (dp.str("role")?.lowercase()) {
            "user" -> MessageRole.USER
            "assistant" -> MessageRole.ASSISTANT
            else -> MessageRole.SYSTEM
        }
        return Triple(prompt, dp.int("depth") ?: 4, role)
    }

    /**
     * 解析 ST 正则脚本 (extensions.regex_scripts) 并映射为原生 AssistantRegex
     *
     * 映射规则:
     * - placement: 1 -> USER scope, 2 -> ASSISTANT scope (空 = 两者)
     * - markdownOnly/displayOnly = true -> visualOnly = true (仅显示层)
     * - promptOnly = true -> visualOnly = false (仅发送层)
     * - 两者都 false -> 生成显示 + 发送两条 (ST 语义为两侧都应用)
     * - findRegex: JS 字面量 /pattern/flags -> Kotlin 内联 flag 前缀
     * - minDepth/maxDepth: RikkaHub 正则无消息深度概念, 忽略
     */
    private fun parseRegexScripts(extensions: JsonElement?): List<AssistantRegex> {
        val ext = extensions as? JsonObject ?: return emptyList()
        val scripts = ext["regex_scripts"] as? JsonArray ?: return emptyList()
        return scripts.mapNotNull { element ->
            val script = element as? JsonObject ?: return@mapNotNull null
            val rawFind = script.str("findRegex") ?: return@mapNotNull null
            if (rawFind.isBlank()) return@mapNotNull null
            val findRegex = convertJsRegex(rawFind)
            // 编译验证, 跳过不兼容的脚本
            runCatching { Regex(findRegex) }.getOrNull() ?: return@mapNotNull null

            val replaceString = script.str("replaceString").orEmpty()
            val name = script.str("scriptName").orEmpty().ifEmpty { "ST Script" }
            val enabled = !(script.bool("disabled") ?: false)

            val placements = script["placement"]?.let { el ->
                runCatching { el.jsonArray.mapNotNull { it.jsonPrimitive.intOrNull } }.getOrNull()
            } ?: emptyList()
            val scopes = buildSet {
                if (placements.isEmpty() || 1 in placements) add(AssistantAffectScope.USER)
                if (placements.isEmpty() || 2 in placements) add(AssistantAffectScope.ASSISTANT)
            }
            if (scopes.isEmpty()) return@mapNotNull null

            val visualOnly = (script.bool("markdownOnly") ?: false) || (script.bool("displayOnly") ?: false)
            val promptOnly = script.bool("promptOnly") ?: false
            val minDepth = script.int("minDepth")
            val maxDepth = script.int("maxDepth")

            val base = AssistantRegex(
                id = Uuid.random(),
                name = name,
                enabled = enabled,
                findRegex = findRegex,
                replaceString = replaceString,
                affectingScope = scopes,
                minDepth = minDepth,
                maxDepth = maxDepth,
            )
            when {
                // 仅显示层 (transient)
                visualOnly -> listOf(base.copy(visualOnly = true))
                // 仅发送层 (transient, 不动存储与显示)
                promptOnly -> listOf(base.copy(promptOnly = true))
                // ST 语义: 显示 + 发送都应用 (均 transient), 拆成两条
                else -> listOf(
                    base.copy(id = Uuid.random(), visualOnly = true),
                    base.copy(promptOnly = true),
                )
            }
        }.flatten()
    }

    /**
     * JS 正则字面量转 Kotlin 正则
     *
     * - /pattern/flags 形式提取 flags, 转为内联 (?i)(?s)(?m) 前缀
     * - 裸模式直接使用
     * - JS 转义 \/ 还原为 /
     */
    internal fun convertJsRegex(jsRegex: String): String {
        var pattern = jsRegex.trim()
        var flags = ""
        if (pattern.length > 2 && pattern.startsWith("/")) {
            val lastSlash = pattern.lastIndexOf('/')
            val tail = pattern.substring(lastSlash + 1)
            if (lastSlash > 0 && tail.all { it in "dgimsuvy" }) {
                flags = tail
                pattern = pattern.substring(1, lastSlash)
            }
        }
        pattern = pattern.replace("\\/", "/")
        val prefix = buildString {
            if ('i' in flags) append("(?i)")
            if ('s' in flags) append("(?s)")
            if ('m' in flags) append("(?m)")
        }
        return prefix + pattern
    }

    // endregion

    // region character_book

    /**
     * character_book.entries 双兼容:
     * - 卡内嵌格式: entries 为数组
     * - ST 独立世界书格式: entries 为 Map (uid -> entry)
     */
    private fun parseCharacterBook(book: JsonElement?): List<PromptInjection.RegexInjection> {
        if (book !is JsonObject) return emptyList()
        val entriesElement = book["entries"] ?: return emptyList()
        val entries: List<JsonObject> = when (entriesElement) {
            is JsonArray -> entriesElement.mapNotNull { it as? JsonObject }
            is JsonObject -> entriesElement.values.mapNotNull { it as? JsonObject }
            else -> emptyList()
        }
        return entries.mapNotNull { entry -> entry.toRegexInjection() }
    }

    private fun JsonObject.toRegexInjection(): PromptInjection.RegexInjection? {
        val content = str("content") ?: return null
        if (content.isBlank()) return null
        val keys = strList("key") + strList("keys")
        val comment = str("comment").orEmpty()
        return PromptInjection.RegexInjection(
            id = Uuid.random(),
            name = comment.ifEmpty { keys.firstOrNull().orEmpty() },
            enabled = !(bool("disable") ?: bool("disabled") ?: false),
            priority = int("order") ?: 100,
            position = when (int("position")) {
                0 -> InjectionPosition.BEFORE_SYSTEM_PROMPT
                2 -> InjectionPosition.TOP_OF_CHAT
                3 -> InjectionPosition.TOP_OF_CHAT
                4 -> InjectionPosition.AT_DEPTH
                else -> InjectionPosition.AFTER_SYSTEM_PROMPT
            },
            injectDepth = int("depth") ?: 4,
            content = content,
            keywords = keys,
            useRegex = bool("use_regex") ?: false,
            caseSensitive = bool("case_sensitive") ?: false,
            scanDepth = int("scan_depth") ?: 4,
            constantActive = bool("constant") ?: bool("always_on") ?: false,
        )
    }

    // endregion

    // region PNG chunk scanning

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )

    /**
     * 扫描 PNG chunks, 从 tEXt/zTXt/iTXt 中提取角色 JSON
     */
    private fun extractPngCharacterJson(bytes: ByteArray): String? {
        if (bytes.size < 8 || !PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] }) {
            return null
        }
        var offset = 8
        while (offset + 8 <= bytes.size) {
            val length = readIntBE(bytes, offset)
            val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
            val dataStart = offset + 8
            if (length < 0 || dataStart + length > bytes.size) break
            when (type) {
                "tEXt" -> {
                    extractTextChunk(bytes, dataStart, length, compressed = false)?.let { return it }
                }
                "zTXt" -> {
                    extractTextChunk(bytes, dataStart, length, compressed = true)?.let { return it }
                }
                "iTXt" -> {
                    extractItxtChunk(bytes, dataStart, length)?.let { return it }
                }
                "IEND" -> return null
            }
            offset = dataStart + length + 4 // skip CRC
        }
        return null
    }

    /** tEXt: keyword\0text ; zTXt: keyword\0compressionMethod(1B) compressedText */
    private fun extractTextChunk(bytes: ByteArray, start: Int, length: Int, compressed: Boolean): String? {
        val end = start + length
        var nul = -1
        for (i in start until end) {
            if (bytes[i] == 0.toByte()) {
                nul = i
                break
            }
        }
        if (nul < 0) return null
        val keyword = String(bytes, start, nul - start, Charsets.ISO_8859_1)
        if (!keyword.equals("chara", ignoreCase = true) && !keyword.equals("ccv3", ignoreCase = true)) {
            return null
        }
        val textStart = if (compressed) nul + 2 else nul + 1 // zTXt 多 1 字节压缩方式
        if (textStart >= end) return null
        val raw = if (compressed) {
            inflate(bytes, textStart, end - textStart)
        } else {
            bytes.copyOfRange(textStart, end)
        }
        // 内容为 base64 编码的 JSON
        return runCatching {
            String(Base64.decode(String(raw, Charsets.ISO_8859_1).trim(), Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull()
    }

    /** iTXt: keyword\0 compressionFlag(1B) compressionMethod(1B) languageTag\0 translatedKeyword\0 text */
    private fun extractItxtChunk(bytes: ByteArray, start: Int, length: Int): String? {
        val end = start + length
        var pos = start
        fun readUntilNul(): String? {
            var nul = -1
            for (i in pos until end) {
                if (bytes[i] == 0.toByte()) {
                    nul = i
                    break
                }
            }
            if (nul < 0) return null
            val s = String(bytes, pos, nul - pos, Charsets.ISO_8859_1)
            pos = nul + 1
            return s
        }
        val keyword = readUntilNul() ?: return null
        if (!keyword.equals("chara", ignoreCase = true) && !keyword.equals("ccv3", ignoreCase = true)) {
            return null
        }
        if (pos + 2 > end) return null
        val compressed = bytes[pos] != 0.toByte()
        pos += 2 // compressionFlag + compressionMethod
        readUntilNul() ?: return null // language tag
        readUntilNul() ?: return null // translated keyword
        if (pos >= end) return null
        val raw = if (compressed) inflate(bytes, pos, end - pos) else bytes.copyOfRange(pos, end)
        return runCatching {
            String(Base64.decode(String(raw, Charsets.ISO_8859_1).trim(), Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun inflate(bytes: ByteArray, offset: Int, length: Int): ByteArray {
        val inflater = Inflater()
        inflater.setInput(bytes, offset, length)
        val out = java.io.ByteArrayOutputStream(length * 2)
        val buffer = ByteArray(8192)
        while (!inflater.finished()) {
            val count = inflater.inflate(buffer)
            if (count == 0) {
                if (inflater.needsInput() || inflater.needsDictionary()) break
            }
            out.write(buffer, 0, count)
        }
        inflater.end()
        return out.toByteArray()
    }

    private fun readIntBE(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }

    // endregion

    // region json helpers

    private fun JsonObject.str(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.int(key: String): Int? =
        this[key]?.jsonPrimitive?.intOrNull

    private fun JsonObject.bool(key: String): Boolean? =
        this[key]?.jsonPrimitive?.booleanOrNull

    private fun JsonObject.strList(key: String): List<String> {
        val element = this[key] ?: return emptyList()
        return runCatching {
            element.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
        }.getOrDefault(emptyList())
    }

    // endregion
}
