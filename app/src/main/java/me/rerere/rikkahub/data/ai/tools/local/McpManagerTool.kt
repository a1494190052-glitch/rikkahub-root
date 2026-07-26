package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.mcp.McpStatus
import me.rerere.rikkahub.data.ai.mcp.serverUrl
import me.rerere.rikkahub.data.datastore.SettingsStore
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * mcp_manager: 让 AI 直接管理 App 的 MCP 服务器配置(读写 SettingsStore.mcpServers)。
 * 补齐 "软件层面装 MCP" 的闭环: AI 在 workspace 本机把 server 跑在 127.0.0.1 后,
 * 用本工具注册 URL 即完成安装, 无需用户手动进设置页。
 * McpManager 监听 settingsFlow.mcpServers, 配置变更会自动连接/断开。
 */
@OptIn(ExperimentalUuidApi::class)
internal fun buildMcpManagerTool(
    settingsStore: SettingsStore,
    mcpManager: McpManager? = null,
): Tool {
    fun typeOf(config: McpServerConfig): String = when (config) {
        is McpServerConfig.SseTransportServer -> "sse"
        is McpServerConfig.StreamableHTTPServer -> "streamable_http"
    }

    fun configSummary(config: McpServerConfig, status: String?) = buildJsonObject {
        put("id", config.id.toString())
        put("name", config.commonOptions.name)
        put("url", config.serverUrl)
        put("type", typeOf(config))
        put("enabled", config.commonOptions.enable)
        put("tools", config.commonOptions.tools.size)
        if (status != null) put("status", status)
    }

    return Tool(
        name = "mcp_manager",
        description = """
            Manage this app's MCP server configurations (the app is an MCP CLIENT that connects to remote servers over SSE / Streamable-HTTP URL — no stdio).
            Use to install/list/enable/disable/remove MCP servers at the software level: after you have an MCP server RUNNING somewhere with an http(s) URL (e.g. started in the workspace Ubuntu container on 127.0.0.1:PORT), register it here — no need to ask the user to edit settings manually.
            actions:
            - list: show all configured servers (id, name, url, type, enabled, status, tool count)
            - add: register a new server (name, url, type=sse|streamable_http, optional headers object). Returns the new server id.
            - remove: delete a server (id or name)
            - enable / disable: toggle a server (id or name)
            - test: report the live connection status of a server (id or name)
            Note: adding a server here only registers the CLIENT config — the server process itself must already be running and reachable at the url.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put("description", "list | add | remove | enable | disable | test")
                    })
                    put("id", buildJsonObject {
                        put("type", "string")
                        put("description", "Server id (uuid) or exact name — for remove/enable/disable/test")
                    })
                    put("name", buildJsonObject {
                        put("type", "string")
                        put("description", "Display name — for add")
                    })
                    put("url", buildJsonObject {
                        put("type", "string")
                        put("description", "Server http(s) URL, e.g. http://127.0.0.1:3001/sse — for add")
                    })
                    put("type", buildJsonObject {
                        put("type", "string")
                        put("description", "sse | streamable_http (default streamable_http) — for add")
                    })
                    put("headers", buildJsonObject {
                        put("type", "object")
                        put("description", "Optional http headers, e.g. {\"Authorization\": \"Bearer xxx\"} — for add")
                    })
                },
                required = listOf("action"),
            )
        },
        needsApproval = { params ->
            val action = params.jsonObject["action"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            action in setOf("add", "remove", "enable", "disable")
        },
        execute = {
            val params = it.jsonObject
            val action = params["action"]?.jsonPrimitive?.contentOrNull?.trim()
                ?: error("action is required")

            fun findConfig(idOrName: String): McpServerConfig? {
                val needle = idOrName.trim()
                val configs = settingsStore.settingsFlow.value.mcpServers
                return configs.firstOrNull { c -> c.id.toString().equals(needle, true) }
                    ?: configs.firstOrNull { c -> c.id.toString().startsWith(needle, true) }
                    ?: configs.firstOrNull { c -> c.commonOptions.name.equals(needle, true) }
            }

            suspend fun statusOf(config: McpServerConfig): String {
                val manager = mcpManager ?: return "unknown"
                val status = withTimeoutOrNull(4_000) { manager.getStatus(config).first() }
                return when (status) {
                    is McpStatus.Connected -> "connected"
                    is McpStatus.Connecting -> "connecting"
                    is McpStatus.Reconnecting -> "reconnecting(${status.attempt}/${status.maxAttempts})"
                    is McpStatus.Error -> "error: ${status.message}"
                    is McpStatus.NeedsAuthorization -> "needs_authorization"
                    is McpStatus.Authorizing -> "authorizing"
                    else -> "idle"
                }
            }

            when (action) {
                "list" -> {
                    val configs = settingsStore.settingsFlow.value.mcpServers
                    listOf(
                        UIMessagePart.Text(
                            buildJsonObject {
                                put("count", configs.size)
                                put("servers", buildJsonArray {
                                    configs.forEach { c -> add(configSummary(c, null)) }
                                })
                                if (configs.isEmpty()) {
                                    put("hint", "No MCP servers configured. To install one: run the server in the workspace on 127.0.0.1, then mcp_manager add.")
                                }
                            }.toString()
                        )
                    )
                }

                "add" -> {
                    val name = params["name"]?.jsonPrimitive?.contentOrNull?.trim()
                        ?: error("name is required for add")
                    val url = params["url"]?.jsonPrimitive?.contentOrNull?.trim()
                        ?: error("url is required for add")
                    val type = params["type"]?.jsonPrimitive?.contentOrNull?.trim()
                        ?.ifEmpty { "streamable_http" } ?: "streamable_http"
                    val headers = params["headers"]?.jsonObject
                        ?.map { (k, v) -> k to v.jsonPrimitive.contentOrNull.orEmpty() }
                        .orEmpty()
                    val common = McpCommonOptions(name = name, headers = headers)
                    val config: McpServerConfig = when (type) {
                        "sse" -> McpServerConfig.SseTransportServer(
                            id = Uuid.random(), commonOptions = common, url = url,
                        )

                        "streamable_http" -> McpServerConfig.StreamableHTTPServer(
                            id = Uuid.random(), commonOptions = common, url = url,
                        )

                        else -> error("type must be sse or streamable_http")
                    }
                    settingsStore.update { settings ->
                        settings.copy(mcpServers = settings.mcpServers + config)
                    }
                    listOf(
                        UIMessagePart.Text(
                            buildJsonObject {
                                put("ok", true)
                                put("added", configSummary(config, null))
                                put("hint", "Registered. The app connects automatically; use action=test in a moment to verify, then enable this server's tools for the assistant if needed.")
                            }.toString()
                        )
                    )
                }

                "remove" -> {
                    val idOrName = params["id"]?.jsonPrimitive?.contentOrNull
                        ?: error("id (or name) is required for remove")
                    val config = findConfig(idOrName)
                        ?: return@Tool listOf(UIMessagePart.Text("{\"error\": \"server not found: $idOrName\"}"))
                    settingsStore.update { settings ->
                        settings.copy(mcpServers = settings.mcpServers.filterNot { c -> c.id == config.id })
                    }
                    listOf(UIMessagePart.Text(buildJsonObject {
                        put("ok", true)
                        put("removed", configSummary(config, null))
                    }.toString()))
                }

                "enable", "disable" -> {
                    val idOrName = params["id"]?.jsonPrimitive?.contentOrNull
                        ?: error("id (or name) is required for $action")
                    val config = findConfig(idOrName)
                        ?: return@Tool listOf(UIMessagePart.Text("{\"error\": \"server not found: $idOrName\"}"))
                    val target = action == "enable"
                    val updated = config.clone(
                        commonOptions = config.commonOptions.copy(enable = target)
                    )
                    settingsStore.update { settings ->
                        settings.copy(
                            mcpServers = settings.mcpServers.map { c -> if (c.id == config.id) updated else c }
                        )
                    }
                    listOf(UIMessagePart.Text(buildJsonObject {
                        put("ok", true)
                        put(action, configSummary(updated, null))
                    }.toString()))
                }

                "test" -> {
                    val idOrName = params["id"]?.jsonPrimitive?.contentOrNull
                        ?: error("id (or name) is required for test")
                    val config = findConfig(idOrName)
                        ?: return@Tool listOf(UIMessagePart.Text("{\"error\": \"server not found: $idOrName\"}"))
                    listOf(UIMessagePart.Text(configSummary(config, statusOf(config)).toString()))
                }

                else -> error("unknown action: $action (expected list/add/remove/enable/disable/test)")
            }
        },
    )
}
