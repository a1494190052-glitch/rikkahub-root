package me.rerere.rikkahub.data.ai.subagent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

val SUBAGENT_TOOL_NAMES: Set<String> = setOf("spawn_subagent", "ask_btw", "manage_subagent_profile")

fun createSubagentTools(
    profiles: List<SubagentProfile>,
    json: Json,
    spawn: suspend (profileName: String, task: String, description: String) -> SubagentResult,
    askBtw: suspend (question: String) -> String,
    includeAskBtw: Boolean = true,
    resume: (suspend (sessionId: String, followUp: String) -> SubagentResult)? = null,
): List<Tool> {
    if (profiles.isEmpty()) return emptyList()

    val profileNames = profiles.map { it.name }
    val profileListText = profiles.joinToString("\n") { "  - ${it.name}: ${it.description}" }

    val spawnTool = Tool(
        name = "spawn_subagent",
        description = """
Launch a subagent to handle a task autonomously. The subagent runs its own tool loop with a fresh context and reports back a summary.

Writing the task prompt:
- The subagent starts with ZERO context. Brief it like a colleague: state the goal, list what you already know, hand over the specifics.
- Lookups: put the exact path or query in the prompt.
- Investigations: give the question, not prescribed steps.

When to USE: research needing more than 2-3 searches/reads, multi-step tasks with a clear goal, parallelizable subtasks, tasks that would bloat your context.
When to SKIP: single file read, one quick search, answering from knowledge you already have.

Parallel execution: multiple spawn_subagent calls in the SAME response run concurrently.
Follow-ups: a successful spawn returns a session_id — use resume_subagent to ask the SAME subagent a follow-up without redoing its work.

Available profiles:
$profileListText
""".trimIndent(),
        systemPrompt = { _, _ ->
            buildString {
                appendLine()
                appendLine("**Subagents — Delegation Guidance**")
                appendLine("Prefer delegating to the matching specialized subagent rather than doing substantial work inline.")
                appendLine()
                appendLine("**Route work by specialty:**")
                appendLine("- `explore`: research / investigation")
                appendLine("- `coder`: substantial coding or editing tasks")
                appendLine("- `reviewer`: reviews, critiques, second opinions")
                appendLine()
                appendLine("**Parallelize aggressively:** spawn multiple subagents in the SAME response for independent parts.")
                appendLine("**When NOT to delegate:** single quick file read, one search, trivial calculation.")
                appendLine()
                appendLine("<available_subagent_profiles>")
                profiles.forEach { p ->
                    appendLine("  <profile>")
                    appendLine("    <name>${p.name}</name>")
                    appendLine("    <description>${p.description}</description>")
                    appendLine("  </profile>")
                }
                append("</available_subagent_profiles>")
            }
        },
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("profile_name", buildJsonObject {
                        put("type", "string")
                        put("description", "The subagent profile to spawn.")
                        put("enum", kotlinx.serialization.json.buildJsonArray { profileNames.forEach { add(it) } })
                    })
                    put("task", buildJsonObject {
                        put("type", "string")
                        put("description", "Full, self-contained task prompt for the subagent.")
                    })
                    put("description", buildJsonObject {
                        put("type", "string")
                        put("description", "Short 3-5 word description for display (optional)")
                    })
                },
                required = listOf("profile_name", "task"),
            )
        },
        execute = { args ->
            val params = args.jsonObject
            val profileName = params["profile_name"]?.jsonPrimitive?.contentOrNull ?: error("profile_name is required")
            val task = params["task"]?.jsonPrimitive?.contentOrNull ?: error("task is required")
            val description = params["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val result = spawn(profileName, task, description)
            val payload = buildJsonObject {
                put("profile_name", JsonPrimitive(result.profileName))
                put("succeeded", JsonPrimitive(result.succeeded))
                if (!result.error.isNullOrBlank()) put("error", JsonPrimitive(result.error))
                put("summary", JsonPrimitive(result.summary))
                put("depth", JsonPrimitive(result.depth))
                put("steps", JsonPrimitive(result.steps))
                put("tool_calls", JsonPrimitive(result.toolCallCount))
                result.sessionId?.let { put("session_id", JsonPrimitive(it)) }
            }
            val transcriptMetadata = if (result.transcript.isNotEmpty()) {
                buildJsonObject {
                    put("subagent_transcript", json.encodeToJsonElement(
                        kotlinx.serialization.builtins.ListSerializer(SubagentTranscriptStep.serializer()), result.transcript
                    ))
                    put("subagent_profile", JsonPrimitive(result.profileName))
                    put("subagent_steps", JsonPrimitive(result.steps))
                    put("subagent_succeeded", JsonPrimitive(result.succeeded))
                }
            } else null
            listOf(UIMessagePart.Text(text = payload.toString(), metadata = transcriptMetadata))
        },
    )

    val btwTool = Tool(
        name = "ask_btw",
        description = "Ask a lightweight, tool-less side question to a fresh agent instance. Use for quick second opinions.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("question", buildJsonObject { put("type", "string"); put("description", "The self-contained side question to ask") })
                },
                required = listOf("question"),
            )
        },
        execute = { args ->
            val question = args.jsonObject["question"]?.jsonPrimitive?.contentOrNull ?: error("question is required")
            val answer = askBtw(question)
            listOf(UIMessagePart.Text(buildJsonObject { put("answer", JsonPrimitive(answer)) }.toString()))
        },
    )

    val tools = mutableListOf(spawnTool)
    if (includeAskBtw) tools.add(btwTool)
    if (resume != null) {
        tools += Tool(
            name = "resume_subagent",
            description = "Ask a follow-up question to a previously spawned subagent session, reusing its full context (findings, files read, work done). Cheaper than spawning a new subagent for related follow-ups. Get session_id from a successful spawn_subagent result.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("session_id", buildJsonObject {
                            put("type", "string")
                            put("description", "The session_id returned by a previous successful spawn_subagent call")
                        })
                        put("follow_up", buildJsonObject {
                            put("type", "string")
                            put("description", "The follow-up question or additional instruction")
                        })
                    },
                    required = listOf("session_id", "follow_up"),
                )
            },
            execute = { args ->
                val params = args.jsonObject
                val sessionId = params["session_id"]?.jsonPrimitive?.contentOrNull ?: error("session_id is required")
                val followUp = params["follow_up"]?.jsonPrimitive?.contentOrNull ?: error("follow_up is required")
                val result = resume(sessionId, followUp)
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("profile_name", JsonPrimitive(result.profileName))
                    put("succeeded", JsonPrimitive(result.succeeded))
                    if (!result.error.isNullOrBlank()) put("error", JsonPrimitive(result.error))
                    put("summary", JsonPrimitive(result.summary))
                }.toString()))
            },
        )
    }
    return tools
}

