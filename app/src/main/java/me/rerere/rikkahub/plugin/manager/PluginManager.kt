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
import me.rerere.rikkahub.plugin.loader.PluginLoaderStats
import me.rerere.rikkahub.plugin.model.PluginInfo
import me.rerere.rikkahub.plugin.model.PluginManifest
import me.rerere.rikkahub.plugin.scanner.PluginScanner
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Central plugin lifecycle manager.
 * Handles: scan → import → load → execute → unload
 *
 * Features:
 * - CompletableDeferred for initialization race resolution
 * - Health check: consecutive timeouts auto-disable plugins (threshold = 3)
 * - Concurrent call statistics for UI display
 * - Suspend getTools() for coroutine integration
 */
class PluginManager(
    private val context: Context,
    private val json: Json,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "PluginManager"
        private const val PREFS_NAME = "plugin_manager_prefs"
        private const val KEY_DISABLED_PLUGINS = "disabled_plugins"
        private const val HEALTH_CHECK_THRESHOLD = 3 // consecutive timeouts before auto-disable
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

    // --- Health check state ---
    // Tracks consecutive timeout count per plugin
    private val consecutiveTimeouts = ConcurrentHashMap<String, AtomicInteger>()
    // Set of auto-disabled plugin IDs (due to health check failures)
    private val autoDisabledPlugins = ConcurrentHashMap.newKeySet<String>()

    // --- Call statistics ---
    private val totalToolCalls = AtomicLong(0)
    private val successfulCalls = AtomicLong(0)
    private val failedCalls = AtomicLong(0)
    private val timedOutCalls = AtomicLong(0)
    private val _stats = MutableStateFlow(PluginCallStats())
    val stats: StateFlow<PluginCallStats> = _stats.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            try {
                scanAndLoad()
            } catch (e: Exception) {
                Log.e(TAG, "Error during plugin initialization", e)
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
            val enabled = manifest.id !in disabled && manifest.id !in autoDisabledPlugins
            if (enabled) {
                try {
                    val config = loadConfig(manifest)
                    loader.loadPlugin(dir, manifest, config)
                    pluginInfos.add(
                        PluginInfo.fromManifest(manifest, enabled = true, loaded = true, configValues = config)
                    )
                    // Reset health on successful load
                    consecutiveTimeouts.remove(manifest.id)
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
            val currentlyDisabled = pluginId in disabled || pluginId in autoDisabledPlugins

            if (currentlyDisabled) {
                // Enable (also clears auto-disable)
                disabled.remove(pluginId)
                autoDisabledPlugins.remove(pluginId)
                consecutiveTimeouts.remove(pluginId)
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
            autoDisabledPlugins.remove(pluginId)
            consecutiveTimeouts.remove(pluginId)
            prefs.edit().putStringSet(KEY_DISABLED_PLUGINS, disabled).apply()

            refreshPluginList()
        }
    }

    /**
     * Update a plugin's configuration and reload it.
     */
    fun updateConfig(pluginId: String, config: Map<String, String>) {
        scope.launch(Dispatchers.IO) {
            saveConfig(pluginId, config)

            val entry = manifests[pluginId]
            if (entry != null) {
                val (dir, manifest) = entry
                val disabled = disabledPluginIds
                if (pluginId !in disabled && pluginId !in autoDisabledPlugins) {
                    try {
                        loader.loadPlugin(dir, manifest, config)
                        consecutiveTimeouts.remove(pluginId)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to reload plugin after config update: $pluginId", e)
                    }
                }
            }

            refreshPluginList()
        }
    }

    /**
     * Get all plugin tools (suspend - waits for initialization).
     * No runBlocking - properly integrates with coroutine callers.
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
     * Record a tool call result for health checking and statistics.
     * Called by the tool execution layer after each plugin tool invocation.
     *
     * @param pluginId The plugin that was called
     * @param timedOut Whether the call timed out
     * @param success Whether the call succeeded (no error in result)
     */
    fun recordToolCallResult(pluginId: String, timedOut: Boolean, success: Boolean) {
        totalToolCalls.incrementAndGet()

        if (timedOut) {
            timedOutCalls.incrementAndGet()
            // Health check: increment consecutive timeout counter
            val counter = consecutiveTimeouts.getOrPut(pluginId) { AtomicInteger(0) }
            val count = counter.incrementAndGet()

            if (count >= HEALTH_CHECK_THRESHOLD) {
                Log.w(TAG, "Plugin $pluginId auto-disabled: $count consecutive timeouts")
                autoDisabledPlugins.add(pluginId)
                loader.unloadPlugin(pluginId)
                refreshPluginList()
            }
        } else if (success) {
            successfulCalls.incrementAndGet()
            // Reset consecutive timeout counter on success
            consecutiveTimeouts[pluginId]?.set(0)
        } else {
            failedCalls.incrementAndGet()
            // Non-timeout failures don't affect health check
            consecutiveTimeouts[pluginId]?.set(0)
        }

        updateStats()
    }

    /**
     * Update the stats StateFlow for UI observation.
     */
    private fun updateStats() {
        val loaderStats = loader.getStats()
        _stats.value = PluginCallStats(
            totalCalls = totalToolCalls.get(),
            successfulCalls = successfulCalls.get(),
            failedCalls = failedCalls.get(),
            timedOutCalls = timedOutCalls.get(),
            activeCalls = loaderStats.activeCalls,
            poolSize = loaderStats.poolSize,
            activeThreads = loaderStats.activeThreads,
            queueSize = loaderStats.queueSize,
            autoDisabledPlugins = autoDisabledPlugins.toSet(),
        )
    }

    /**
     * Get current call statistics.
     */
    fun getCallStats(): PluginCallStats = _stats.value

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
     * Check if a plugin is auto-disabled due to health check failures.
     */
    fun isAutoDisabled(pluginId: String): Boolean = pluginId in autoDisabledPlugins

    /**
     * Manually reset health check state for a plugin (re-enable after auto-disable).
     */
    fun resetHealth(pluginId: String) {
        consecutiveTimeouts.remove(pluginId)
        autoDisabledPlugins.remove(pluginId)
    }

    /**
     * Refresh the plugin info list from current state.
     */
    private fun refreshPluginList() {
        val disabled = disabledPluginIds
        val pluginInfos = manifests.map { (id, entry) ->
            val (_, manifest) = entry
            val enabled = id !in disabled && id !in autoDisabledPlugins
            val loaded = loader.isLoaded(id)
            val config = loadConfig(manifest)
            PluginInfo.fromManifest(manifest, enabled = enabled, loaded = loaded, configValues = config)
        }
        _plugins.value = pluginInfos
        updateStats()
    }

    /**
     * Load saved config for a plugin, merged with defaults from manifest.
     */
    private fun loadConfig(manifest: PluginManifest): Map<String, String> {
        val configPrefs = context.getSharedPreferences("plugin_config_${manifest.id}", Context.MODE_PRIVATE)
        val config = mutableMapOf<String, String>()

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

/**
 * Plugin call statistics for UI display.
 */
data class PluginCallStats(
    val totalCalls: Long = 0,
    val successfulCalls: Long = 0,
    val failedCalls: Long = 0,
    val timedOutCalls: Long = 0,
    val activeCalls: Int = 0,
    val poolSize: Int = 0,
    val activeThreads: Int = 0,
    val queueSize: Int = 0,
    val autoDisabledPlugins: Set<String> = emptySet(),
)
