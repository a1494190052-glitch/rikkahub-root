package me.rerere.rikkahub.ui.components.richtext

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import me.rerere.rikkahub.utils.toCssHex
import java.io.File

/**
 * 检测文本是否包含 HTML 结构标签 (ST 风格角色卡消息)
 *
 * 只匹配结构性标签, 避免普通 Markdown 内联 HTML (<br>/<b> 等) 误判
 */
private val HTML_STRUCTURE_REGEX = Regex(
    "(?i)</?(div|section|article|header|footer|nav|aside|main|style|table|thead|tbody|tfoot|tr|td|th|caption|button|audio|video|source|font|center|details|summary|marquee|iframe|object|embed|canvas|svg|form|input|select|textarea|fieldset|figure|figcaption|h[1-6]|html|head|body)([\\s>/]|$)"
)

fun containsHtmlMarkup(text: String): Boolean = HTML_STRUCTURE_REGEX.containsMatchIn(text)

/**
 * 剥离 ```html ... ``` 代码栅栏 (ST 正则脚本常用 ```html 包裹界面代码,
 * ST 渲染时原样输出为 HTML)
 */
private val HTML_FENCE_REGEX = Regex("```html\\s*\n([\\s\\S]*?)```", RegexOption.IGNORE_CASE)

fun unwrapHtmlFences(text: String): String {
    if (!text.contains("```")) return text
    return HTML_FENCE_REGEX.replace(text) { it.groupValues[1].trim() }
}

/** 全屏布局特征: 内容主要用 fixed/absolute/100vh 定位, 不占文档流 */
private val FULLSCREEN_LAYOUT_REGEX = Regex(
    "(?i)position:\\s*(fixed|absolute)|\\d+vh\\b|height:\\s*100%"
)

private const val FULLSCREEN_HEIGHT_RATIO = 0.72f

