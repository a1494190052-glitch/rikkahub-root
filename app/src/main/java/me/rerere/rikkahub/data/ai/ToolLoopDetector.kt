package me.rerere.rikkahub.data.ai

import android.util.Log
import kotlinx.serialization.json.*
import java.security.MessageDigest

/**
 * Per-session tool-call sliding window detector.
 * Ported from OpenMinis's ToolLoopDetector with adaptations for RikkaHub's
 * kotlinx.serialization-based tool pipeline.
 *
 * Detects four classes of agent tool-call loops:
 *   1. unknown_tool_repeat    — consecutive hallucinated-tool errors
 *   2. global_circuit_breaker — same args + same result ≥30 times
 *   3. known_poll_no_progress — poll-style tool with frozen results
 *   4. generic_repeat         — same args ≥10 times regardless of result
 *
 * One instance per generateText() call. Not thread-safe; the agent loop
 * is single-threaded per step (tools within a step run in parallel but
 * check/record are called sequentially before/after the parallel block).
 */

data class ToolCallRecord(
    val toolName: String,
    val argsHash: String,
    val resultHash: String? = null,
    val unknownToolName: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
)

enum class LoopLevel { NONE, WARNING, CRITICAL }

data class LoopCheckResult(
    val level: LoopLevel,
    val message: String? = null,
) {
    companion object {
        val NONE = LoopCheckResult(LoopLevel.NONE)
    }
}

data class ToolLoopConfig(
    val historySize: Int = 30,
    val warningThreshold: Int = 10,
    val unknownToolThreshold: Int = 10,
    val criticalThreshold: Int = 20,
    val globalCircuitBreakerThreshold: Int = 30,
) {
    init {
        require(warningThreshold > 0)
        require(warningThreshold < criticalThreshold)
        require(criticalThreshold < globalCircuitBreakerThreshold)
        require(historySize >= globalCircuitBreakerThreshold)
    }
}

