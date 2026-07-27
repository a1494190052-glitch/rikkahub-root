package me.rerere.rikkahub.plugin.loader

import android.util.Log
import me.rerere.rikkahub.plugin.data.PluginDataStore
import me.rerere.rikkahub.plugin.model.PluginInfo
import me.rerere.rikkahub.plugin.model.PluginManifest
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Plugin loader that manages QuickJS sandbox lifecycle.
 * Uses a single-thread executor to ensure QuickJS thread safety.
 */
class PluginLoader(
    private val pluginDataStoreFactory: (String) -> PluginDataStore,
) {
    companion object {
        private const val TAG = "PluginLoader"
        private const val CALL_TIMEOUT_SECONDS = 16L
    }

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "PluginLoader-QuickJS").apply { isDaemon = true }
    }

    private val loadedPlugins = ConcurrentHashMap<String, LoadedPlugin>()

    /**
     * Load a plugin: create sandbox → inject config → execute entry → verify tools.
     * Must be called from any thread; execution happens on the single QuickJS thread.
     */
    fun loadPlugin(
        pluginDir: File,
        manifest: PluginManifest,
        config: Map<String, String> = emptyMap(),
    ): LoadedPlugin {
        val pluginId = manifest.id
        Log.i(TAG, "Loading plugin: $pluginId from ${pluginDir.absolutePath}")

        // Unload existing if present
        if (loadedPlugins.containsKey(pluginId)) {
            unloadPlugin(pluginId)
        }

        val future = executor.submit<LoadedPlugin> {
            val dataStore = pluginDataStoreFactory(pluginId)
            val sandbox = PluginSandbox(
                pluginId = pluginId,
                allowedHosts = manifest.allowedHosts,
                dataStore = dataStore,
            )

            try {
                // Initialize sandbox
                sandbox.init()

                // Inject user config
                sandbox.injectConfig(config)

                // Read and execute entry file
                val entryFile = File(pluginDir, manifest.entry)
                if (!entryFile.exists()) {
                    throw IllegalStateException("Entry file not found: ${manifest.entry}")
                }
                val entryCode = entryFile.readText()
                sandbox.evaluateFile(entryCode, manifest.entry)

                // Verify that declared tools have corresponding exported functions
                val missingTools = manifest.tools.filter { toolDef ->
                    val checkScript = """
                        (function() {
                            var fn = exports['${toolDef.name}'] || (module.exports && module.exports['${toolDef.name}']);
                            return typeof fn === 'function' ? 'ok' : 'missing';
                        })()
                    """.trimIndent()
                    val result = sandbox.evaluate(checkScript)
                    result?.toString() != "ok"
                }

                if (missingTools.isNotEmpty()) {
                    Log.w(TAG, "Plugin $pluginId missing tool implementations: ${missingTools.map { it.name }}")
                }

                val info = PluginInfo.fromManifest(
                    manifest = manifest,
                    enabled = true,
                    loaded = true,
                    configValues = config,
                )

                val loaded = LoadedPlugin(
                    id = pluginId,
                    info = info,
                    manifest = manifest,
                    sandbox = sandbox,
                    dir = pluginDir,
                )

                loadedPlugins[pluginId] = loaded
                Log.i(TAG, "Plugin loaded successfully: $pluginId (${manifest.tools.size} tools)")
                loaded
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load plugin: $pluginId", e)
                sandbox.destroy()
                throw e
            }
        }

        return try {
            future.get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw RuntimeException("Plugin load timed out: $pluginId", e)
        }
    }

    /**
     * Unload a plugin and destroy its sandbox.
     */
    fun unloadPlugin(pluginId: String) {
        val plugin = loadedPlugins.remove(pluginId) ?: return
        Log.i(TAG, "Unloading plugin: $pluginId")

        executor.submit {
            try {
                plugin.sandbox.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "Error destroying sandbox for $pluginId", e)
            }
        }
    }

    /**
     * Call a tool function on a loaded plugin.
     * Executes on the QuickJS thread with timeout control.
     */
    fun callTool(pluginId: String, toolName: String, paramsJson: String): String {
        val plugin = loadedPlugins[pluginId]
            ?: return """{"error":"Plugin not loaded: $pluginId"}"""

        val future: Future<String> = executor.submit<String> {
            try {
                plugin.sandbox.callFunction(toolName, paramsJson)
            } catch (e: Exception) {
                Log.e(TAG, "Error calling tool $toolName on plugin $pluginId", e)
                """{"error":"${e.message?.replace("\"", "'") ?: "Unknown error"}"}"""
            }
        }

        return try {
            future.get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            """{"error":"Tool execution timed out: $toolName"}"""
        } catch (e: Exception) {
            """{"error":"Tool execution failed: ${e.message?.replace("\"", "'") ?: "Unknown"}"}"""
        }
    }

    /**
     * Broadcast an event to all loaded plugins that have registered hooks for it.
     */
    fun callEvent(event: String, paramsJson: String = "{}") {
        val plugins = loadedPlugins.values.toList()

        executor.submit {
            for (plugin in plugins) {
                val hooks = plugin.manifest.hooks.filter { it.event == event }
                for (hook in hooks) {
                    try {
                        plugin.sandbox.callEvent(hook.handler, paramsJson)
                    } catch (e: Exception) {
                        Log.w(TAG, "Error calling event $event handler ${hook.handler} on plugin ${plugin.id}", e)
                    }
                }
            }
        }
    }

    /**
     * Check if a plugin is loaded.
     */
    fun isLoaded(pluginId: String): Boolean = loadedPlugins.containsKey(pluginId)

    /**
     * Get all loaded plugin IDs.
     */
    fun getLoadedPluginIds(): Set<String> = loadedPlugins.keys.toSet()

    /**
     * Get a loaded plugin by ID.
     */
    fun getLoadedPlugin(pluginId: String): LoadedPlugin? = loadedPlugins[pluginId]

    /**
     * Unload all plugins and shut down the executor.
     */
    fun destroy() {
        loadedPlugins.keys.toList().forEach { unloadPlugin(it) }
        executor.shutdown()
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            executor.shutdownNow()
        }
    }
}
