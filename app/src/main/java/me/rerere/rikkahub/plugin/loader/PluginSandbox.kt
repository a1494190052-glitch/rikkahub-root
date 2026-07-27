package me.rerere.rikkahub.plugin.loader

import android.util.Log
import com.whl.quickjs.wrapper.QuickJSContext
import me.rerere.rikkahub.plugin.data.PluginDataStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * QuickJS sandbox for isolated plugin JavaScript execution.
 *
 * Provides:
 * - Native ES2020+ support (async/await, template literals, destructuring, class)
 * - console.log redirection
 * - Synchronous fetch() via OkHttp Java bridge (15s timeout)
 * - Promise event loop via executePendingJob()
 * - config object injection (user settings)
 * - dataStore object injection (per-plugin KV storage)
 * - Network domain whitelist enforcement
 *
 * Architecture: Uses QuickJS native async/await with a synchronous Java fetch bridge.
 * The JS fetch() calls into Java synchronously (blocking the JS thread, which runs on
 * a background executor), wraps the result in Promise.resolve(), and the async function
 * resumes via executePendingJob() microtask processing.
 */
class PluginSandbox(
    private val pluginId: String,
    private val allowedHosts: List<String> = emptyList(),
    private val dataStore: PluginDataStore? = null,
) {
    companion object {
        private const val TAG = "PluginSandbox"
        private const val FETCH_TIMEOUT_SECONDS = 15L
        private const val MAX_PENDING_JOBS = 1000
    }

    private var context: QuickJSContext? = null
    private var destroyed = false
    private val logs = mutableListOf<String>()
    private val fetchInProgress = AtomicBoolean(false)

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * Initialize the QuickJS context and inject base APIs.
     */
    fun init() {
        if (destroyed) throw IllegalStateException("Sandbox already destroyed")
        val ctx = QuickJSContext.create()
        context = ctx

        // Inject console
        ctx.setConsole(object : QuickJSContext.Console {
            override fun log(info: String?) {
                logs.add("[LOG] $info")
                Log.d(TAG, "[$pluginId] $info")
            }

            override fun info(info: String?) {
                logs.add("[INFO] $info")
                Log.i(TAG, "[$pluginId] $info")
            }

            override fun warn(info: String?) {
                logs.add("[WARN] $info")
                Log.w(TAG, "[$pluginId] $info")
            }

            override fun error(info: String?) {
                logs.add("[ERROR] $info")
                Log.e(TAG, "[$pluginId] $info")
            }
        })

        // Inject exports object and module system
        ctx.evaluate(
            """
            var exports = {};
            var module = { exports: exports };
            """.trimIndent(),
            "__init__.js"
        )

        // Inject allowed hosts array
        val hostsJson = allowedHosts.joinToString(",", "[", "]") { "\"$it\"" }
        ctx.evaluate("var __allowed_hosts__ = $hostsJson;", "__hosts__.js")

        // Inject synchronous fetch via Java bridge + Promise wrapper
        injectFetchBridge(ctx)

        // Inject dataStore with pre-loaded values
        injectDataStore(ctx)
    }

    /**
     * Inject fetch() using a synchronous Java bridge.
     *
     * The Java function __sync_fetch__ performs HTTP synchronously (blocking the JS thread,
     * which is acceptable since we run on a background executor). The JS fetch() wraps this
     * in Promise.resolve() so async/await code works naturally.
     *
     * Flow:
     * 1. Plugin JS calls: const resp = await fetch(url, opts)
     * 2. JS fetch() calls __sync_fetch__(url, method, headers, body) → Java HTTP → returns JSON string
     * 3. JS wraps result in Promise.resolve(response)
     * 4. async function suspends, executePendingJob() resumes it with the resolved value
     */
    private fun injectFetchBridge(ctx: QuickJSContext) {
        // Register the Java-side synchronous fetch function
        val globalObj = ctx.globalObject
        globalObj.setProperty("__sync_fetch__") { args ->
            if (args == null || args.isEmpty()) {
                return@setProperty """{"status":0,"statusText":"Error","body":"No arguments"}"""
            }
            val requestJson = args[0]?.toString() ?: "{}"
            try {
                val reqParts = parseSimpleJson(requestJson)
                val url = reqParts["url"] ?: ""
                val method = reqParts["method"] ?: "GET"
                val headers = reqParts["headers"] ?: "{}"
                val body = reqParts["body"]

                // Domain whitelist check
                if (!isHostAllowed(url)) {
                    """{"status":403,"statusText":"Forbidden","body":"Host not in whitelist: ${escapeJsonString(url)}"}"""
                } else {
                    fetchInProgress.set(true)
                    try {
                        performFetch(url, method, headers, body)
                    } finally {
                        fetchInProgress.set(false)
                    }
                }
            } catch (e: Exception) {
                """{"status":0,"statusText":"Error","body":"${escapeJsonString(e.message ?: "Unknown")}"}"""
            }
        }

        // Inject the JS fetch() wrapper that uses the synchronous bridge
        ctx.evaluate(
            """
            function __check_host_allowed__(url) {
                if (!__allowed_hosts__ || __allowed_hosts__.length === 0) return true;
                let host = '';
                try {
                    const match = url.match(/^https?:\/\/([^\/:]+)/);
                    if (match) host = match[1];
                } catch(e) { return false; }
                for (const pattern of __allowed_hosts__) {
                    if (pattern === '*') return true;
                    if (host === pattern) return true;
                    if (host.endsWith('.' + pattern)) return true;
                }
                return false;
            }

            function __make_response__(respStr) {
                try {
                    const result = JSON.parse(respStr);
                    const body = result.body || '';
                    return {
                        ok: result.status >= 200 && result.status < 300,
                        status: result.status,
                        statusText: result.statusText || '',
                        body: body,
                        json: function() { try { return JSON.parse(body); } catch(e) { return {}; } },
                        text: function() { return body; }
                    };
                } catch(e) {
                    return {
                        ok: false, status: 0, statusText: 'Parse error', body: respStr,
                        json: function() { return {}; },
                        text: function() { return this.body; }
                    };
                }
            }

            function fetch(url, options) {
                options = options || {};
                const method = (options.method || 'GET').toUpperCase();
                const headers = options.headers || {};
                const body = options.body || null;

                if (!__check_host_allowed__(url)) {
                    return Promise.resolve({
                        ok: false, status: 403, statusText: 'Forbidden',
                        body: 'Host not in whitelist: ' + url,
                        json: function() { return {}; },
                        text: function() { return this.body; }
                    });
                }

                // Synchronous Java bridge call - blocks JS thread (runs on background executor)
                const requestJson = JSON.stringify({
                    url: url,
                    method: method,
                    headers: JSON.stringify(headers),
                    body: body
                });
                const responseStr = __sync_fetch__(requestJson);
                const response = __make_response__(responseStr);

                // Return as resolved Promise for async/await compatibility
                return Promise.resolve(response);
            }
            """.trimIndent(),
            "__fetch__.js"
        )
    }

    /**
     * Check if a URL's host is in the allowed list.
     */
    private fun isHostAllowed(url: String): Boolean {
        if (allowedHosts.isEmpty()) return true
        val host = try {
            Regex("^https?://([^/:]+)").find(url)?.groupValues?.get(1) ?: ""
        } catch (e: Exception) {
            return false
        }
        return allowedHosts.any { pattern ->
            pattern == "*" || host == pattern || host.endsWith(".$pattern")
        }
    }

    /**
     * Inject dataStore API. Pre-loads existing values into a JS object.
     * Writes are stored in a JS-side map and synced back after execution.
     */
    private fun injectDataStore(ctx: QuickJSContext) {
        val existingData = dataStore?.list() ?: emptyMap()
        val dataJson = existingData.entries.joinToString(",", "{", "}") { (k, v) ->
            "\"${escapeJsonString(k)}\":\"${escapeJsonString(v)}\""
        }

        ctx.evaluate(
            """
            var __ds_data__ = JSON.parse('${escapeForJsString(dataJson)}');
            var __ds_dirty__ = {};

            const dataStore = {
                set(key, value) {
                    __ds_data__[key] = String(value);
                    __ds_dirty__[key] = String(value);
                },
                get(key) {
                    const v = __ds_data__[key];
                    return v !== undefined ? v : null;
                },
                del(key) {
                    delete __ds_data__[key];
                    __ds_dirty__[key] = '__DELETE__';
                },
                list() {
                    const result = {};
                    for (const k of Object.keys(__ds_data__)) {
                        result[k] = __ds_data__[k];
                    }
                    return result;
                }
            };
            """.trimIndent(),
            "__datastore__.js"
        )
    }

    /**
     * Sync dataStore changes back to persistent storage after execution.
     */
    private fun syncDataStore() {
        val ctx = context ?: return
        try {
            val dirtyJson = ctx.evaluate("JSON.stringify(__ds_dirty__ || {})")?.toString() ?: "{}"
            val dirty = parseSimpleJson(dirtyJson)
            dirty.forEach { (key, value) ->
                if (value == "__DELETE__") {
                    dataStore?.del(key)
                } else {
                    dataStore?.set(key, value)
                }
            }
            ctx.evaluate("__ds_dirty__ = {};", "__ds_sync__.js")
        } catch (e: Exception) {
            Log.w(TAG, "Error syncing dataStore for $pluginId", e)
        }
    }

    /**
     * Inject user configuration into the JS context as a `config` object.
     */
    fun injectConfig(config: Map<String, String>) {
        val ctx = context ?: return
        val configJson = config.entries.joinToString(",", "{", "}") { (k, v) ->
            "\"${escapeJsonString(k)}\":\"${escapeJsonString(v)}\""
        }
        ctx.evaluate("var config = JSON.parse('${escapeForJsString(configJson)}');", "__config__.js")
    }

    /**
     * @deprecated ES5 preprocessing is no longer needed.
     * QuickJS natively supports ES2020+ (async/await, template literals, destructuring, class).
     * This method now returns code unchanged for backward compatibility.
     */
    @Deprecated(
        message = "QuickJS natively supports ES2020+. No preprocessing needed.",
        replaceWith = ReplaceWith("code")
    )
    fun preprocessES5(code: String): String {
        // No-op: QuickJS supports async/await, const/let, arrow functions, template literals natively
        return code
    }

    /**
     * Evaluate a JavaScript file in the sandbox.
     * No ES5 preprocessing - QuickJS handles ES2020+ natively.
     */
    fun evaluateFile(code: String, fileName: String = "main.js") {
        val ctx = context ?: throw IllegalStateException("Sandbox not initialized")
        ctx.evaluate(code, fileName)
    }

    /**
     * Evaluate raw JavaScript code.
     */
    fun evaluate(code: String): Any? {
        val ctx = context ?: throw IllegalStateException("Sandbox not initialized")
        return ctx.evaluate(code)
    }

    /**
     * Call an exported function by name with JSON parameters.
     * Supports both sync and async functions via the Promise event loop.
     *
     * For async functions:
     * 1. Invoke the function → returns a Promise
     * 2. Store Promise result in __call_result__ via .then()/.catch()
     * 3. Loop executePendingJob() to process microtasks until Promise settles
     * 4. Read __call_result__ for the final value
     */
    fun callFunction(name: String, paramsJson: String = "{}"): String {
        val ctx = context ?: throw IllegalStateException("Sandbox not initialized")

        val escapedParams = escapeForJsString(paramsJson)
        val escapedName = escapeForJsString(name)

        // Execute the function call with Promise handling
        val callScript = """
            (function() {
                'use strict';
                const fn = exports['$escapedName'] || (module.exports && module.exports['$escapedName']);
                if (!fn || typeof fn !== 'function') {
                    __call_result__ = JSON.stringify({ error: 'Function not found: $escapedName' });
                    __call_done__ = true;
                    return;
                }
                try {
                    const params = JSON.parse('$escapedParams');
                    const result = fn(params);

                    // Check if result is a Promise (async function)
                    if (result && typeof result.then === 'function') {
                        __call_done__ = false;
                        result.then(function(value) {
                            if (value === undefined || value === null) {
                                __call_result__ = JSON.stringify({ success: true });
                            } else if (typeof value === 'object') {
                                __call_result__ = JSON.stringify(value);
                            } else {
                                __call_result__ = JSON.stringify({ result: value });
                            }
                            __call_done__ = true;
                        }).catch(function(err) {
                            __call_result__ = JSON.stringify({ error: err.message || String(err) });
                            __call_done__ = true;
                        });
                    } else {
                        // Synchronous result
                        if (result === undefined || result === null) {
                            __call_result__ = JSON.stringify({ success: true });
                        } else if (typeof result === 'object') {
                            __call_result__ = JSON.stringify(result);
                        } else {
                            __call_result__ = JSON.stringify({ result: result });
                        }
                        __call_done__ = true;
                    }
                } catch(e) {
                    __call_result__ = JSON.stringify({ error: e.message || String(e) });
                    __call_done__ = true;
                }
            })();
        """.trimIndent()

        // Reset state and execute
        ctx.evaluate("var __call_result__ = null; var __call_done__ = false;", "__reset__.js")
        ctx.evaluate(callScript, "call_$name.js")

        // Event loop: process pending jobs (microtasks) until the Promise settles
        var jobCount = 0
        while (jobCount < MAX_PENDING_JOBS) {
            val done = ctx.evaluate("__call_done__")
            if (done == true || done?.toString() == "true") break

            // Execute one pending job (microtask/Promise callback)
            val jobResult = ctx.executePendingJob()
            if (jobResult <= 0) {
                // No more pending jobs but not done - might be stuck
                // Give it one more check
                val doneCheck = ctx.evaluate("__call_done__")
                if (doneCheck == true || doneCheck?.toString() == "true") break
                // If still not done and no jobs, something is wrong
                if (jobResult < 0) {
                    Log.w(TAG, "[$pluginId] executePendingJob returned error for $name")
                    break
                }
                // jobResult == 0 means no pending jobs; if not done, break to avoid infinite loop
                break
            }
            jobCount++
        }

        if (jobCount >= MAX_PENDING_JOBS) {
            Log.w(TAG, "[$pluginId] Max pending jobs reached for $name - possible infinite async loop")
        }

        // Read the result
        val result = try {
            ctx.evaluate("__call_result__")?.toString()
                ?: """{"error":"No result returned"}"""
        } catch (e: Exception) {
            """{"error":"${escapeJsonString(e.message ?: "Failed to read result")}"}"""
        }

        // Sync dataStore changes
        syncDataStore()

        return result
    }

    /**
     * Call an event handler by name.
     */
    fun callEvent(handlerName: String, paramsJson: String = "{}"): String {
        return callFunction(handlerName, paramsJson)
    }

    /**
     * Perform actual HTTP fetch using OkHttp (synchronous).
     * Called from the Java bridge function __sync_fetch__.
     */
    private fun performFetch(url: String, method: String, headersJson: String, body: String?): String {
        return try {
            val requestBuilder = Request.Builder().url(url)

            // Parse and add headers
            try {
                val headersMap = parseSimpleJson(headersJson)
                headersMap.forEach { (key, value) ->
                    requestBuilder.addHeader(key, value)
                }
            } catch (_: Exception) {}

            // Set method and body
            val requestBody = body?.toRequestBody("application/json".toMediaType())
            when (method.uppercase()) {
                "GET" -> requestBuilder.get()
                "POST" -> requestBuilder.post(requestBody ?: "".toRequestBody(null))
                "PUT" -> requestBuilder.put(requestBody ?: "".toRequestBody(null))
                "DELETE" -> requestBuilder.delete(requestBody)
                "PATCH" -> requestBuilder.patch(requestBody ?: "".toRequestBody(null))
                else -> requestBuilder.method(method, requestBody)
            }

            val response = httpClient.newCall(requestBuilder.build()).execute()
            val responseBody = response.body?.string() ?: ""
            val status = response.code
            val statusText = response.message

            val escapedBody = escapeJsonString(responseBody)
            val escapedStatusText = escapeJsonString(statusText)
            """{"status":$status,"statusText":"$escapedStatusText","body":"$escapedBody"}"""
        } catch (e: Exception) {
            val errorMsg = escapeJsonString(e.message ?: "Unknown error")
            """{"status":0,"statusText":"Error","body":"$errorMsg"}"""
        }
    }

    /**
     * Get collected console logs.
     */
    fun getLogs(): List<String> = logs.toList()

    /**
     * Clear collected logs.
     */
    fun clearLogs() = logs.clear()

    /**
     * Destroy the sandbox and release resources.
     */
    fun destroy() {
        if (destroyed) return
        destroyed = true
        try {
            context?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Error destroying QuickJS context for $pluginId", e)
        }
        context = null
    }

    // --- Utility functions ---

    private fun escapeJsonString(s: String): String {
        return s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    private fun escapeForJsString(s: String): String {
        return s
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }

    private fun parseSimpleJson(json: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val content = json.trim().removeSurrounding("{", "}")
        if (content.isBlank()) return result
        val regex = Regex("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"")
        regex.findAll(content).forEach { match ->
            result[match.groupValues[1]] = match.groupValues[2]
        }
        return result
    }
}
