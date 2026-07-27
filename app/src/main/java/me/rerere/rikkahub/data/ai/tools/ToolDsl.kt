package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema

/**
 * 工具参数 DSL：消除"schema 声明 + execute 解析"两处重复。
 *
 * 之前定义一个参数要在两处各写一遍参数名与类型（schema 声明 + execute 手动 jsonPrimitive 解析），
 * 参数名平均出现 3 次，改一处易漏另一处，且无编译期约束。
 *
 * 本 DSL 产物仍是 [InputSchema.Obj]，零侵入兼容现有 [me.rerere.ai.core.Tool]。
 * 配合 [ToolArgs] 做类型安全解析，参数名只需声明一次。
 *
 * 用法：
 * ```
 * private val spec = params {
 *     string("action", "read or write", required = true, enum = listOf("read", "write"))
 *     string("text", "Text to write")
 * }
 * Tool(
 *     name = "clipboard_tool",
 *     description = "...",
 *     parameters = { spec },
 *     execute = { input ->
 *         val a = ToolArgs(input)
 *         when (a.str("action")) { "write" -> write(a.str("text")) }
 *         ...
 *     },
 * )
 * ```
 */
class ToolParamsBuilder {
    private val props = linkedMapOf<String, JsonObject>()
    private val req = mutableListOf<String>()

    private fun add(
        name: String,
        type: String,
        description: String,
        required: Boolean,
        extra: (JsonObjectBuilder.() -> Unit)? = null,
    ) {
        props[name] = buildJsonObject {
            put("type", type)
            put("description", description)
            extra?.invoke(this)
        }
        if (required) req += name
    }

    /** 字符串参数。[enum] 非空时生成枚举约束。 */
    fun string(name: String, description: String, required: Boolean = false, enum: List<String>? = null) =
        add(name, "string", description, required) {
            enum?.let { values -> put("enum", buildJsonArray { values.forEach { add(it) } }) }
        }

    /** 整数参数。 */
    fun int(name: String, description: String, required: Boolean = false) =
        add(name, "integer", description, required)

    /** 浮点/数值参数。 */
    fun number(name: String, description: String, required: Boolean = false) =
        add(name, "number", description, required)

    /** 布尔参数。 */
    fun bool(name: String, description: String, required: Boolean = false) =
        add(name, "boolean", description, required)

    /** 字符串数组参数。 */
    fun stringArray(name: String, description: String, required: Boolean = false) =
        add(name, "array", description, required) {
            put("items", buildJsonObject { put("type", "string") })
        }

    fun build(): InputSchema.Obj = InputSchema.Obj(
        properties = buildJsonObject { props.forEach { (k, v) -> put(k, v) } },
        required = req.ifEmpty { null },
    )
}

/** 声明工具参数 schema，返回可直接传给 [me.rerere.ai.core.Tool.parameters] 的 [InputSchema.Obj]。 */
fun params(block: ToolParamsBuilder.() -> Unit): InputSchema.Obj =
    ToolParamsBuilder().apply(block).build()

/**
 * 类型安全的工具参数解析器，替代手写的 `jsonPrimitive?.contentOrNull ?: error(...)`。
 *
 * @param input 工具 execute 收到的原始 JsonElement（应为 JsonObject）
 */
class ToolArgs(input: kotlinx.serialization.json.JsonElement) {
    val raw: JsonObject = input as? JsonObject
        ?: error("Tool arguments must be a JSON object")

    /** 必填字符串，缺失时抛错。 */
    fun str(name: String): String = raw[name]?.jsonPrimitive?.contentOrNull ?: error("$name is required")

    /** 可选字符串。 */
    fun strOpt(name: String): String? = raw[name]?.jsonPrimitive?.contentOrNull

    /** 整数，缺失返回 [default]。 */
    fun int(name: String, default: Int = 0): Int = raw[name]?.jsonPrimitive?.intOrNull ?: default

    /** 可选整数。 */
    fun intOpt(name: String): Int? = raw[name]?.jsonPrimitive?.intOrNull

    /** 浮点数，缺失返回 [default]。 */
    fun float(name: String, default: Float = 0f): Float = raw[name]?.jsonPrimitive?.floatOrNull ?: default

    /** 布尔，缺失返回 [default]。 */
    fun bool(name: String, default: Boolean = false): Boolean = raw[name]?.jsonPrimitive?.booleanOrNull ?: default

    /** 可选布尔。 */
    fun boolOpt(name: String): Boolean? = raw[name]?.jsonPrimitive?.booleanOrNull

    /** 字符串数组，缺失返回空列表。 */
    fun strList(name: String): List<String> =
        (raw[name] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
            ?: emptyList()
}
