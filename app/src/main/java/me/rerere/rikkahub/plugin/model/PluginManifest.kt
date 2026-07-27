package me.rerere.rikkahub.plugin.model

import kotlinx.serialization.Serializable

@Serializable
data class PluginManifest(
    val id: String,
    val name: String,
    val version: String = "1.0.0",
    val description: String = "",
    val author: String = "",
    val entry: String = "main.js",
    val icon: String? = null,
    val tools: List<PluginToolDefinition> = emptyList(),
    val permissions: List<String> = emptyList(),
    val allowedHosts: List<String> = emptyList(),
    val config: List<PluginConfigItem> = emptyList(),
    val hooks: List<PluginHook> = emptyList(),
    val ui: PluginUIConfig? = null,
)

@Serializable
data class PluginToolDefinition(
    val name: String,
    val description: String,
    val parameters: List<PluginToolParameter> = emptyList(),
)

@Serializable
data class PluginToolParameter(
    val name: String,
    val type: String = "string",
    val description: String = "",
    val required: Boolean = false,
    val enum: List<String>? = null,
)

@Serializable
data class PluginConfigItem(
    val key: String,
    val label: String,
    val type: String = "text", // text, number, toggle, model
    val default: String = "",
    val description: String = "",
)

@Serializable
data class PluginHook(
    val event: String,
    val handler: String,
)

@Serializable
data class PluginUIConfig(
    val page: String? = null,
    val icon: String? = null,
)
