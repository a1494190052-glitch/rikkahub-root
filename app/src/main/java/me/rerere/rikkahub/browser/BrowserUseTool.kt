package me.rerere.rikkahub.browser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.io.ByteArrayOutputStream

/**
 * Bridges the browser system (BrowserTabPool) to RikkaHub's Tool interface.
 * Provides the "browser_use" tool that lets the AI agent control a WebView-based
 * browser with up to 3 tabs.
 */
fun createBrowserUseTool(tabPool: BrowserTabPool): Tool = Tool(
    name = "browser_use",
    description = "Control a web browser with up to 3 tabs. " +
        "Use navigate to open URL, screenshot to see the page (returns an image), " +
        "click/type to interact with elements, get_text/get_readable to extract content, " +
        "scroll to navigate long pages, scroll_and_collect to scroll through infinite-scroll/virtual-rendered pages " +
        "(like Twitter/X timelines) and accumulate unique content items across scroll positions in a single call, " +
        "find_elements to discover interactive elements, " +
        "get_page_info for page metadata, get_backbone to get a structural overview of the page DOM as a simplified tree, " +
        "fetch to download files/resources using the page's session (returns metadata), " +
        "new_tab to open an additional tab, close_tab to close a tab, and list_tabs to see all open tabs. " +
        "Use set_viewport with viewport_width + viewport_height to override the viewport for the current session; " +
        "pass reset=true to drop the session override and fall back to the global browser setting. " +
        "Use get_cookies to retrieve cookies for the current page URL / current site root domain only (including HttpOnly cookies). " +
        "Use set_cookies to write cookies into the current page's cookie store via the native cookie store. " +
        "Use wait_for_dom_stable to wait until the page DOM stops changing " +
        "(useful after navigation or interactions that trigger async data loading). " +
        "Use tab_id to target a specific tab (defaults to the most recently used tab).",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putJsonObject("action") {
                    put("type", "string")
                    put("description", "The browser action to perform")
                    putJsonArray("enum") {
                        BrowserAction.allValues.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                    }
                }
                putJsonObject("url") {
                    put("type", "string")
                    put("description", "URL to navigate to (for navigate action) or resource to download (for fetch action)")
                }
                putJsonObject("selector") {
                    put("type", "string")
                    put("description", "CSS selector for targeting elements (click, type, get_text, scroll, hover, find_elements)")
                }
                putJsonObject("text") {
                    put("type", "string")
                    put("description", "Text to type (for type action)")
                }
                putJsonObject("coordinate_x") {
                    put("type", "integer")
                    put("description", "X coordinate for click (alternative to selector)")
                }
                putJsonObject("coordinate_y") {
                    put("type", "integer")
                    put("description", "Y coordinate for click (alternative to selector)")
                }
                putJsonObject("direction") {
                    put("type", "string")
                    put("description", "Scroll direction")
                    putJsonArray("enum") {
                        add(kotlinx.serialization.json.JsonPrimitive("up"))
                        add(kotlinx.serialization.json.JsonPrimitive("down"))
                    }
                }
                putJsonObject("amount") {
                    put("type", "integer")
                    put("description", "Scroll amount in pixels (default: 500)")
                }
                putJsonObject("script") {
                    put("type", "string")
                    put("description", "JavaScript code to execute (for execute_js action). Supports await and top-level return.")
                }
                putJsonObject("user_agent") {
                    put("type", "string")
                    put("description", "User agent profile to switch to")
                    putJsonArray("enum") {
                        add(kotlinx.serialization.json.JsonPrimitive("desktop_chrome"))
                        add(kotlinx.serialization.json.JsonPrimitive("mobile_chrome"))
                    }
                }
                putJsonObject("max_depth") {
                    put("type", "integer")
                    put("description", "Maximum tree depth for get_backbone (default: 5)")
                }
                putJsonObject("scroll_count") {
                    put("type", "integer")
                    put("description", "Number of scroll steps for scroll_and_collect (default: 10, max: 20)")
                }
                putJsonObject("item_selector") {
                    put("type", "string")
                    put("description", "CSS selector for individual content items in scroll_and_collect")
                }
                putJsonObject("tab_id") {
                    put("type", "integer")
                    put("description", "Target tab ID (optional, defaults to most recently used tab)")
                }
                putJsonObject("keywords") {
                    put("type", "string")
                    put("description", "Filter cookies by name (for get_cookies). Space-separated keywords.")
                }
                putJsonObject("fuzzy") {
                    put("type", "boolean")
                    put("description", "Whether keyword matching is fuzzy (contains-all) or exact-any (for get_cookies, default: true)")
                }
                putJsonObject("cookies") {
                    put("type", "string")
                    put("description", "For set_cookies: a JSON array of cookie objects to write.")
                }
                putJsonObject("timeout") {
                    put("type", "integer")
                    put("description", "Timeout in seconds for wait_for_dom_stable (default: 10)")
                }
                putJsonObject("viewport_width") {
                    put("type", "integer")
                    put("description", "Viewport width in CSS pixels for set_viewport (e.g. 1920)")
                }
                putJsonObject("viewport_height") {
                    put("type", "integer")
                    put("description", "Viewport height in CSS pixels for set_viewport (e.g. 1080)")
                }
                putJsonObject("reset") {
                    put("type", "boolean")
                    put("description", "For set_viewport: when true, clear the session-level viewport override")
                }
            },
            required = listOf("action"),
        )
    },
    execute = { input ->
        val inputStr = input.toString()
        val actionInput = BrowserActionInput.parse(inputStr)
        if (actionInput == null) {
            return@Tool listOf(UIMessagePart.Text("Error: Invalid browser_use input — could not parse action"))
        }

        val result = tabPool.execute(actionInput)

        val parts = mutableListOf<UIMessagePart>()

        // Add text result
        if (result.text.isNotEmpty()) {
            parts.add(UIMessagePart.Text(result.text))
        }

        // Add screenshot image if present
        if (result.base64Image != null) {
            try {
                val rawBytes = Base64.decode(result.base64Image, Base64.DEFAULT)
                val resized = resizeJpegToMaxEdge(rawBytes, 2000)
                val b64 = if (resized != null) {
                    Base64.encodeToString(resized, Base64.NO_WRAP)
                } else {
                    result.base64Image
                }
                parts.add(UIMessagePart.Image(url = "data:image/jpeg;base64,$b64"))
            } catch (_: Exception) {
                // If image processing fails, still return the text
            }
        }

        if (parts.isEmpty()) {
            parts.add(UIMessagePart.Text(if (result.success) "OK" else "Error: unknown"))
        }

        parts
    }
)

/**
 * Resize a JPEG byte array so its longest edge is at most [maxEdge] pixels.
 * Returns null if decoding fails or the image is already within bounds
 * (in which case the caller should use the original bytes).
 */
fun resizeJpegToMaxEdge(bytes: ByteArray, maxEdge: Int): ByteArray? {
    return try {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        val w = options.outWidth
        val h = options.outHeight
        if (w <= 0 || h <= 0) return null

        val longest = maxOf(w, h)
        if (longest <= maxEdge) return null // Already small enough

        val scale = maxEdge.toFloat() / longest
        val newW = (w * scale).toInt().coerceAtLeast(1)
        val newH = (h * scale).toInt().coerceAtLeast(1)

        val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val scaled = Bitmap.createScaledBitmap(original, newW, newH, true)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        if (scaled !== original) scaled.recycle()
        original.recycle()
        out.toByteArray()
    } catch (_: Exception) {
        null
    }
}
