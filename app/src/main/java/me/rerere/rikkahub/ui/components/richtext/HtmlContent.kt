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
import me.rerere.rikkahub.data.model.CardVariableStore
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
    messageId: String = "",
    charId: String = "",
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val colorScheme = MaterialTheme.colorScheme

    // 全屏布局 (100vh/height:100%/fixed): 保留界面原布局, WebView 固定
    // 92% 屏高并切断嵌套滚动 — 手势全归 WebView 内部滚动 (浏览器原生速度);
    // 非全屏内容: 高度=内容全高, 滚动交给聊天列表
    val isFullScreenLayout = remember(content) { FULLSCREEN_LAYOUT_REGEX.containsMatchIn(content) }
    val configuration = LocalConfiguration.current
    val initialHeightPx = remember(isFullScreenLayout) {
        with(density) {
            (if (isFullScreenLayout) (configuration.screenHeightDp * 0.92f).dp else 96.dp).toPx().toInt()
        }
    }
    var contentHeightPx by remember(content) { mutableIntStateOf(initialHeightPx) }

    // 显示层占位符替换 + ```html 栅栏剥离 (ST 渲染行为)
    val processedContent = remember(content, charName, userName) {
        unwrapHtmlFences(content)
            .replace("{{char}}", charName, ignoreCase = true)
            .replace("{{user}}", userName, ignoreCase = true)
    }

    val htmlDoc = remember(processedContent, colorScheme, messageId, charId) {
        buildHtmlMessageDocument(
            content = processedContent,
            textColor = colorScheme.onSurface.toCssHex(),
            linkColor = colorScheme.primary.toCssHex(),
            messageId = messageId,
            charId = charId,
        )
    }

    val assetLoader = remember { buildAssetLoader(context) }
    val tavBridge = remember(messageId) { TavBridge(messageId) }

    val heightDp = with(density) { contentHeightPx.toDp() }

    AndroidView(
        modifier = modifier.height(heightDp),
        factory = { ctx ->
            TouchRelayWebView(ctx).apply {
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
                            // 全屏界面(100vh/vh 布局): 高度恒定为初始视口(92%屏高), 忽略 JS 报告。
                            // vh 单位随 WebView 高度重算, 采纳报告值会形成正反馈
                            // (WebView 越高 → 100vh 越大 → 内容越高 → 报告值越大),
                            // 且 bottom 兜底在滚动时叠加 scrollY 把高度越滚越大。
                            // 固定高度后 WebView 即视口, 内部滚动全归 Chromium 合成器。
                            android.util.Log.d(
                                "HtmlMessage",
                                "postHeight report=$height full=$isFullScreenLayout adopted=${if (isFullScreenLayout) initialHeightPx else height}"
                            )
                            if (!isFullScreenLayout) {
                                post { contentHeightPx = height }
                            }
                        }
                    }
                }, "AndroidHeight")
                addJavascriptInterface(tavBridge, "AndroidTavBridge")
                // 滚动内容探测桥: JS 报告页面有无可滚内容, 决定手势归属
                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun setConsumable(consumable: Boolean) {
                        touchConsumable = consumable
                    }
                }, "AndroidTouchBridge")
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
                        // 全屏界面高度恒定(=初始视口), 无需高度回传;
                        // 测量脚本每次遍历 500 元素取 getBoundingClientRect 会强制
                        // reflow, 滚动期被 ResizeObserver 反复触发 = JS 主线程卡顿源, 不注入
                        if (isFullScreenLayout) return
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
                                function scheduleMeasure() {
                                    if (scheduled) return;
                                    scheduled = true;
                                    setTimeout(function() { scheduled = false; measure(); }, 120);
                                }
                                if (window.__heightObserverInstalled) { scheduleMeasure(); return; }
                                window.__heightObserverInstalled = true;
                                // ResizeObserver (Tavo 方案): 只在 body 尺寸真正变化时触发,
                                // CSS transform 动画不触发 -> 不会随动画帧风暴
                                if (typeof ResizeObserver !== 'undefined') {
                                    new ResizeObserver(scheduleMeasure).observe(document.documentElement);
                                    if (document.body) new ResizeObserver(scheduleMeasure).observe(document.body);
                                }
                                window.addEventListener('load', scheduleMeasure);
                                window.addEventListener('resize', scheduleMeasure);
                                scheduleMeasure();
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
            // 嵌套滚动开关由 TouchRelayWebView 在每次 DOWN 时按 JS 探测结果管理:
            // 页面无可滚内容 -> 手动转发 delta 给聊天列表; 有可滚内容 -> Chromium 独享
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
 * 触摸转发 WebView: 解决"卡的界面无可滚内容时, 聊天列表划不动"
 *
 * 浏览器滚动链语义: 内部可滚元素滚手势, 滚不动/无可滚内容时手势归外层。
 * 但 WebView 的嵌套滚动是 preScroll 父优先 (LazyColumn 抢 delta),
 * 且 Chromium 分发低效无 fling — 所以反过来手动实现:
 * - JS 探测当前页面有无可滚内容 (AndroidTouchBridge.setConsumable)
 * - 无可滚 (touchConsumable=false): DOWN/UP 给 Chromium 保 tap,
 *   移动超 touchSlop 后 CANCEL Chromium 防误点击, delta 手动
 *   dispatchNestedPreScroll 给 LazyColumn, UP 时 dispatchNestedFling 保惯性
 * - 有可滚 (touchConsumable=true): 禁嵌套分发, 手势全归 Chromium 内部滚动
 */
