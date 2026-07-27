package me.rerere.rikkahub.plugin.loader

import android.util.Log
import com.whl.quickjs.wrapper.JSCallFunction
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
 * - Native fetch() via Java-JS bridge (JSCallFunction) with Promise support
 * - Full ES2020+ support (async/await, template literals, destructuring, classes, etc.)
 * - config object injection (user settings)
 * - dataStore object injection (per-plugin KV storage)
 * - Network domain whitelist enforcement (Java-side)
 *
 * Architecture: Uses JSCallFunction to register a synchronous __native_fetch bridge.
 * JS fetch() wraps this in a Promise for async/await compatibility.
 * No more request-intercept / re-execution hack.
 */
class PluginSandbox(
    private val pluginId: String,
    private val allowedHosts: List<String> = emptyList(),
    private val dataStore: PluginDataStore? = null,
) {
    companion object {
        private const val TAG = "PluginSandbox"
        private const val FETCH_TIMEOUT_SECONDS = 16L
        private const val MAX_ASYNC_PUMP_ITERATIONS = 200
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

        // Inject native fetch bridge via JSCallFunction
        injectNativeFetch(ctx)

        // Inject dataStore with pre-loaded values
        injectDataStore(ctx)
    }

    /**
     * Inject native fetch() using JSCallFunction Java-JS bridge.
     *
     * Registers __native_fetch(url, method, headersJson, body) as a Java function
     * that performs synchronous OkHttp requests with domain whitelist enforcement.
     *
     * The JS-side fetch() wrapper calls __native_fetch synchronously and returns
     * a Promise.resolve(response) for async/await compatibility.
     */
    private fun injectNativeFetch(ctx: QuickJSContext) {
        val globalObj = ctx.getGlobalObject()

        // Register the native fetch bridge - callable from JS as __native_fetch(url, method, headers, body)
        globalObj.setProperty("__native_fetch", JSCallFunction { args ->
            val url = args.getOrNull(0)?.toString() ?: ""
            val method = args.getOrNull(1)?.toString() ?: "GET"
            val headersJson = args.getOrNull(2)?.toString() ?: "{}"
            val body = args.getOrNull(3)?.toString()

            // Domain whitelist check (Java-side, cannot be bypassed by JS)
            if (!isHostAllowed(url)) {
                return@JSCallFunction """{"status":403,"statusText":"Forbidden","body":"Host not in whitelist: ${escapeJsonString(url)}"}"""
            }

            performFetch(url, method, headersJson, body)
        })

        // Inject JS-side fetch wrapper with Promise support
        ctx.evaluate(
            """
            function __check_host_allowed__(url) {
                var hosts = ${allowedHosts.joinToString(",", "[", "]") { "\"$it\"" }};
                if (!hosts || hosts.length === 0) return true;
                var host = '';
                try {
                    var match = url.match(/^https?:\/\/([^\/:]+)/);
                    if (match) host = match[1];
                } catch(e) { return false; }
                for (var i = 0; i < hosts.length; i++) {
                    var pattern = hosts[i];
                    if (pattern === '*') return true;
                    if (host === pattern) return true;
                    if (host.endsWith('.' + pattern)) return true;
                }
                return false;
            }

            function fetch(url, options) {
                options = options || {};
                var method = (options.method || 'GET').toUpperCase();
                var headers = options.headers || {};
                var body = options.body !== undefined ? options.body : null;

                // Early JS-side host check for better error messages
                if (!__check_host_allowed__(url)) {
                    return Promise.resolve({
                        ok: false,
                        status: 403,
                        statusText: 'Forbidden',
                        body: 'Host not in whitelist: ' + url,
                        json: function() { return Promise.resolve({}); },
                        text: function() { return Promise.resolve(this.body); }
                    });
                }

                // Synchronous native call via Java bridge
                var respStr = __native_fetch(url, method, JSON.stringify(headers), body);

                var result;
                try {
                    result = JSON.parse(respStr);
                } catch(e) {
                    result = { status: 0, statusText: 'Parse error', body: respStr || '' };
                }

                var response = {
                    ok: result.status >= 200 && result.status < 300,
                    status: result.status,
                    statusText: result.statusText || '',
                    body: result.body || '',
                    json: function() {
                        try { return Promise.resolve(JSON.parse(this.body)); }
                        catch(e) { return Promise.resolve({}); }
                    },
                    text: function() { return Promise.resolve(this.body); }
                };

                return Promise.resolve(response);
            }
            """.trimIndent(),
            "__fetch__.js"
        )
    }

    /**
     * Check if a URL's host is in the allowed hosts list.
     * Called from Java side before making OkHttp requests.
     */
    private fun isHostAllowed(url: String): Boolean {
        if (allowedHosts.isEmpty()) return true

        val host = try {
            val regex = Regex("^https?://([^/:]+)")
            regex.find(url)?.groupValues?.get(1) ?: return false
        } catch (e: Exception) {
            return false
        }

        for (pattern in allowedHosts) {
            if (pattern == "*") return true
            if (host == pattern) return true
            if (host.endsWith(".$pattern")) return true
        }
        return false
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
     * Evaluate a JavaScript file in the sandbox.
     * No ES5 preprocessing - QuickJS natively supports ES2020+.
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
     * Supports both synchronous functions and async functions (returning Promises).
     * Returns the result as a JSON string.
     */
    fun callFunction(name: String, paramsJson: String = "{}"): String {
        val ctx = context ?: throw IllegalStateException("Sandbox not initialized")

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

                    // Check if result is a Promise/thenable (async function)
                    if (result !== null && result !== undefined && typeof result.then === 'function') {
                        __async_result__ = undefined;
                        __async_error__ = undefined;
                        __async_done__ = false;
                        result.then(function(v) {
                            __async_result__ = v;
                            __async_done__ = true;
                        }).catch(function(e) {
                            __async_error__ = (e && e.message) ? e.message : String(e);
                            __async_done__ = true;
                        });
                        return '__ASYNC_PENDING__';
                    }

                    // Synchronous result
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

        var result = ctx.evaluate(callScript, "call_$name.js")?.toString()
            ?: """{"error":"null result"}"""

        // Handle async functions: pump microtasks until the Promise resolves
        if (result == "__ASYNC_PENDING__") {
            var iterations = 0
            while (iterations < MAX_ASYNC_PUMP_ITERATIONS) {
                // Pump the microtask queue by evaluating a trivial Promise
                ctx.evaluate("Promise.resolve()", "__pump__.js")

                val done = ctx.evaluate("__async_done__")
                if (done == true || done?.toString() == "true") break
                iterations++
            }

            // Check for async error
            val asyncError = ctx.evaluate("__async_error__")?.toString()
            if (asyncError != null && asyncError != "undefined" && asyncError != "null" && asyncError.isNotBlank()) {
                result = """{"error":"${escapeJsonString(asyncError)}"}"""
            } else {
                // Extract the async result
                val extractScript = """
                    (function() {
                        var r = __async_result__;
                        if (r === undefined || r === null) return JSON.stringify({ success: true });
                        if (typeof r === 'object') return JSON.stringify(r);
                        return JSON.stringify({ result: r });
                    })()
                """.trimIndent()
                result = ctx.evaluate(extractScript, "__async_extract__.js")?.toString()
                    ?: """{"success":true}"""
            }
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
     * Called from the __native_fetch JSCallFunction bridge on the plugin's thread.
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
