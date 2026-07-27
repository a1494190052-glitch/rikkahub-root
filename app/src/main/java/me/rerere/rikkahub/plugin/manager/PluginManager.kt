package me.rerere.rikkahub.plugin.manager

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.plugin.data.PluginDataStore
import me.rerere.rikkahub.plugin.loader.PluginLoader
import me.rerere.rikkahub.plugin.model.PluginInfo
import me.rerere.rikkahub.plugin.model.PluginManifest
import me.rerere.rikkahub.plugin.scanner.PluginScanner
import java.io.File

/**
 * Central plugin lifecycle manager.
 * Handles: scan → import → load → execute → unload
 * Uses CompletableDeferred to resolve initialization race conditions.
 */
class PluginManager(
    private val context: Context,
    private val json: Json,
    private val scope: CoroutineScope,
    private val errorReporter: ErrorReporter? = null,
) {
    companion object {
        private const val TAG = "PluginManager"
        private const val PREFS_NAME = "plugin_manager_prefs"
        private const val KEY_DISABLED_PLUGINS = "disabled_plugins"
    }

    private val scanner = PluginScanner(context, json)
    private val loader = PluginLoader { pluginId -> PluginDataStore(context, pluginId) }

    private val _plugins = MutableStateFlow<List<PluginInfo>>(emptyList())
    val plugins: StateFlow<List<PluginInfo>> = _plugins.asStateFlow()

    private val _initialized = CompletableDeferred<Unit>()
    val initialized = _initialized

    // Persisted disabled plugin IDs
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val disabledPluginIds: MutableSet<String>
        get() = prefs.getStringSet(KEY_DISABLED_PLUGINS, emptySet())?.toMutableSet() ?: mutableSetOf()

    private var manifests: Map<String, Pair<File, PluginManifest>> = emptyMap()

    init {
        scope.launch(Dispatchers.IO) {
            try {
                scanAndLoad()
            } catch (e: Exception) {
                Log.e(TAG, "Error during plugin initialization", e)
                errorReporter?.report(e, "插件系统初始化失败")
            } finally {
                _initialized.complete(Unit)
            }
        }
    }

    /**
     * Scan plugins directory and load all enabled plugins.
     */
    private fun scanAndLoad() {
        val scanned = scanner.scanPlugins()
        manifests = scanned.associate { (dir, manifest) -> manifest.id to (dir to manifest) }

        val disabled = disabledPluginIds
        val pluginInfos = mutableListOf<PluginInfo>()

        for ((dir, manifest) in scanned) {
            val enabled = manifest.id !in disabled
            if (enabled) {
                try {
                    val config = loadConfig(manifest)
                    loader.loadPlugin(dir, manifest, config)
                    pluginInfos.add(
                        PluginInfo.fromManifest(manifest, enabled = true, loaded = true, configValues = config)
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load plugin: ${manifest.id}", e)
                    pluginInfos.add(
                        PluginInfo.fromManifest(manifest, enabled = true, loaded = false)
                    )
                }
            } else {
                pluginInfos.add(
                    PluginInfo.fromManifest(manifest, enabled = false, loaded = false)
                )
            }
        }

        _plugins.value = pluginInfos
        Log.i(TAG, "Plugin scan complete: ${pluginInfos.size} plugins, ${pluginInfos.count { it.loaded }} loaded")
    }

    /**
     * Scan plugins directory (refresh).
     */
    fun scanPlugins() {
        scope.launch(Dispatchers.IO) {
            scanAndLoad()
        }
    }

    /**
     * Load all enabled plugins (alias for scanAndLoad).
     */
    fun loadAll() {
        scope.launch(Dispatchers.IO) {
            scanAndLoad()
        }
    }

    /**
     * Toggle a plugin's enabled state.
     */
    fun togglePlugin(pluginId: String) {
        scope.launch(Dispatchers.IO) {
            val disabled = disabledPluginIds
            val currentlyDisabled = pluginId in disabled

            if (currentlyDisabled) {
                // Enable
                disabled.remove(pluginId)
                prefs.edit().putStringSet(KEY_DISABLED_PLUGINS, disabled).apply()

                // Load the plugin
                val entry = manifests[pluginId]
                if (entry != null) {
                    val (dir, manifest) = entry
                    try {
                        val config = loadConfig(manifest)
                        loader.loadPlugin(dir, manifest, config)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to load plugin on enable: $pluginId", e)
                        errorReporter?.report(e, "启用插件失败: $pluginId")
                    }
                }
            } else {
                // Disable
                disabled.add(pluginId)
                prefs.edit().putStringSet(KEY_DISABLED_PLUGINS, disabled).apply()
                loader.unloadPlugin(pluginId)
            }

            refreshPluginList()
        }
    }

    /**
     * Import a plugin from a zip file.
     */
    fun importPlugin(zipFile: File): Result<PluginManifest> {
        val result = scanner.importPlugin(zipFile)
        if (result.isSuccess) {
            scope.launch(Dispatchers.IO) {
                scanAndLoad()
            }
        }
        return result
    }

    /**
     * Delete a plugin by ID.
     */
    fun deletePlugin(pluginId: String) {
        scope.launch(Dispatchers.IO) {
            loader.unloadPlugin(pluginId)
            scanner.deletePlugin(pluginId)
            PluginDataStore(context, pluginId).destroy()

            val disabled = disabledPluginIds
            disabled.remove(pluginId)
            prefs.edit().putStringSet(KEY_DISABLED_PLUGINS, disabled).apply()

            refreshPluginList()
        }
    }

    /**
     * Update a plugin's configuration and reload it.
     */
    fun updateConfig(pluginId: String, config: Map<String, String>) {
        scope.launch(Dispatchers.IO) {
            // Save config
            saveConfig(pluginId, config)

            // Reload plugin with new config
            val entry = manifests[pluginId]
            if (entry != null) {
                val (dir, manifest) = entry
                val disabled = disabledPluginIds
                if (pluginId !in disabled) {
                    try {
                        loader.loadPlugin(dir, manifest, config)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to reload plugin after config update: $pluginId", e)
                        errorReporter?.report(e, "重载插件配置失败: $pluginId")
                    }
                }
            }

            refreshPluginList()
        }
    }

    /**
     * Get all plugin tools (waits for initialization).
     */
    suspend fun getTools(): List<me.rerere.ai.core.Tool> {
        _initialized.await()
        return loader.getLoadedPluginIds().flatMap { pluginId ->
            val plugin = loader.getLoadedPlugin(pluginId) ?: return@flatMap emptyList()
            plugin.manifest.tools.map { toolDef ->
                me.rerere.rikkahub.plugin.provider.PluginToolProvider.createTool(
                    pluginId = pluginId,
                    toolDef = toolDef,
                    loader = loader,
                )
            }
        }
    }

    /**
     * Get the plugin loader for direct tool calls.
     */
    fun getLoader(): PluginLoader = loader

    /**
     * Get the plugin scanner.
     */
    fun getScanner(): PluginScanner = scanner

    /**
     * Broadcast an event to all plugins.
     */
    fun emitEvent(event: String, paramsJson: String = "{}") {
        loader.callEvent(event, paramsJson)
    }

    /**
     * Refresh the plugin info list from current state.
     */
    private fun refreshPluginList() {
        val disabled = disabledPluginIds
        val pluginInfos = manifests.map { (id, entry) ->
            val (_, manifest) = entry
            val enabled = id !in disabled
            val loaded = loader.isLoaded(id)
            val config = loadConfig(manifest)
            PluginInfo.fromManifest(manifest, enabled = enabled, loaded = loaded, configValues = config)
        }
        _plugins.value = pluginInfos
    }

    /**
     * Load saved config for a plugin, merged with defaults from manifest.
     */
    private fun loadConfig(manifest: PluginManifest): Map<String, String> {
        val configPrefs = context.getSharedPreferences("plugin_config_${manifest.id}", Context.MODE_PRIVATE)
        val config = mutableMapOf<String, String>()

        // Start with defaults from manifest
        for (item in manifest.config) {
            config[item.key] = configPrefs.getString(item.key, item.default) ?: item.default
        }

        return config
    }

    /**
     * Save config for a plugin.
     */
    private fun saveConfig(pluginId: String, config: Map<String, String>) {
        val configPrefs = context.getSharedPreferences("plugin_config_$pluginId", Context.MODE_PRIVATE)
        val editor = configPrefs.edit()
        config.forEach { (key, value) -> editor.putString(key, value) }
        editor.apply()
    }

    /**
     * Destroy the plugin manager and release resources.
     */
    fun destroy() {
        loader.destroy()
    }
}
