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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import me.rerere.rikkahub.utils.base64Encode
import me.rerere.rikkahub.utils.toCssHex

/**
 * 检测文本是否包含 HTML 结构标签 (ST 风格角色卡消息)
 *
 * 只匹配结构性标签, 避免普通 Markdown 内联 HTML (<br>/<b> 等) 误判
 */
private val HTML_STRUCTURE_REGEX = Regex(
    "(?i)</?(div|section|article|header|footer|nav|aside|main|style|table|thead|tbody|tfoot|tr|td|th|caption|button|audio|video|source|font|center|details|summary|marquee|iframe|object|embed|canvas|svg|form|input|select|textarea|fieldset|figure|figcaption|h[1-6])([\\s>/]|$)"
)

fun containsHtmlMarkup(text: String): Boolean = HTML_STRUCTURE_REGEX.containsMatchIn(text)

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
    val colorScheme = MaterialTheme.colorScheme
    var contentHeightPx by remember { mutableIntStateOf(96) }

    // 显示层占位符替换 (ST 渲染行为)
    val processedContent = remember(content, charName, userName) {
        content
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
                            post { contentHeightPx = height }
                        }
                    }
                }, "AndroidHeight")
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        super.onPageFinished(view, url)
                        // 注入高度监听: DOM 变化时回传最新高度
                        view.evaluateJavascript(
                            """
                            (function() {
                                function postHeight() {
                                    var h = Math.max(
                                        document.body ? document.body.scrollHeight : 0,
                                        document.documentElement ? document.documentElement.scrollHeight : 0
                                    );
                                    AndroidHeight.post(h);
                                }
                                if (window.__heightObserverInstalled) { postHeight(); return; }
                                window.__heightObserverInstalled = true;
                                new MutationObserver(function() { postHeight(); })
                                    .observe(document.documentElement, {
                                        childList: true, subtree: true,
                                        attributes: true, characterData: true
                                    });
                                window.addEventListener('load', postHeight);
                                window.addEventListener('resize', postHeight);
                                postHeight();
                                setTimeout(postHeight, 300);
                                setTimeout(postHeight, 1000);
                            })();
                            """.trimIndent(), null
                        )
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest
                    ): Boolean {
                        val uri = request.url
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
                webView.loadDataWithBaseURL(
                    "https://rikkahub.local/",
                    htmlDoc,
                    "text/html",
                    "UTF-8",
                    null
                )
            }
        },
        onRelease = { it.destroy() },
        // WebView 高度由 JS 回传驱动
    )
}

/**
 * 包装消息 HTML 为完整文档, 注入基础样式
 */
private fun buildHtmlMessageDocument(
    content: String,
    textColor: String,
    linkColor: String,
): String {
    val contentBase64 = content.base64Encode()
    return """
<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<style>
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
  * { max-width: 100%; }
</style>
</head>
<body>
<div id="content"></div>
<script>
  function decodeBase64Utf8(b64) {
    var bin = atob(b64);
    var bytes = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    return new TextDecoder('utf-8').decode(bytes);
  }
  document.getElementById('content').innerHTML = decodeBase64Utf8("$contentBase64");
</script>
</body>
</html>
""".trimIndent()
}
