package me.rerere.rikkahub.plugin.loader

import me.rerere.rikkahub.plugin.model.PluginInfo
import me.rerere.rikkahub.plugin.model.PluginManifest
import java.io.File

/**
 * Represents a loaded plugin with its runtime state.
 */
data class LoadedPlugin(
    val id: String,
    val info: PluginInfo,
    val manifest: PluginManifest,
    val sandbox: PluginSandbox,
    val dir: File,
)