/**
 * ST 风格 HTML 消息渲染器
 *
 * 用 WebView 原样渲染包含 HTML 的消息 (角色卡开场白 / 状态面板等),
 * 与 SillyTavern 的消息渲染行为对齐:
 * - 完整 HTML + CSS 支持, 启用 JavaScript
 * - {{char}} / {{user}} 显示层替换
 * - 内容高度自适应 (MutationObserver 监听 DOM 变化)
 * - 链接点击跳外部浏览器
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HtmlMessageContent(
    content: String,
    charName: String,
    userName: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val colorScheme = MaterialTheme.colorScheme

    // 全屏布局 (position:fixed / 100vh / height:100%) 的内容不占文档流,
    // scrollHeight 测不出真实高度 — 给足默认视口高度
    val isFullScreenLayout = remember(content) { FULLSCREEN_LAYOUT_REGEX.containsMatchIn(content) }
    val initialHeightPx = remember(isFullScreenLayout) {
        with(density) {
            (if (isFullScreenLayout) (configuration.screenHeightDp * FULLSCREEN_HEIGHT_RATIO).dp else 96.dp)
                .toPx().toInt()
        }
    }
    var contentHeightPx by remember(content) { mutableIntStateOf(initialHeightPx) }

    // 显示层占位符替换 + ```html 栅栏剥离 (ST 渲染行为)
    val processedContent = remember(content, charName, userName) {
        unwrapHtmlFences(content)
            .replace("{{char}}", charName, ignoreCase = true)
            .replace("{{user}}", userName, ignoreCase = true)
    }

    val htmlDoc = remember(processedContent, colorScheme) {
        buildHtmlMessageDocument(
            content = processedContent,
            textColor = colorScheme.onSurface.toCssHex(),
            linkColor = colorScheme.primary.toCssHex(),
        )
    }

    val assetLoader = remember { buildAssetLoader(context) }

    val heightDp = with(density) { contentHeightPx.toDp() }

    AndroidView(
        modifier = modifier.height(heightDp),
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(AndroidColor.TRANSPARENT)
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = true
                    allowContentAccess = true
                    mediaPlaybackRequiresUserGesture = false
                    loadWithOverviewMode = true
                    useWideViewPort = false
                    mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }
                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun postHeight(height: Int) {
                        if (height > 0) {
                            post { contentHeightPx = maxOf(height, initialHeightPx) }
                        }
                    }
                }, "AndroidHeight")
                webChromeClient = object : android.webkit.WebChromeClient() {
                    override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                        android.util.Log.d(
                            "HtmlMessage",
                            "[${message.messageLevel()}] ${message.message()} @${message.sourceId()}:${message.lineNumber()}"
                        )
                        return true
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest
                    ): android.webkit.WebResourceResponse? {
                        return assetLoader.shouldInterceptRequest(request.url)
                            ?: super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        super.onPageFinished(view, url)
                        // 注入高度监听: DOM 变化时回传最新高度
                        view.evaluateJavascript(
                            """
                            (function() {
                                var scheduled = false;
                                var lastHeight = 0;
                                function measure() {
                                    var h = Math.max(
                                        document.body ? document.body.scrollHeight : 0,
                                        document.documentElement ? document.documentElement.scrollHeight : 0
                                    );
                                    // fixed/absolute 元素不占文档流: 取所有元素下边界最大值兜底
                                    var els = document.body ? document.body.querySelectorAll('*') : [];
                                    var limit = Math.min(els.length, 500);
                                    for (var i = 0; i < limit; i++) {
                                        var r = els[i].getBoundingClientRect();
                                        if (r.height > 0) {
                                            var bottom = r.bottom + window.scrollY;
                                            if (bottom > h) h = Math.ceil(bottom);
                                        }
                                    }
                                    // 微小变化忽略, 避免动画抖动引发频繁重组
                                    if (Math.abs(h - lastHeight) >= 8) {
                                        lastHeight = h;
                                        AndroidHeight.post(h);
                                    }
                                }
                                function postHeight() {
                                    if (scheduled) return;
                                    scheduled = true;
                                    setTimeout(function() { scheduled = false; measure(); }, 300);
                                }
                                if (window.__heightObserverInstalled) { postHeight(); return; }
                                window.__heightObserverInstalled = true;
                                // 不监听 style 属性: GSAP 等动画每帧改 style,
                                // 会导致 observer 高频触发 -> 滚动卡顿
                                new MutationObserver(postHeight)
                                    .observe(document.documentElement, {
                                        childList: true, subtree: true,
                                        characterData: true,
                                        attributes: true,
                                        attributeFilter: ['class', 'id', 'src', 'href', 'open', 'width', 'height', 'colspan', 'rowspan']
                                    });
                                window.addEventListener('load', postHeight);
                                window.addEventListener('resize', postHeight);
                                postHeight();
                                setTimeout(measure, 500);
                                setTimeout(measure, 1500);
                                setTimeout(measure, 3500);
                            })();
                            """.trimIndent(), null
                        )
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest
                    ): Boolean {
                        val uri = request.url
                        // 内部资源域不拦截
                        if (uri.host == "appassets.androidplatform.net") return false
                        return when (uri.scheme?.lowercase()) {
                            "http", "https" -> {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, uri).apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                    )
                                }
                                true
                            }

                            "about", "data", "javascript", null -> false
                            else -> {
                                // 自定义 scheme (如卡内跳转), 尝试外部打开, 失败则吞掉
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, uri).apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                    )
                                }
                                true
                            }
                        }
                    }
                }
            }
        },
        update = { webView ->
            // 内容未变化时不重复加载, 避免闪烁
            if (webView.tag != htmlDoc.hashCode()) {
                webView.tag = htmlDoc.hashCode()
                // 写缓存文件, 经 WebViewAssetLoader 以 https 虚拟域加载:
                // 无 loadData 字符串转义坑, 标准 https 同源, localStorage 可用
                val file = writeHtmlCache(context, htmlDoc)
                webView.loadUrl("$ASSET_ORIGIN$HTML_PATH_PREFIX${file.name}")
            }
        },
        onRelease = { it.destroy() },
        // WebView 高度由 JS 回传驱动
    )
}

/**
 * 把 HTML 文档写入缓存目录, 返回文件 (内容相同则复用)
 *
 * 附带简单 LRU: 超过 [MAX_CACHE_FILES] 个文件时删除最旧的
 */
private const val MAX_CACHE_FILES = 50
private const val HTML_CACHE_DIR = "html_messages"

/** WebViewAssetLoader 映射的虚拟 https 域 (标准同源策略, localStorage 可用) */
const val ASSET_ORIGIN = "https://appassets.androidplatform.net"
private const val HTML_PATH_PREFIX = "/html-messages/"