fun createManageSubagentTool(
    profiles: List<SubagentProfile>,
    json: Json,
    manage: suspend (action: String, name: String, profile: SubagentProfile?) -> String,
): Tool = Tool(
    name = "manage_subagent_profile",
    description = """Manage subagent profiles (create / update / delete / list). Use to adapt your delegation toolkit.""".trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("enum", kotlinx.serialization.json.buildJsonArray { listOf("list", "create", "update", "delete").forEach { add(it) } })
                    put("description", "One of: list, create, update, delete")
                })
                put("name", buildJsonObject { put("type", "string"); put("description", "Profile name (lowercase [a-z][a-z0-9_]*)") })
                put("display_name", buildJsonObject { put("type", "string") })
                put("profile_description", buildJsonObject { put("type", "string") })
                put("system_prompt", buildJsonObject { put("type", "string") })
                put("model_id", buildJsonObject { put("type", "string") })
                put("inherit_tools", buildJsonObject { put("type", "boolean") })
                put("excluded_tools", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "string") })
                    put("description", "Tool names this subagent may NOT use. Omit to keep the current value. NOTE: passing an empty array [] CLEARS the exclusion list (all inherited tools allowed).")
                })
                put("max_steps", buildJsonObject { put("type", "integer") })
                put("stream_output", buildJsonObject { put("type", "boolean") })
                put("enable_memory", buildJsonObject { put("type", "boolean") })
                put("temperature", buildJsonObject { put("type", "number") })
                put("top_p", buildJsonObject { put("type", "number") })
                put("max_tokens", buildJsonObject { put("type", "integer") })
                put("allow_host_shell_write", buildJsonObject {
                    put("type", "boolean")
                    put("description", "DANGEROUS: true lets this subagent run root shell WRITE commands without approval. Default false (read-only host shell only).")
                })
                put("timeout_seconds", buildJsonObject {
                    put("type", "integer")
                    put("description", "Max wall-clock seconds per spawn before the subagent is failed as timed out. Default 600.")
                })
                put("max_total_tokens", buildJsonObject {
                    put("type", "integer")
                    put("description", "Token budget per spawn; the subagent is aborted and failed when exceeded. 0/omit = unlimited.")
                })
                put("output_schema", buildJsonObject {
                    put("type", "string")
                    put("description", "JSON Schema string. When set, the subagent's final answer must be valid JSON matching it (one auto-retry on invalid output). Empty string clears.")
                })
            },
            required = listOf("action"),
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val action = params["action"]?.jsonPrimitive?.contentOrNull ?: error("action is required")
        val name = params["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        when (action) {
            "list" -> listOf(UIMessagePart.Text(manage("list", "", null)))
            "delete" -> {
                if (name.isBlank()) error("name is required for delete")
                listOf(UIMessagePart.Text(manage("delete", name, null)))
            }
            "create", "update" -> {
                if (name.isBlank()) error("name is required for $action")
                if (!name.matches(SubagentProfile.IdentifierRegex)) error("name must be lowercase [a-z][a-z0-9_]*: $name")
                val base = if (action == "update") profiles.firstOrNull { it.name == name } ?: error("profile '$name' not found") else SubagentProfile(name = name)
                val updated = base.applyPatch(params)
                listOf(UIMessagePart.Text(manage(action, name, updated)))
            }
            else -> error("unknown action: $action")
        }
    },
)

