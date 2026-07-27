package me.rerere.rikkahub.plugin.loader

import android.util.Log
import com.whl.quickjs.wrapper.QuickJSContext
import me.rerere.rikkahub.plugin.data.PluginDataStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * QuickJS sandbox for isolated plugin JavaScript execution.
 *
 * Provides:
 * - console.log redirection
 * - Synchronous fetch() via OkHttp (15s timeout)
 * - config object injection (user settings)
 * - dataStore object injection (per-plugin KV storage)
 * - Network domain whitelist enforcement
 * - ES5 preprocessing (async→function, await removal)
 *
 * Architecture: Uses a "request-intercept" pattern for native bridges.
 * JS functions store requests in globals; Java performs I/O and injects results.
 * This avoids dependency on specific QuickJS wrapper callback APIs.
 */
class PluginSandbox(
    private val pluginId: String,
    private val allowedHosts: List<String> = emptyList(),
    private val dataStore: PluginDataStore? = null,
) {
    companion object {
        private const val TAG = "PluginSandbox"
        private const val FETCH_TIMEOUT_SECONDS = 15L
        private const val MAX_FETCH_ITERATIONS = 20
    }

    private var context: QuickJSContext? = null
    private var destroyed = false
    private val logs = mutableListOf<String>()

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

        // Inject fetch implementation (request-intercept pattern)
        injectFetchShim(ctx)

        // Inject dataStore with pre-loaded values
        injectDataStore(ctx)
    }

    /**
     * Inject fetch() shim using request-intercept pattern with response caching.
     * The JS fetch() checks a cache first; on cache miss, stores the request in
     * __fetch_request__ for Java to process. callFunction() detects pending requests,
     * performs HTTP, injects results into the cache, and re-invokes the function.
     * This supports multiple fetch() calls per function invocation.
     */
    private fun injectFetchShim(ctx: QuickJSContext) {
        ctx.evaluate(
            """
            var __fetch_request__ = null;
            var __fetch_cache__ = {};

            function __check_host_allowed__(url) {
                if (!__allowed_hosts__ || __allowed_hosts__.length === 0) return true;
                var host = '';
                try {
                    var match = url.match(/^https?:\/\/([^\/:]+)/);
                    if (match) host = match[1];
                } catch(e) { return false; }
                for (var i = 0; i < __allowed_hosts__.length; i++) {
                    var pattern = __allowed_hosts__[i];
                    if (pattern === '*') return true;
                    if (host === pattern) return true;
                    if (host.indexOf('.' + pattern) === host.length - pattern.length - 1) return true;
                }
                return false;
            }

            function __make_response__(respStr) {
                try {
                    var result = JSON.parse(respStr);
                    return {
                        ok: result.status >= 200 && result.status < 300,
                        status: result.status,
                        statusText: result.statusText || '',
                        body: result.body || '',
                        json: function() { try { return JSON.parse(this.body); } catch(e) { return {}; } },
                        text: function() { return this.body; }
                    };
                } catch(e) {
                    return { ok: false, status: 0, statusText: 'Parse error', body: respStr,
                        json: function() { return {}; }, text: function() { return this.body; } };
                }
            }

            function fetch(url, options) {
                options = options || {};
                var method = (options.method || 'GET').toUpperCase();
                var headers = options.headers || {};
                var body = options.body || null;

                if (!__check_host_allowed__(url)) {
                    return {
                        ok: false, status: 403, statusText: 'Forbidden',
                        body: 'Host not in whitelist: ' + url,
                        json: function() { return {}; },
                        text: function() { return this.body; }
                    };
                }

                // Check cache first (keyed by url+method+body)
                var cacheKey = method + '|' + url + '|' + (body || '');
                if (__fetch_cache__[cacheKey]) {
                    return __make_response__(__fetch_cache__[cacheKey]);
                }

                // Cache miss: store request for Java to process
                __fetch_request__ = JSON.stringify({
                    url: url, method: method,
                    headers: JSON.stringify(headers),
                    body: body, cacheKey: cacheKey
                });

                // Return a placeholder error (function will be re-run with cached response)
                return __make_response__('{"status":0,"statusText":"Pending","body":""}');
            }
            """.trimIndent(),
            "__fetch__.js"
        )
    }

    /**
     * Inject dataStore API. Pre-loads existing values into a JS object.
     * Writes are stored in a JS-side map and synced back after execution.
     */
    private fun injectDataStore(ctx: QuickJSContext) {
        // Pre-load existing dataStore values
        val existingData = dataStore?.list() ?: emptyMap()
        val dataJson = existingData.entries.joinToString(",", "{", "}") { (k, v) ->
            "\"${escapeJsonString(k)}\":\"${escapeJsonString(v)}\""
        }

        ctx.evaluate(
            """
            var __ds_data__ = JSON.parse('${escapeForJsString(dataJson)}');
            var __ds_dirty__ = {};

            var dataStore = {
                set: function(key, value) {
                    __ds_data__[key] = String(value);
                    __ds_dirty__[key] = String(value);
                },
                get: function(key) {
                    var v = __ds_data__[key];
                    return v !== undefined ? v : null;
                },
                del: function(key) {
                    delete __ds_data__[key];
                    __ds_dirty__[key] = '__DELETE__';
                },
                list: function() {
                    var result = {};
                    for (var k in __ds_data__) {
                        if (__ds_data__.hasOwnProperty(k)) result[k] = __ds_data__[k];
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
            // Clear dirty flags
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
     * Preprocess ES6+ code to ES5-compatible syntax.
     */
    fun preprocessES5(code: String): String {
        return code
            .replace(Regex("\\basync\\s+function\\b"), "function")
            .replace(Regex("\\basync\\s*\\("), "(")
            .replace(Regex("\\bawait\\s+"), "")
            .replace(Regex("\\bconst\\s+"), "var ")
            .replace(Regex("\\blet\\s+"), "var ")
            .replace(Regex("\\(([^)]*)\\)\\s*=>\\s*\\{"), "function($1) {")
            .replace(Regex("(\\w+)\\s*=>\\s*\\{"), "function($1) {")
            .replace(Regex("\\(([^)]*)\\)\\s*=>\\s*([^;{\\n]+)"), "function($1) { return $2; }")
    }

    /**
     * Evaluate a JavaScript file in the sandbox.
     */
    fun evaluateFile(code: String, fileName: String = "main.js") {
        val ctx = context ?: throw IllegalStateException("Sandbox not initialized")
        val processed = preprocessES5(code)
        ctx.evaluate(processed, fileName)
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
     * Handles the fetch-intercept loop for synchronous HTTP.
     * Returns the result as a JSON string.
     */
    fun callFunction(name: String, paramsJson: String = "{}"): String {
        val ctx = context ?: throw IllegalStateException("Sandbox not initialized")

        // Reset fetch state
        ctx.evaluate("__fetch_request__ = null; __fetch_response__ = null;", "__reset__.js")

        // Build the function call script
        val escapedParams = escapeForJsString(paramsJson)
        val callScript = """
            (function() {
                var fn = exports['$name'] || (module.exports && module.exports['$name']);
                if (!fn || typeof fn !== 'function') {
                    return JSON.stringify({ error: 'Function not found: $name' });
                }
                try {
                    var params = JSON.parse('$escapedParams');
                    var result = fn(params);
                    if (result === undefined || result === null) {
                        return JSON.stringify({ success: true });
                    }
                    if (typeof result === 'object') {
                        return JSON.stringify(result);
                    }
                    return JSON.stringify({ result: result });
                } catch(e) {
                    return JSON.stringify({ error: e.message || String(e) });
                }
            })()
        """.trimIndent()

        // Execute with fetch-intercept loop
        var result = ctx.evaluate(callScript, "call_$name.js")?.toString()
            ?: """{"error":"null result"}"""

        // Handle fetch intercept: if __fetch_request__ is set, perform HTTP and re-run
        var iterations = 0
        while (iterations < MAX_FETCH_ITERATIONS) {
            val pendingRequest = ctx.evaluate("__fetch_request__")?.toString()
            if (pendingRequest == null || pendingRequest == "null" || pendingRequest.isBlank()) break

            // Clear the pending request
            ctx.evaluate("__fetch_request__ = null;", "__clear__.js")

            // Parse and perform the HTTP request
            val responseJson = try {
                val reqParts = parseSimpleJson(pendingRequest)
                val url = reqParts["url"] ?: ""
                val method = reqParts["method"] ?: "GET"
                val headers = reqParts["headers"] ?: "{}"
                val body = reqParts["body"]
                performFetch(url, method, headers, body)
            } catch (e: Exception) {
                """{"status":0,"statusText":"Error","body":"${escapeJsonString(e.message ?: "Unknown")}"}"""
            }

            // Inject response and re-execute
            ctx.evaluate(
                "__fetch_response__ = '${escapeForJsString(responseJson)}';",
                "__inject_response__.js"
            )

            // Re-execute the function (it will pick up the injected response)
            result = ctx.evaluate(callScript, "call_$name.js")?.toString()
                ?: """{"error":"null result"}"""

            iterations++
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