private fun writeHtmlCache(context: android.content.Context, htmlDoc: String): File {
    val dir = File(context.cacheDir, HTML_CACHE_DIR).apply { mkdirs() }
    val file = File(dir, "msg_" + htmlDoc.hashCode().toString(16) + ".html")
    if (!file.exists()) {
        file.writeText(htmlDoc, Charsets.UTF_8)
        // LRU 清理
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return file
        if (files.size > MAX_CACHE_FILES) {
            files.take(files.size - MAX_CACHE_FILES).forEach { it.delete() }
        }
    }
    return file
}

/**
 * 缓存目录 <-> https://appassets.androidplatform.net/html-messages/ 映射
 */
private fun buildAssetLoader(context: android.content.Context): WebViewAssetLoader {
    val cacheRoot = context.cacheDir.canonicalPath
    return WebViewAssetLoader.Builder()
        .addPathHandler(HTML_PATH_PREFIX) { path ->
            val file = File(context.cacheDir, "$HTML_CACHE_DIR/$path")
            // 防目录穿越
            if (file.exists() && file.canonicalPath.startsWith(cacheRoot)) {
                android.webkit.WebResourceResponse(
                    "text/html", "UTF-8", file.inputStream()
                )
            } else {
                null
            }
        }
        .build()
}

/**
 * 包装消息 HTML 为完整文档
 *
 * 内容直接作为文档加载 (非 innerHTML 注入) — innerHTML 插入的 <script>
 * 不会执行, 而 ST 卡界面大量依赖 JS 动态生成。基础样式与 ST API 桩
 * 注入到 </head> 前 (或包装为新文档)。
 */
private fun buildHtmlMessageDocument(
    content: String,
    textColor: String,
    linkColor: String,
): String {
    val baseHead = buildString {
        append("<meta charset=\"UTF-8\">")
        append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">")
        append("<style>")
        append(
            """
            html, body {
              margin: 0; padding: 0;
              background: transparent;
              color: $textColor;
              font-size: 15px;
              line-height: 1.6;
              word-wrap: break-word;
              overflow-wrap: break-word;
              -webkit-text-size-adjust: 100%;
            }
            img, video, audio, iframe, canvas, svg { max-width: 100%; height: auto; }
            a { color: $linkColor; }
            table { border-collapse: collapse; max-width: 100%; }
            td, th { padding: 4px 8px; }
            pre { white-space: pre-wrap; word-wrap: break-word; }
            """.trimIndent()
        )
        append("</style>")
        // ST/酒馆助手 API 桩: 防止卡内脚本因缺少宿主 API 而报错中断渲染
        append("<script>")
        append(
            """
            (function() {
              function makeStub() {
                var fn = function() { return stub; };
                var stub = new Proxy(fn, {
                  get: function(t, k) {
                    if (k === Symbol.toPrimitive) return function() { return ''; };
                    if (k === 'then') return undefined;
                    return stub;
                  },
                  apply: function() { return stub; },
                  set: function() { return true; }
                });
                return stub;
              }
              if (typeof window.Mvu === 'undefined') window.Mvu = makeStub();
              if (typeof window.TavernHelper === 'undefined') window.TavernHelper = makeStub();
              if (typeof window.SillyTavern === 'undefined') window.SillyTavern = makeStub();
              if (typeof window.parent === 'undefined' || window.parent === window) {
                try { /* noop */ } catch (e) {}
              }
            })();
            """.trimIndent()
        )
        append("</script>")
    }

    return when {
        // 完整 HTML 文档: 基础样式注入 </head> 前
        content.contains("</head>", ignoreCase = true) ->
            content.replaceFirst(Regex("</head>", RegexOption.IGNORE_CASE), "$baseHead</head>")

        // 有 body 无 head: 插到 body 开标签后 (body 开标签唯一, replace 等价 replaceFirst)
        Regex("<body[^>]*>", RegexOption.IGNORE_CASE).containsMatchIn(content) ->
            content.replace(Regex("<body[^>]*>", RegexOption.IGNORE_CASE)) { it.value + baseHead }

        // HTML 片段: 包装为新文档
        else -> "<!DOCTYPE html><html><head>$baseHead</head><body>$content</body></html>"
    }
}
