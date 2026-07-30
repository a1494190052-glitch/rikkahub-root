package me.rerere.rikkahub.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ContentBlock
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import kotlin.coroutines.cancellation.CancellationException

/**
 * Bridges RikkaHub's internal local tools ([me.rerere.ai.core.Tool]) to the MCP Kotlin SDK server.
 *
 * Approval note: inside the app, tool approval gates live in ChatService (outside [Tool.execute]).
 * Calling [Tool.execute] directly therefore bypasses the interactive per-call approval UI.
 * This is intentional for MCP: the Bearer token is the authorization boundary, so any client
 * holding the token may run every exposed tool (including root shell). See SettingMcpServerPage.
 */

/** Full list of local tool options. [LocalToolOption] is a sealed class (no `.entries`), so list manually. */
fun allLocalToolOptions(): List<LocalToolOption> = listOf(
    LocalToolOption.JavascriptEngine,
    LocalToolOption.TimeInfo,
    LocalToolOption.Clipboard,
    LocalToolOption.Tts,
    LocalToolOption.AskUser,
    LocalToolOption.AskBtw,
    LocalToolOption.ScreenTime,
    LocalToolOption.Calendar,
    LocalToolOption.RootShell,
    LocalToolOption.SubAgents,
    LocalToolOption.Scheduler,
    LocalToolOption.Battery,
    LocalToolOption.Brightness,
    LocalToolOption.Torch,
    LocalToolOption.Vibrate,
    LocalToolOption.Volume,
    LocalToolOption.WakeScreen,
    LocalToolOption.WifiInfo,
    LocalToolOption.TelephonyInfo,
    LocalToolOption.StorageInfo,
    LocalToolOption.Toast,
    LocalToolOption.PostNotification,
    LocalToolOption.Share,
    LocalToolOption.ScanMedia,
    LocalToolOption.BrowserUse,
)

/**
 * Builds a fresh MCP [Server] with the tools capability enabled and every local tool registered.
 * Called once per new MCP session (the streamable-http factory invokes this lazily).
 */
fun createMcpServer(localTools: LocalTools): Server {
    val server = Server(
        serverInfo = Implementation(name = "rikkahub-mcp-server", version = "1.0.0"),
        options = ServerOptions(
            // tools capability MUST be declared, otherwise addTool throws IllegalStateException
            capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true)),
        ),
    )
    registerAllTools(server, localTools.getTools(allLocalToolOptions()))
    return server
}

/** Registers every internal [Tool] on the MCP [server], adapting schema + result types. */
fun registerAllTools(server: Server, tools: List<Tool>) {
    tools.forEach { tool ->
        server.addTool(
            name = tool.name,
            description = tool.description,
            inputSchema = tool.parameters()?.toToolSchema() ?: ToolSchema(),
        ) { request ->
            // JsonObject is a JsonElement, so it can be passed straight to execute()
            val args = request.arguments ?: JsonObject(emptyMap())
            try {
                val parts = tool.execute(args)
                CallToolResult(content = parts.toContentBlocks())
            } catch (e: CancellationException) {
                // Propagate coroutine cancellation; do not swallow it as a tool error.
                throw e
            } catch (e: Exception) {
                // Tool-level errors are reported inside the result (isError = true), per MCP spec.
                CallToolResult(
                    content = listOf(TextContent("Error: ${e.message ?: e.toString()}")),
                    isError = true,
                )
            }
        }
    }
}

private fun InputSchema.toToolSchema(): ToolSchema = when (this) {
    is InputSchema.Obj -> ToolSchema(properties = properties, required = required)
}

/** Converts internal UI message parts into MCP content blocks. */
private fun List<UIMessagePart>.toContentBlocks(): List<ContentBlock> {
    val blocks = mutableListOf<ContentBlock>()
    for (part in this) {
        when (part) {
            is UIMessagePart.Text -> blocks += TextContent(part.text)
            is UIMessagePart.Image -> {
                val dataUri = parseDataUri(part.url)
                if (dataUri != null && dataUri.base64 && dataUri.mimeType.startsWith("image/")) {
                    blocks += ImageContent(data = dataUri.data, mimeType = dataUri.mimeType)
                } else {
                    // Not an inline base64 image -> degrade to a text reference
                    blocks += TextContent(part.url)
                }
            }
            else -> blocks += TextContent(part.toFallbackText())
        }
    }
    if (blocks.isEmpty()) {
        blocks += TextContent("(no output)")
    }
    return blocks
}

private data class DataUri(
    val mimeType: String,
    val data: String,
    val base64: Boolean,
)

/** Parses `data:<mime>[;base64],<payload>`; returns null when [uri] is not a data URI. */
private fun parseDataUri(uri: String): DataUri? {
    if (!uri.startsWith("data:")) return null
    val comma = uri.indexOf(',')
    if (comma < 0) return null
    val meta = uri.substring("data:".length, comma)
    val data = uri.substring(comma + 1)
    val base64 = meta.endsWith(";base64")
    val mimeType = meta.removeSuffix(";base64").ifEmpty { "text/plain" }
    return DataUri(mimeType = mimeType, data = data, base64 = base64)
}

/** Reasonable plain-text representation for parts that have no direct MCP content block. */
private fun UIMessagePart.toFallbackText(): String = when (this) {
    is UIMessagePart.Text -> text
    is UIMessagePart.Image -> url
    is UIMessagePart.Video -> "[video] $url"
    is UIMessagePart.Audio -> "[audio] $url"
    is UIMessagePart.Document -> "[document] $fileName ($mime) $url"
    is UIMessagePart.Reasoning -> reasoning
    is UIMessagePart.Tool -> buildString {
        append("[tool] ").append(toolName)
        if (output.isNotEmpty()) {
            append(":\n").append(output.joinToString("\n") { it.toFallbackText() })
        }
    }
    // Search / legacy ToolCall / ToolResult etc.
    else -> "[${this::class.simpleName ?: "part"}]"
}
