package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.memory.SemanticSearchResult
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.utils.toLocalString
import java.time.LocalDate

fun buildMemoryTools(
    json: Json,
    onCreation: suspend (String) -> AssistantMemory,
    onUpdate: suspend (Int, String) -> AssistantMemory,
    onDelete: suspend (Int) -> Unit,
    onSearch: (suspend (String, Int) -> List<SemanticSearchResult>)? = null,
    onRecall: (suspend (String, Int) -> List<SemanticSearchResult>)? = null,
): List<Tool> = listOf(
    Tool(
        name = "memory_tool",
        description = """
            The memory tool stores and retrieves long-term information across conversations.
            Use `action` to control the operation:
            - `create`: Add a new memory record
            - `edit`: Update an existing memory record
            - `delete`: Remove a memory record
            - `search`: Semantic search across memories (returns ranked results with similarity scores)
            - `recall`: Automatically recall relevant memories based on current conversation context

            Guidelines:
            - No relevant record: `create` + `content`
            - Existing relevant record: `edit` + `id` + `content`
            - Outdated/irrelevant record: `delete` + `id`
            - Find related memories: `search` + `query`
            - Context-aware retrieval: `recall` + `context`

            Memories will automatically appear in the <memories> tag in later conversations.
            Do not store sensitive information (e.g., ethnicity, religion, sexual orientation, political views, sex life, criminal records).
            You may store: preferred name, preferences, plans, work-related notes, chat style preferences, first chat time, etc.
            Do not show memory content directly in the conversation unless the user explicitly asks.
            Today is ${LocalDate.now().toLocalString(true)}.
            Similar memories should be merged; prefer updating existing records.

            Examples:
            {"action":"create","content":"User prefers brief replies and is more active on weekends."}
            {"action":"edit","id":12,"content":"User's preferred name updated to "A-Xing", prefers Chinese replies."}
            {"action":"delete","id":7}
            {"action":"search","query":"user preferences","top_k":5}
            {"action":"recall","context":"The user is asking about their weekend plans"}
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put(
                            "enum",
                            buildJsonArray {
                                add("create")
                                add("edit")
                                add("delete")
                                add("search")
                                add("recall")
                            }
                        )
                        put("description", "Operation to perform: create, edit, delete, search, or recall")
                    })
                    put("id", buildJsonObject {
                        put("type", "integer")
                        put("description", "The id of the memory record (required for edit/delete)")
                    })
                    put("content", buildJsonObject {
                        put("type", "string")
                        put("description", "The content of the memory record (required for create/edit)")
                    })
                    put("query", buildJsonObject {
                        put("type", "string")
                        put("description", "Search query text for semantic search (required for search action)")
                    })
                    put("context", buildJsonObject {
                        put("type", "string")
                        put("description", "Conversation context for recall (required for recall action)")
                    })
                    put("top_k", buildJsonObject {
                        put("type", "integer")
                        put("description", "Number of results to return for search/recall (default 5)")
                    })
                },
                required = listOf("action")
            )
        },
        execute = {
            val params = it.jsonObject
            val action = params["action"]?.jsonPrimitive?.contentOrNull ?: error("action is required")
            val payload = when (action) {
                "create" -> {
                    val content = params["content"]?.jsonPrimitive?.contentOrNull ?: error("content is required for create")
                    json.encodeToJsonElement(AssistantMemory.serializer(), onCreation(content))
                }

                "edit" -> {
                    val id = params["id"]?.jsonPrimitive?.intOrNull ?: error("id is required for edit")
                    val content = params["content"]?.jsonPrimitive?.contentOrNull ?: error("content is required for edit")
                    json.encodeToJsonElement(AssistantMemory.serializer(), onUpdate(id, content))
                }

                "delete" -> {
                    val id = params["id"]?.jsonPrimitive?.intOrNull ?: error("id is required for delete")
                    onDelete(id)
                    buildJsonObject {
                        put("success", true)
                        put("id", id)
                    }
                }

                "search" -> {
                    val query = params["query"]?.jsonPrimitive?.contentOrNull
                        ?: error("query is required for search")
                    val topK = params["top_k"]?.jsonPrimitive?.intOrNull ?: 5
                    val searchFn = onSearch ?: error("search is not available")
                    val results = searchFn(query, topK)
                    buildJsonObject {
                        put("query", query)
                        put("count", results.size)
                        put("results", buildJsonArray {
                            results.forEach { result ->
                                add(buildJsonObject {
                                    put("id", result.memory.id)
                                    put("content", result.memory.content)
                                    put("score", result.score.toString().take(6))
                                    put("importance", result.memory.importance)
                                    put("source", result.memory.source)
                                    put("created_at", result.memory.createdAt)
                                })
                            }
                        })
                    }
                }

                "recall" -> {
                    val context = params["context"]?.jsonPrimitive?.contentOrNull
                        ?: error("context is required for recall")
                    val topK = params["top_k"]?.jsonPrimitive?.intOrNull ?: 5
                    val recallFn = onRecall ?: error("recall is not available")
                    val results = recallFn(context, topK)
                    buildJsonObject {
                        put("context", context)
                        put("count", results.size)
                        put("results", buildJsonArray {
                            results.forEach { result ->
                                add(buildJsonObject {
                                    put("id", result.memory.id)
                                    put("content", result.memory.content)
                                    put("relevance_score", result.score.toString().take(6))
                                    put("importance", result.memory.importance)
                                    put("access_count", result.memory.accessCount)
                                })
                            }
                        })
                    }
                }

                else -> error("unknown action: $action, must be one of [create, edit, delete, search, recall]")
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    )
)