private class TouchRelayWebView(context: android.content.Context) : WebView(context) {
    /** JS 探测结果: 当前页面有无 WebView 可滚内容 (JS 桥线程写, UI 线程读) */
    @Volatile
    var touchConsumable: Boolean = true

    private var relayMode = false
    private var relayDragging = false
    private var downRawY = 0f
    private var lastRawY = 0f
    private val consumed = IntArray(2)
    private var velocityTracker: android.view.VelocityTracker? = null
    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop

    /** 用屏幕绝对坐标(rawY)喂速度器: WebView 随列表平移时 ev.y 相对坐标会失真 */
    private fun trackMovement(ev: android.view.MotionEvent) {
        val copy = android.view.MotionEvent.obtain(ev)
        copy.setLocation(ev.rawX, ev.rawY)
        velocityTracker?.addMovement(copy)
        copy.recycle()
    }

    @SuppressLint("ClickableViewAccessibility", "Recycle")
    override fun onTouchEvent(ev: android.view.MotionEvent): Boolean {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                relayMode = !touchConsumable
                relayDragging = false
                downRawY = ev.rawY
                lastRawY = ev.rawY
                // 转发模式: 启嵌套(手动分发); WebView 模式: 禁嵌套(Chromium 独享)
                isNestedScrollingEnabled = relayMode
                if (relayMode) {
                    startNestedScroll(android.view.View.SCROLL_AXIS_VERTICAL)
                    velocityTracker?.recycle()
                    velocityTracker = android.view.VelocityTracker.obtain()
                    trackMovement(ev)
                }
                // DOWN 始终给 Chromium: tap/按钮点击需要
                super.onTouchEvent(ev)
                return true
            }

            android.view.MotionEvent.ACTION_MOVE -> {
                if (!relayMode) return super.onTouchEvent(ev)
                trackMovement(ev)
                val dyTotal = ev.rawY - downRawY
                if (!relayDragging) {
                    if (kotlin.math.abs(dyTotal) > touchSlop) {
                        relayDragging = true
                        // 确认是滚动手势: CANCEL 掉 Chromium 的手势, 防松手误点击
                        val cancel = android.view.MotionEvent.obtain(ev)
                        cancel.action = android.view.MotionEvent.ACTION_CANCEL
                        super.onTouchEvent(cancel)
                        cancel.recycle()
                    } else {
                        // 微小移动: 给 Chromium 维持 tap 判定
                        super.onTouchEvent(ev)
                    }
                }
                if (relayDragging) {
                    // rawY: 屏幕坐标, 不受 WebView 随列表平移影响, 防 delta 振荡
                    val dy = (lastRawY - ev.rawY).toInt()
                    if (dy != 0) {
                        // delta 全给聊天列表 (父没消费的部分 = 列表已到头, 丢弃)
                        dispatchNestedPreScroll(0, dy, consumed, null)
                    }
                    lastRawY = ev.rawY
                }
                return true
            }

            android.view.MotionEvent.ACTION_UP -> {
                if (relayMode) {
                    if (relayDragging) {
                        trackMovement(ev)
                        velocityTracker?.computeCurrentVelocity(1000)
                        val vy = -(velocityTracker?.yVelocity ?: 0f)
                        dispatchNestedFling(0f, vy, true)
                        // Chromium 已 CANCEL, 不发 UP
                    } else {
                        // 纯 tap: UP 给 Chromium 触发点击
                        super.onTouchEvent(ev)
                    }
                    stopNestedScroll()
                    velocityTracker?.recycle()
                    velocityTracker = null
                    return true
                }
                return super.onTouchEvent(ev)
            }