private fun SubagentProfile.applyPatch(params: JsonObject): SubagentProfile {
    fun str(key: String): String? = params[key]?.jsonPrimitive?.contentOrNull
    fun bool(key: String): Boolean? = params[key]?.jsonPrimitive?.booleanOrNull
    fun int(key: String): Int? = params[key]?.jsonPrimitive?.intOrNull
    fun flt(key: String): Float? = params[key]?.jsonPrimitive?.floatOrNull
    fun strList(key: String): List<String> = (params[key] as? JsonArray)?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() } ?: emptyList()
    fun optStrSet(key: String): Set<String>? = if (key in params) strList(key).toSet() else null

    return copy(
        displayName = str("display_name") ?: displayName,
        description = str("profile_description") ?: description,
        systemPrompt = str("system_prompt") ?: systemPrompt,
        chatModelId = str("model_id")?.let { runCatching { Uuid.parse(it) }.getOrNull() } ?: chatModelId,
        temperature = flt("temperature") ?: temperature,
        topP = flt("top_p") ?: topP,
        maxTokens = int("max_tokens") ?: maxTokens,
        maxSteps = int("max_steps") ?: maxSteps,
        inheritTools = bool("inherit_tools") ?: inheritTools,
        streamOutput = bool("stream_output") ?: streamOutput,
        enableMemory = bool("enable_memory") ?: enableMemory,
        excludedTools = optStrSet("excluded_tools") ?: excludedTools,
        allowHostShellWrite = bool("allow_host_shell_write") ?: allowHostShellWrite,
        timeoutSeconds = int("timeout_seconds") ?: timeoutSeconds,
        maxTotalTokens = int("max_total_tokens") ?: maxTotalTokens,
        outputSchema = if ("output_schema" in params) str("output_schema").orEmpty() else outputSchema,
    )
}