class ToolLoopDetector(
    private val config: ToolLoopConfig = ToolLoopConfig(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    companion object {
        private const val TAG = "ToolLoopDetector"
        private val ARGS_HASH_IGNORED_KEYS = setOf("tool_title")
        private val UNKNOWN_TOOL_RE_1 = Regex(
            """unknown tool[:\s]+["']?([a-zA-Z0-9_.\-]+)["']?""", RegexOption.IGNORE_CASE
        )
        private val UNKNOWN_TOOL_RE_2 = Regex(
            """tool\s+["']?([a-zA-Z0-9_.\-]+)["']?\s+(?:not found|is not available)""", RegexOption.IGNORE_CASE
        )
        private val HEX = "0123456789abcdef".toCharArray()
    }

    private val history = ArrayDeque<ToolCallRecord>()
    private val warningBuckets = HashMap<String, Int>()

    fun reset() {
        history.clear()
        warningBuckets.clear()
    }

    // ─── before-execution hook ──────────────────────────────────────────

    /**
     * Check if the about-to-run tool shows loop patterns.
     * Returns CRITICAL to block execution, WARNING to append a nudge.
     */
    fun check(toolName: String, argsJson: String): LoopCheckResult {
        val argsHash = argsHashFor(toolName, argsJson)

        // 1. unknown_tool_repeat
        val unknownStreak = countUnknownStreakFromTail(toolName)
        if (unknownStreak >= config.unknownToolThreshold) {
            Log.w(TAG, "CRITICAL unknown_tool_repeat tool=$toolName streak=$unknownStreak")
            return LoopCheckResult(LoopLevel.CRITICAL,
                "[LOOP BLOCKED] Attempted unavailable tool '$toolName' $unknownStreak times. Stop retrying and answer without it.")
        }

        val noProgressStreak = getNoProgressStreak(toolName, argsHash)

        // 2. global_circuit_breaker
        if (noProgressStreak >= config.globalCircuitBreakerThreshold) {
            Log.w(TAG, "CRITICAL global_circuit_breaker tool=$toolName streak=$noProgressStreak")
            return LoopCheckResult(LoopLevel.CRITICAL,
                "[LOOP BLOCKED] $toolName repeated identical no-progress outcomes $noProgressStreak times. Blocked by circuit breaker.")
        }

        // 3. known_poll_no_progress
        if (isPollTool(toolName)) {
            if (noProgressStreak >= config.criticalThreshold) {
                Log.w(TAG, "CRITICAL poll_no_progress tool=$toolName streak=$noProgressStreak")
                return LoopCheckResult(LoopLevel.CRITICAL,
                    "[LOOP BLOCKED] Called $toolName $noProgressStreak times with identical results. Blocked.")
            }
            if (noProgressStreak >= config.warningThreshold) {
                return LoopCheckResult(LoopLevel.WARNING,
                    "[LOOP WARNING] Called $toolName $noProgressStreak times with no progress. Stop polling or increase wait time.")
            }
        }

        // 4. generic_repeat
        if (!isPollTool(toolName)) {
            val totalCount = history.count { it.toolName == toolName && it.argsHash == argsHash }
            if (totalCount >= config.warningThreshold) {
                val key = "repeat:$toolName:$argsHash"
                if (shouldEmitWarning(key, totalCount)) {
                    return LoopCheckResult(LoopLevel.WARNING,
                        "[LOOP WARNING] Called $toolName $totalCount times with identical arguments. If not making progress, stop and report failure.")
                }
            }
        }

        return LoopCheckResult.NONE
    }

    // ─── after-execution hook ───────────────────────────────────────────

    /**
     * Record the completed tool call. May return a WARNING to append.
     */
    fun record(
        toolName: String,
        argsJson: String,
        result: String?,
        errorMessage: String? = null,
    ): LoopCheckResult {
        val argsHash = argsHashFor(toolName, argsJson)
        val resultHash = resultHashFor(result, errorMessage)
        val unknownToolName = extractUnknownToolName(errorMessage)

        history.addLast(ToolCallRecord(
            toolName = toolName,
            argsHash = argsHash,
            resultHash = resultHash,
            unknownToolName = unknownToolName,
        ))
        while (history.size > config.historySize) history.removeFirst()

        // Post-record warning evaluation
        if (isPollTool(toolName)) {
            val streak = getNoProgressStreak(toolName, argsHash)
            if (streak in config.warningThreshold until config.criticalThreshold) {
                val key = "poll:$toolName:$argsHash"
                if (shouldEmitWarning(key, streak)) {
                    return LoopCheckResult(LoopLevel.WARNING,
                        "[LOOP WARNING] Called $toolName $streak times with no progress.")
                }
            }
        } else {
            val totalCount = history.count { it.toolName == toolName && it.argsHash == argsHash }
            if (totalCount >= config.warningThreshold) {
                val key = "repeat:$toolName:$argsHash"
                if (shouldEmitWarning(key, totalCount)) {
                    return LoopCheckResult(LoopLevel.WARNING,
                        "[LOOP WARNING] Called $toolName $totalCount times with identical arguments.")
                }
            }
        }

        return LoopCheckResult.NONE
    }

    // ─── strategy helpers ───────────────────────────────────────────────

    private fun countUnknownStreakFromTail(toolName: String): Int {
        var streak = 0
        for (rec in history.reversed()) {
            val unk = rec.unknownToolName ?: break
            if (unk == toolName) streak++ else break
        }
        return streak
    }

    private fun getNoProgressStreak(toolName: String, argsHash: String): Int {
        var streak = 0
        var pinnedHash: String? = null
        for (rec in history.reversed()) {
            if (rec.toolName != toolName || rec.argsHash != argsHash) continue
            val rh = rec.resultHash ?: break
            if (pinnedHash == null) { pinnedHash = rh; streak++ }
            else if (rh == pinnedHash) streak++
            else break
        }
        return streak
    }

    private fun isPollTool(toolName: String): Boolean {
        return toolName == "workspace_shell_task_output" || toolName == "command_status"
    }

    private fun shouldEmitWarning(warningKey: String, currentCount: Int): Boolean {
        val bucket = currentCount / config.warningThreshold
        val last = warningBuckets[warningKey]
        if (last == bucket) return false
        warningBuckets[warningKey] = bucket
        return true
    }

    // ─── hashing ────────────────────────────────────────────────────────

    private fun argsHashFor(toolName: String, argsJson: String): String {
        // Parse and re-serialize with sorted keys, excluding cosmetic fields
        val normalized = try {
            val obj = json.parseToJsonElement(argsJson).jsonObject
            val filtered = obj.filterKeys { it !in ARGS_HASH_IGNORED_KEYS }
            stableJson(JsonObject(filtered))
        } catch (_: Exception) {
            argsJson
        }
        return sha256("$toolName:$normalized")
    }

    private fun stableJson(element: JsonElement): String = buildString {
        appendStable(element)
    }

    private fun StringBuilder.appendStable(element: JsonElement) {
        when (element) {
            is JsonNull -> append("null")
            is JsonPrimitive -> {
                if (element.isString) append('"').append(element.content.replace("\"", "\\\"")).append('"')
                else append(element.content)
            }
            is JsonObject -> {
                append('{')
                element.entries.sortedBy { it.key }.forEachIndexed { i, (k, v) ->
                    if (i > 0) append(',')
                    append('"').append(k).append('"').append(':')
                    appendStable(v)
                }
                append('}')
            }
            is JsonArray -> {
                append('[')
                element.forEachIndexed { i, v ->
                    if (i > 0) append(',')
                    appendStable(v)
                }
                append(']')
            }
        }
    }

    private fun resultHashFor(result: String?, errorMessage: String?): String {
        val payload = "err=${errorMessage ?: ""}\u0001out=${result ?: ""}"
        return sha256(payload)
    }

    private fun extractUnknownToolName(errorMessage: String?): String? {
        if (errorMessage.isNullOrBlank()) return null
        UNKNOWN_TOOL_RE_1.find(errorMessage)?.groupValues?.getOrNull(1)?.let { return it }
        UNKNOWN_TOOL_RE_2.find(errorMessage)?.groupValues?.getOrNull(1)?.let { return it }
        return null
    }

    private fun sha256(s: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        return buildString(bytes.size * 2) {
            for (b in bytes) {
                val v = b.toInt() and 0xFF
                append(HEX[v ushr 4])
                append(HEX[v and 0x0F])
            }
        }
    }
}