            android.view.MotionEvent.ACTION_CANCEL -> {
                if (relayMode) {
                    stopNestedScroll()
                    velocityTracker?.recycle()
                    velocityTracker = null
                    relayDragging = false
                    return true
                }
                return super.onTouchEvent(ev)
            }
        }
        return super.onTouchEvent(ev)
    }
}

/**
 * MVU / TavoJS 变量桥的 WebView 接口
 */
private class TavBridge(private val messageId: String) {
    private fun id(id: String?): String = if (id.isNullOrBlank()) messageId else id

    @JavascriptInterface
    fun getData(id: String): String = CardVariableStore.getData(id(id))

    @JavascriptInterface
    fun setData(id: String, json: String) = CardVariableStore.setData(id(id), json)

    @JavascriptInterface
    fun removeData(id: String) = CardVariableStore.removeData(id(id))

    @JavascriptInterface
    fun getVar(id: String, name: String): String? = CardVariableStore.getVar(id(id), name)

    @JavascriptInterface
    fun setVar(id: String, name: String, json: String) = CardVariableStore.setVar(id(id), name, json)

    @JavascriptInterface
    fun unsetVar(id: String, name: String) = CardVariableStore.unsetVar(id(id), name)
}

/**
 * 宿主 API 注入 (对齐酒馆助手/Tavo 标准):
 * - window.Mvu: MVU 变量库完整实现 (CDN 加载失败也能用)
 * - waitGlobalInitialized / getCurrentMessageId: 卡的 MVU 就绪判定依赖
 * - window.tav._getVariable 等: TavoJS 变量桥
 */
