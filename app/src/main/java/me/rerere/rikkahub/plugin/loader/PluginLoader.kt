package me.rerere.rikkahub.plugin.loader

import android.util.Log
import me.rerere.rikkahub.plugin.data.PluginDataStore
import me.rerere.rikkahub.plugin.model.PluginInfo
import me.rerere.rikkahub.plugin.model.PluginManifest
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Plugin loader that manages QuickJS sandbox lifecycle.
 *
 * Threading model:
 * - Each plugin gets its own single-thread executor (pluginId → ExecutorService).
 * - Different plugins execute in PARALLEL on separate threads.
 * - The same plugin's calls are SERIALIZED (QuickJS context is not thread-safe).
 * - This replaces the old shared SingleThreadExecutor that serialized ALL plugins.
 */
class PluginLoader(
    private val pluginDataStoreFactory: (String) -> PluginDataStore,
) {
    companion object {
        private const val TAG = "PluginLoader"
        private const val CALL_TIMEOUT_SECONDS = 16L
    }

    private val loadedPlugins = ConcurrentHashMap<String, LoadedPlugin>()

    /**
     * Per-plugin executor map. Each plugin has its own single-thread executor
     * to ensure QuickJS thread safety within a plugin while allowing
     * parallel execution across different plugins.
     */
    private val pluginExecutors = ConcurrentHashMap<String, ExecutorService>()

    /**
     * Get or create a dedicated single-thread executor for a plugin.
     */
    private fun getOrCreateExecutor(pluginId: String): ExecutorService {
        return pluginExecutors.getOrPut(pluginId) {
            Executors.newSingleThreadExecutor { r ->
                Thread(r, "Plugin-$pluginId").apply { isDaemon = true }
            }
        }
    }

    /**
     * Load a plugin: create sandbox → inject config → execute entry → verify tools.
     * Execution happens on the plugin's own dedicated thread.
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

        val executor = getOrCreateExecutor(pluginId)

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
     * Shuts down the plugin's dedicated executor.
     */
    fun unloadPlugin(pluginId: String) {
        val plugin = loadedPlugins.remove(pluginId) ?: return
        Log.i(TAG, "Unloading plugin: $pluginId")

        val executor = pluginExecutors.remove(pluginId)
        if (executor != null) {
            executor.submit {
                try {
                    plugin.sandbox.destroy()
                } catch (e: Exception) {
                    Log.w(TAG, "Error destroying sandbox for $pluginId", e)
                }
            }
            executor.shutdown()
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow()
                }
            } catch (e: InterruptedException) {
                executor.shutdownNow()
            }
        } else {
            // Fallback: destroy directly if no executor found
            try {
                plugin.sandbox.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "Error destroying sandbox for $pluginId", e)
            }
        }
    }

    /**
     * Call a tool function on a loaded plugin.
     * Executes on the plugin's own dedicated thread with timeout control.
     * Different plugins can execute in parallel; same plugin calls are serialized.
     */
    fun callTool(pluginId: String, toolName: String, paramsJson: String): String {
        val plugin = loadedPlugins[pluginId]
            ?: return """{"error":"Plugin not loaded: $pluginId"}"""

        val executor = pluginExecutors[pluginId]
            ?: return """{"error":"Plugin executor not found: $pluginId"}"""

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
     * Each plugin's hooks execute on that plugin's own thread (parallel across plugins).
     */
    fun callEvent(event: String, paramsJson: String = "{}") {
        val plugins = loadedPlugins.values.toList()

        for (plugin in plugins) {
            val hooks = plugin.manifest.hooks.filter { it.event == event }
            if (hooks.isEmpty()) continue

            val executor = pluginExecutors[plugin.id] ?: continue
            executor.submit {
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
     * Unload all plugins and shut down all executors.
     */
    fun destroy() {
        loadedPlugins.keys.toList().forEach { unloadPlugin(it) }

        // Ensure all executors are shut down
        pluginExecutors.forEach { (id, executor) ->
            executor.shutdown()
            try {
                if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                    executor.shutdownNow()
                }
            } catch (e: InterruptedException) {
                executor.shutdownNow()
            }
        }
        pluginExecutors.clear()
    }
}
