package me.rerere.rikkahub.plugin.loader

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import me.rerere.rikkahub.plugin.data.PluginDataStore
import me.rerere.rikkahub.plugin.model.PluginInfo
import me.rerere.rikkahub.plugin.model.PluginManifest
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Plugin loader that manages QuickJS sandbox lifecycle with a thread pool.
 *
 * Threading model:
 * - Shared ThreadPoolExecutor (core=2, max=4) for parallel plugin execution
 * - Per-plugin single-thread executor ensures serial execution within a plugin
 *   (QuickJS contexts are NOT thread-safe; one plugin = one context = one thread at a time)
 * - Plugins execute in parallel across the pool; each plugin's calls are serialized
 *
 * This allows multiple plugins to run concurrently while maintaining per-plugin state consistency.
 */
class PluginLoader(
    private val pluginDataStoreFactory: (String) -> PluginDataStore,
) {
    companion object {
        private const val TAG = "PluginLoader"
        private const val CALL_TIMEOUT_SECONDS = 16L
        private const val CORE_POOL_SIZE = 2
        private const val MAX_POOL_SIZE = 4
        private const val KEEP_ALIVE_SECONDS = 30L
    }

    // Shared thread pool for parallel plugin execution
    private val threadPool = ThreadPoolExecutor(
        CORE_POOL_SIZE,
        MAX_POOL_SIZE,
        KEEP_ALIVE_SECONDS,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(32),
        object : ThreadFactory {
            private val counter = AtomicInteger(0)
            override fun newThread(r: Runnable): Thread {
                return Thread(r, "PluginPool-${counter.getAndIncrement()}").apply { isDaemon = true }
            }
        },
        ThreadPoolExecutor.CallerRunsPolicy()
    )

    // Per-plugin single-thread executors for serialization within each plugin
    private val pluginExecutors = ConcurrentHashMap<String, java.util.concurrent.ExecutorService>()

    private val loadedPlugins = ConcurrentHashMap<String, LoadedPlugin>()

    // Statistics
    private val totalCalls = AtomicLong(0)
    private val totalTimeouts = AtomicLong(0)
    private val activeCalls = AtomicInteger(0)

    /**
     * Get or create a per-plugin executor (single-thread for serialization).
     */
    private fun getPluginExecutor(pluginId: String): java.util.concurrent.ExecutorService {
        return pluginExecutors.getOrPut(pluginId) {
            Executors.newSingleThreadExecutor { r ->
                Thread(r, "Plugin-$pluginId").apply { isDaemon = true }
            }
        }
    }

    /**
     * Load a plugin: create sandbox → inject config → execute entry → verify tools.
     * Execution happens on the plugin's dedicated thread.
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

        val executor = getPluginExecutor(pluginId)
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

                // Read and execute entry file (no ES5 preprocessing - native ES2020+)
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
                            const fn = exports['${toolDef.name}'] || (module.exports && module.exports['${toolDef.name}']);
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

        val executor = pluginExecutors[pluginId]
        if (executor != null) {
            executor.submit {
                try {
                    plugin.sandbox.destroy()
                } catch (e: Exception) {
                    Log.w(TAG, "Error destroying sandbox for $pluginId", e)
                }
            }
            // Shutdown the per-plugin executor after a delay to allow cleanup
            executor.shutdown()
            pluginExecutors.remove(pluginId)
        } else {
            // Fallback: destroy on shared pool
            threadPool.submit {
                try {
                    plugin.sandbox.destroy()
                } catch (e: Exception) {
                    Log.w(TAG, "Error destroying sandbox for $pluginId", e)
                }
            }
        }
    }

    /**
     * Call a tool function on a loaded plugin (synchronous with timeout).
     * Executes on the plugin's dedicated thread to ensure serialization.
     */
    fun callTool(pluginId: String, toolName: String, paramsJson: String): String {
        val plugin = loadedPlugins[pluginId]
            ?: return """{"error":"Plugin not loaded: $pluginId"}"""

        totalCalls.incrementAndGet()
        activeCalls.incrementAndGet()

        val executor = getPluginExecutor(pluginId)
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
            totalTimeouts.incrementAndGet()
            """{"error":"Tool execution timed out: $toolName"}"""
        } catch (e: Exception) {
            """{"error":"Tool execution failed: ${e.message?.replace("\"", "'") ?: "Unknown"}"}"""
        } finally {
            activeCalls.decrementAndGet()
        }
    }

    /**
     * Call a tool function asynchronously, returning a Deferred.
     * Enables parallel tool calls across different plugins.
     * Each plugin's calls are still serialized via its dedicated executor.
     */
    fun callToolAsync(pluginId: String, toolName: String, paramsJson: String): Deferred<String> {
        val deferred = CompletableDeferred<String>()

        val plugin = loadedPlugins[pluginId]
        if (plugin == null) {
            deferred.complete("""{"error":"Plugin not loaded: $pluginId"}""")
            return deferred
        }

        totalCalls.incrementAndGet()
        activeCalls.incrementAndGet()

        // Submit to the shared thread pool, which will dispatch to plugin executor
        threadPool.submit {
            val executor = getPluginExecutor(pluginId)
            val future: Future<String> = executor.submit<String> {
                try {
                    plugin.sandbox.callFunction(toolName, paramsJson)
                } catch (e: Exception) {
                    Log.e(TAG, "Error calling tool $toolName on plugin $pluginId", e)
                    """{"error":"${e.message?.replace("\"", "'") ?: "Unknown error"}"}"""
                }
            }

            try {
                val result = future.get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                deferred.complete(result)
            } catch (e: TimeoutException) {
                future.cancel(true)
                totalTimeouts.incrementAndGet()
                deferred.complete("""{"error":"Tool execution timed out: $toolName"}""")
            } catch (e: Exception) {
                deferred.complete("""{"error":"Tool execution failed: ${e.message?.replace("\"", "'") ?: "Unknown"}"}""")
            } finally {
                activeCalls.decrementAndGet()
            }
        }

        return deferred
    }

    /**
     * Broadcast an event to all loaded plugins that have registered hooks for it.
     */
    fun callEvent(event: String, paramsJson: String = "{}") {
        val plugins = loadedPlugins.values.toList()

        for (plugin in plugins) {
            val hooks = plugin.manifest.hooks.filter { it.event == event }
            if (hooks.isEmpty()) continue

            val executor = getPluginExecutor(plugin.id)
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
     * Get execution statistics.
     */
    fun getStats(): PluginLoaderStats {
        return PluginLoaderStats(
            totalCalls = totalCalls.get(),
            totalTimeouts = totalTimeouts.get(),
            activeCalls = activeCalls.get(),
            loadedPluginCount = loadedPlugins.size,
            poolSize = threadPool.poolSize,
            activeThreads = threadPool.activeCount,
            queueSize = threadPool.queue.size,
        )
    }

    /**
     * Unload all plugins and shut down executors.
     */
    fun destroy() {
        loadedPlugins.keys.toList().forEach { unloadPlugin(it) }

        // Shutdown per-plugin executors
        pluginExecutors.values.forEach { it.shutdownNow() }
        pluginExecutors.clear()

        // Shutdown shared pool
        threadPool.shutdown()
        try {
            if (!threadPool.awaitTermination(5, TimeUnit.SECONDS)) {
                threadPool.shutdownNow()
            }
        } catch (e: InterruptedException) {
            threadPool.shutdownNow()
        }
    }
}

/**
 * Statistics snapshot for the plugin loader.
 */
data class PluginLoaderStats(
    val totalCalls: Long,
    val totalTimeouts: Long,
    val activeCalls: Int,
    val loadedPluginCount: Int,
    val poolSize: Int,
    val activeThreads: Int,
    val queueSize: Int,
)