private fun buildHostApiScript(messageId: String, charId: String): String {
    val safeId = messageId.replace("\"", "")
    val safeCharId = charId.replace("\"", "")
    return """
<script>
(function() {
  var __MSG_ID = "$safeId";
  var __CHAR_ID = "$safeCharId";
  // MVU/Tavo 变量作用域路由: type 为 character/global/chat 时按角色级存储
  function scopeId(opt) {
    var t = opt && opt.type;
    if (t === 'character' || t === 'global' || t === 'chat') return t + ':' + __CHAR_ID;
    return (opt && opt.message_id != null) ? String(opt.message_id) : __MSG_ID;
  }
  function parsePath(path) {
    var parts = [];
    String(path).split('.').forEach(function(seg) {
      var m = seg.match(/([^\[\]]+)|\[(\d+)\]/g);
      if (m) m.forEach(function(p) {
        if (p.charAt(0) === '[') parts.push(Number(p.slice(1, -1)));
        else parts.push(p);
      });
    });
    return parts;
  }

  // ---- MVU 变量库 (MagVarUpdate 兼容层) ----
  if (typeof window.Mvu === 'undefined') {
    window.Mvu = {
      getMvuData: function(opt) {
        var raw = AndroidTavBridge.getData(scopeId(opt));
        try { return raw ? JSON.parse(raw) : {}; } catch (e) { return {}; }
      },
      replaceMvuData: function(data, opt) {
        AndroidTavBridge.setData(scopeId(opt), JSON.stringify(data == null ? {} : data));
        return true;
      },
      initMvuData: function(opt) {
        var d = this.getMvuData(opt);
        if (!d || typeof d !== 'object' || Array.isArray(d)) d = {};
        if (!d.stat_data || typeof d.stat_data !== 'object') d.stat_data = {};
        this.replaceMvuData(d, opt);
        return d;
      },
      getMvuVariable: function(path, opt) {
        var cur = this.getMvuData(opt);
        var parts = parsePath(path);
        for (var i = 0; i < parts.length; i++) {
          if (cur == null) return opt ? opt.default_value : undefined;
          cur = cur[parts[i]];
        }
        return cur === undefined ? (opt ? opt.default_value : undefined) : cur;
      },
      setMvuVariable: function(path, value, opt) {
        var d = this.getMvuData(opt);
        if (!d || typeof d !== 'object') d = {};
        var parts = parsePath(path);
        var cur = d;
        for (var i = 0; i < parts.length - 1; i++) {
          var k = parts[i];
          if (typeof cur[k] !== 'object' || cur[k] === null) {
            cur[k] = (typeof parts[i + 1] === 'number') ? [] : {};
          }
          cur = cur[k];
        }
        cur[parts[parts.length - 1]] = value;
        this.replaceMvuData(d, opt);
        return value;
      },
      deleteMvuVariable: function(path, opt) {
        var d = this.getMvuData(opt);
        var parts = parsePath(path);
        var cur = d;
        for (var i = 0; i < parts.length - 1; i++) {
          if (cur == null) return false;
          cur = cur[parts[i]];
        }
        if (cur != null) { delete cur[parts[parts.length - 1]]; this.replaceMvuData(d, opt); return true; }
        return false;
      },
      removeMvuData: function(opt) {
        AndroidTavBridge.removeData(scopeId(opt));
        return true;
      }
    };
  }

  // ---- 酒馆助手兼容: 卡的 MVU 就绪判定 ----
  if (typeof window.waitGlobalInitialized !== 'function') {
    window.waitGlobalInitialized = function(name) {
      return new Promise(function(resolve) {
        if (typeof window[name] !== 'undefined') { resolve(window[name]); return; }
        var n = 0;
        var timer = setInterval(function() {
          if (typeof window[name] !== 'undefined' || ++n > 100) {
            clearInterval(timer);
            resolve(window[name]);
          }
        }, 100);
      });
    };
  }
  if (typeof window.getCurrentMessageId !== 'function') {
    window.getCurrentMessageId = function() { return __MSG_ID; };
  }

  // ---- TavoJS 变量桥 ----
  window.tav = window.tav || {};
  if (typeof window.tav._getVariable !== 'function') {
    window.tav._getVariable = function(name, def) {
      var raw = AndroidTavBridge.getVar(__MSG_ID, name);
      if (raw == null) return def;
      try { return JSON.parse(raw); } catch (e) { return def; }
    };
    window.tav._setVariable = function(name, value) {
      AndroidTavBridge.setVar(__MSG_ID, name, JSON.stringify(value == null ? null : value));
    };
    window.tav._updateVariable = function(name, value) {
      AndroidTavBridge.setVar(__MSG_ID, name, JSON.stringify(value == null ? null : value));
    };
    window.tav._unsetVariable = function(name) {
      AndroidTavBridge.unsetVar(__MSG_ID, name);
    };
  }

  // ---- 酒馆助手变量函数 (MVU 库的宿主协议, async) ----
  function thOpt(opt) { return opt || {}; }
  function thGet(opt) {
    return Promise.resolve(JSON.parse(AndroidTavBridge.getData(scopeId(opt)) || '{}'));
  }
  function thSet(data, opt) {
    AndroidTavBridge.setData(scopeId(opt), JSON.stringify(data == null ? {} : data));
    return Promise.resolve();
  }
  function thUpdateWith(fn, opt) {
    return thGet(opt).then(function(d) {
      var r = (typeof fn === 'function') ? fn(d) : undefined;
      return thSet(r !== undefined ? r : d, opt);
    });
  }
  function thAssign(vars, opt) {
    return thGet(opt).then(function(d) {
      function merge(dst, src) {
        for (var k in src) {
          if (src[k] && typeof src[k] === 'object' && !Array.isArray(src[k]) &&
              dst[k] && typeof dst[k] === 'object' && !Array.isArray(dst[k])) merge(dst[k], src[k]);
          else dst[k] = src[k];
        }
        return dst;
      }
      merge(d, vars || {});
      return thSet(d, opt).then(function() { return d; });
    });
  }
  function thDelete(path, opt) {
    return Promise.resolve(window.Mvu.deleteMvuVariable(path, opt));
  }
  var TH_REAL = {
    getVariables: thGet,
    replaceVariables: thSet,
    updateVariablesWith: thUpdateWith,
    insertOrAssignVariables: thAssign,
    deleteVariable: thDelete,
    getCurrentMessageId: window.getCurrentMessageId
  };
  // 全局函数 (酒馆助手注入到消息 iframe 的同款)
  window.getVariables = TH_REAL.getVariables;
  window.replaceVariables = TH_REAL.replaceVariables;
  window.updateVariablesWith = TH_REAL.updateVariablesWith;
  window.insertOrAssignVariables = TH_REAL.insertOrAssignVariables;
  window.deleteVariable = TH_REAL.deleteVariable;
  // TavernHelper: 真方法优先, 未定义方法由 Proxy 兜底为无害空实现
  window.TavernHelper = new Proxy(TH_REAL, {
    get: function(t, k) {
      if (k in t) return t[k];
      if (k === Symbol.toPrimitive) return function() { return ''; };
      return function() { return Promise.resolve(undefined); };
    },
    set: function() { return true; }
  });

  // ---- 透明覆盖层穿透 + 滚动内容探测 (TouchRelayWebView 手势路由) ----
  // ST 卡常用 opacity:0 隐藏弹窗但忘了 display:none —— 透明层照样拦截
  // 触摸 (opacity 不参与 hit-test), 把主界面罩死。修复: 透明元素
  // 强制 pointer-events:none, 恢复不透明时还原。
  var __PE_MARK = 'data-rk-pe';
  function __fixInvisibleLayers() {
    var els = document.querySelectorAll('*');
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      try {
        var cs = getComputedStyle(el);
        if (parseFloat(cs.opacity) === 0) {
          if (el.style.pointerEvents !== 'none') {
            el.setAttribute(__PE_MARK, el.style.pointerEvents || '');
            el.style.pointerEvents = 'none';
          }
        } else if (el.hasAttribute(__PE_MARK)) {
          el.style.pointerEvents = el.getAttribute(__PE_MARK);
          el.removeAttribute(__PE_MARK);
        }
      } catch (e) {}
    }
  }
  // 元素及其祖先链是否实际可见 (排除透明层内的可滚元素)
  function __isEffectivelyVisible(el) {
    for (var n = el; n && n.nodeType === 1; n = n.parentElement) {
      var cs = getComputedStyle(n);
      if (cs.display === 'none' || cs.visibility === 'hidden' || parseFloat(cs.opacity) === 0) return false;
    }
    return true;
  }
  // 滚动内容探测: 无可滚内容时手势转发聊天列表
  function __probeScrollable() {
    try {
      var de = document.documentElement, bd = document.body;
      if (de && de.scrollHeight > innerHeight + 5) return true;
      if (bd && bd.scrollHeight > innerHeight + 5) return true;
      var els = document.querySelectorAll('*');
      for (var i = 0; i < els.length; i++) {
        var el = els[i];
        if (el.scrollHeight <= el.clientHeight + 5 && el.scrollWidth <= el.clientWidth + 5) continue;
        var cs = getComputedStyle(el);
        var oy = cs.overflowY, ox = cs.overflowX;
        if (oy !== 'auto' && oy !== 'scroll' && ox !== 'auto' && ox !== 'scroll') continue;
        var r = el.getBoundingClientRect();
        if (r.width > 1 && r.height > 1 && r.bottom > 0 && r.top < innerHeight && r.right > 0 && r.left < innerWidth) {
          if (__isEffectivelyVisible(el)) return true;
        }
      }
    } catch (e) {}
    return false;
  }
  var __lastProbe = null;
  function __reportProbe() {
    try {
      __fixInvisibleLayers();
      var v = __probeScrollable();
      if (v !== __lastProbe && typeof AndroidTouchBridge !== 'undefined') {
        __lastProbe = v;
        AndroidTouchBridge.setConsumable(v);
      }
    } catch (e) {}
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', function() { setTimeout(__reportProbe, 300); });
  } else {
    setTimeout(__reportProbe, 300);
  }
  window.addEventListener('load', function() { setTimeout(__reportProbe, 500); });
  // 视图切换/弹窗开合会改 DOM class/style: 监听后节流重探
  var __probeTimer = null;
  function __installProbeObserver() {
    if (window.__probeObserverInstalled) return;
    var target = document.documentElement || document;
    if (!target) return;
    try {
      new MutationObserver(function() {
        if (__probeTimer) return;
        __probeTimer = setTimeout(function() { __probeTimer = null; __reportProbe(); }, 400);
      }).observe(target, { childList: true, subtree: true, attributes: true, attributeFilter: ['class', 'style', 'hidden'] });
      window.__probeObserverInstalled = true;
    } catch (e) {}
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', __installProbeObserver);
  }
  __installProbeObserver();
  setTimeout(__installProbeObserver, 1000);
  setInterval(__reportProbe, 2500);
})();
</script>
""".trimIndent()
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
    messageId: String,
    charId: String,
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
              // Mvu / TavernHelper 由后续宿主 API 脚本提供真实实现, 不在此 stub
              if (typeof window.SillyTavern === 'undefined') window.SillyTavern = makeStub();
              if (typeof window.parent === 'undefined' || window.parent === window) {
                try { /* noop */ } catch (e) {}
              }
            })();
            """.trimIndent()
        )
        append("</script>")
        // 宿主 API: MVU 变量库 + 酒馆助手就绪判定 + TavoJS 变量桥
        append(buildHostApiScript(messageId, charId))
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
