package me.rerere.rikkahub.plugin.provider

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.plugin.loader.PluginLoader
import me.rerere.rikkahub.plugin.model.PluginManifest
import me.rerere.rikkahub.plugin.model.PluginToolDefinition

/**
 * Converts plugin tool definitions into AI Tool objects.
 * Tool naming convention: plugin_{pluginId}_{toolName}
 * All plugin tools require user approval (needsApproval = true).
 */
object PluginToolProvider {

    /**
     * Create an AI Tool from a plugin tool definition.
     */
    fun createTool(
        pluginId: String,
        toolDef: PluginToolDefinition,
        loader: PluginLoader,
    ): Tool {
        val fullName = "plugin_${pluginId}_${toolDef.name}"

        return Tool(
            name = fullName,
            description = "[Plugin: $pluginId] ${toolDef.description}",
            parameters = {
                buildInputSchema(toolDef)
            },
            needsApproval = { true }, // Plugin tools always require approval
            execute = { input: JsonElement ->
                val paramsJson = input.toString()
                val result = loader.callTool(pluginId, toolDef.name, paramsJson)
                listOf(UIMessagePart.Text(result))
            },
        )
    }

    /**
     * Build an InputSchema from plugin tool parameter definitions.
     */
    private fun buildInputSchema(toolDef: PluginToolDefinition): InputSchema? {
        if (toolDef.parameters.isEmpty()) return null

        val properties = buildJsonObject {
            for (param in toolDef.parameters) {
                put(param.name, buildJsonObject {
                    put("type", mapType(param.type))
                    if (param.description.isNotBlank()) {
                        put("description", param.description)
                    }
                    if (param.enum != null && param.enum.isNotEmpty()) {
                        put("enum", kotlinx.serialization.json.JsonArray(
                            param.enum.map { kotlinx.serialization.json.JsonPrimitive(it) }
                        ))
                    }
                })
            }
        }

        val required = toolDef.parameters
            .filter { it.required }
            .map { it.name }
            .takeIf { it.isNotEmpty() }

        return InputSchema.Obj(
            properties = properties,
            required = required,
        )
    }

    /**
     * Map plugin parameter types to JSON Schema types.
     */
    private fun mapType(type: String): String {
        return when (type.lowercase()) {
            "string", "text" -> "string"
            "number", "int", "integer", "float", "double" -> "number"
            "boolean", "bool", "toggle" -> "boolean"
            "array", "list" -> "array"
            "object", "map" -> "object"
            else -> "string"
        }
    }

    /**
     * Generate system prompt injections describing available plugin tools.
     * This tells the AI what plugin tools are available and how to use them.
     */
    fun getPluginPromptInjections(plugins: List<PluginManifest>): String {
        val pluginsWithTools = plugins.filter { it.tools.isNotEmpty() }
        if (pluginsWithTools.isEmpty()) return ""

        val sb = StringBuilder()
        sb.appendLine("## Available Plugin Tools")
        sb.appendLine()

        for (manifest in pluginsWithTools) {
            sb.appendLine("### Plugin: ${manifest.name} (v${manifest.version})")
            if (manifest.description.isNotBlank()) {
                sb.appendLine(manifest.description)
            }
            sb.appendLine()

            for (tool in manifest.tools) {
                val fullName = "plugin_${manifest.id}_${tool.name}"
                sb.appendLine("- **$fullName**: ${tool.description}")
                if (tool.parameters.isNotEmpty()) {
                    sb.appendLine("  Parameters:")
                    for (param in tool.parameters) {
                        val req = if (param.required) " (required)" else ""
                        val enumStr = param.enum?.let { " [${it.joinToString(", ")}]" } ?: ""
                        sb.appendLine("  - ${param.name}: ${param.type}$req - ${param.description}$enumStr")
                    }
                }
            }
            sb.appendLine()
        }

        return sb.toString()
    }
}
