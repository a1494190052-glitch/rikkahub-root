package me.rerere.rikkahub.plugin.model

import kotlinx.serialization.Serializable

/**
 * Runtime plugin information exposed to the UI layer.
 */
@Serializable
data class PluginInfo(
    val id: String,
    val name: String,
    val version: String = "1.0.0",
    val description: String = "",
    val author: String = "",
    val enabled: Boolean = true,
    val loaded: Boolean = false,
    val hasUI: Boolean = false,
    val toolCount: Int = 0,
    val toolNames: List<String> = emptyList(),
    val permissions: List<String> = emptyList(),
    val configValues: Map<String, String> = emptyMap(),
) {
    companion object {
        fun fromManifest(
            manifest: PluginManifest,
            enabled: Boolean = true,
            loaded: Boolean = false,
            configValues: Map<String, String> = emptyMap(),
        ): PluginInfo = PluginInfo(
            id = manifest.id,
            name = manifest.name,
            version = manifest.version,
            description = manifest.description,
            author = manifest.author,
            enabled = enabled,
            loaded = loaded,
            hasUI = manifest.ui != null,
            toolCount = manifest.tools.size,
            toolNames = manifest.tools.map { it.name },
            permissions = manifest.permissions,
            configValues = configValues,
        )
    }
}
