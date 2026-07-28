package me.rerere.rikkahub.ui.components.richtext

import android.content.ClipData
import android.net.Uri
import android.view.MotionEvent
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import me.rerere.highlight.HighlightText
import me.rerere.highlight.HighlightTextColorPalette
import me.rerere.highlight.Highlighter
import me.rerere.highlight.LocalHighlighter
import me.rerere.highlight.buildHighlightText
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Code
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Download04
import me.rerere.hugeicons.stroke.Eye
import me.rerere.hugeicons.stroke.View
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.webview.WebView
import me.rerere.rikkahub.ui.components.webview.WebViewContentCache
import me.rerere.rikkahub.ui.components.webview.rememberWebViewState
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.modifier.onClick
import me.rerere.rikkahub.ui.theme.AtomOneDarkPalette
import me.rerere.rikkahub.ui.theme.AtomOneLightPalette
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.utils.toDp
import kotlin.time.Clock

private const val COLLAPSE_LINES = 10
private val PREVIEWABLE_LANGUAGES = setOf("html", "svg")

@Composable
fun HighlightCodeBlock(
    code: String,
    language: String,
    modifier: Modifier = Modifier,
    completeCodeBlock: Boolean = true,
    style: TextStyle? = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
) {
    val darkMode = LocalDarkMode.current
    val colorPalette = if (darkMode) AtomOneDarkPalette else AtomOneLightPalette
    val scrollState = rememberScrollState()
    val clipboardManager = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val navController = LocalNavController.current
    val context = LocalContext.current
    val settings = LocalSettings.current
    val normalizedLanguage = remember(language) { language.lowercase() }
    val canInlinePreview = completeCodeBlock && normalizedLanguage in PREVIEWABLE_LANGUAGES
    var previewMode by remember(canInlinePreview, code, normalizedLanguage) {
        mutableStateOf(canInlinePreview)
    }

    var isExpanded by remember(settings.displaySetting.codeBlockAutoCollapse) {
        mutableStateOf(!settings.displaySetting.codeBlockAutoCollapse)
    }
    val autoWrap = settings.displaySetting.codeBlockAutoWrap
    val showLineNumbers = settings.displaySetting.showLineNumbers

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                try {
                    context.contentResolver.openOutputStream(it)?.use { outputStream ->
                        outputStream.write(code.toByteArray())
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    Column(
        modifier = modifier
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            HighlightCodeActions(
                language = language,
                scope = scope,
                clipboardManager = clipboardManager,
                code = code,
                createDocumentLauncher = createDocumentLauncher,
                navController = navController,
                completeCodeBlock = completeCodeBlock,
                previewMode = previewMode,
                canInlinePreview = canInlinePreview,
                onTogglePreviewMode = {
                    previewMode = !previewMode
                },
            )
        }
        Column(
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)
        ) {
            when {
                canInlinePreview && previewMode -> {
                    CodeBlockPreview(
                        code = code,
                        language = normalizedLanguage,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                completeCodeBlock && normalizedLanguage == "mermaid" -> {
                    Mermaid(
                        code = code,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                else -> {
                    val textStyle = LocalTextStyle.current.merge(style)
                    val codeLines = remember(code) { code.lines() }
                    val collapsedCode = remember(codeLines) { codeLines.take(COLLAPSE_LINES).joinToString("\n") }
                    val displayCode = if (isExpanded) code else collapsedCode
                    val displayLines = remember(displayCode) { displayCode.lines() }

                    // 如果显示行号且自动换行，需要逐行渲染以保持对齐
                    when {
                        showLineNumbers && autoWrap -> {
                            CodeBlockWithLineNumbersWrapped(
                                displayLines = displayLines,
                                language = language,
                                textStyle = textStyle,
                                colorPalette = colorPalette,
                            )
                        }
                        else -> {
                            CodeBlockDefault(
                                displayCode = displayCode,
                                displayLines = displayLines,
                                language = language,
                                textStyle = textStyle,
                                colorPalette = colorPalette,
                                autoWrap = autoWrap,
                                showLineNumbers = showLineNumbers,
                                scrollState = scrollState,
                            )
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    // 代码折叠按钮
                    if (settings.displaySetting.codeBlockAutoCollapse && codeLines.size > COLLAPSE_LINES) {
                        Box(
                            modifier = Modifier
                                .onClick {
                                    isExpanded = !isExpanded
                                }
                                .fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (isExpanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.size(textStyle.fontSize.toDp())
                                )
                                Text(
                                    text = if (isExpanded) {
                                        stringResource(id = R.string.code_block_collapse)
                                    } else {
                                        stringResource(id = R.string.code_block_expand)
                                    },
                                    fontSize = textStyle.fontSize,
                                    lineHeight = textStyle.lineHeight,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CodeBlockWithLineNumbersWrapped(
    displayLines: List<String>,
    language: String,
    textStyle: TextStyle,
    colorPalette: HighlightTextColorPalette,
) {
    val lineNumberWidth = remember(displayLines.size) {
        displayLines.size.toString().length
    }
    SelectionContainer {
        Column {
            displayLines.forEachIndexed { index, line ->
                Row(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = (index + 1).toString().padStart(lineNumberWidth, ' '),
                        fontSize = textStyle.fontSize,
                        lineHeight = textStyle.lineHeight,
                        fontFamily = JetbrainsMono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        softWrap = false,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    HighlightText(
                        code = line,
                        language = language,
                        fontSize = textStyle.fontSize,
                        lineHeight = textStyle.lineHeight,
                        colors = colorPalette,
                        overflow = TextOverflow.Visible,
                        softWrap = true,
                        fontFamily = JetbrainsMono,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun CodeBlockDefault(
    displayCode: String,
    displayLines: List<String>,
    language: String,
    textStyle: TextStyle,
    colorPalette: HighlightTextColorPalette,
    autoWrap: Boolean,
    showLineNumbers: Boolean,
    scrollState: ScrollState,
) {
    Row(
        modifier = Modifier.then(
            if (autoWrap) {
                Modifier
            } else {
                Modifier.horizontalScroll(scrollState)
            }
        )
    ) {
        // 行号列
        if (showLineNumbers) {
            val lineNumberWidth = remember(displayLines.size) {
                displayLines.size.toString().length
            }
            Column(
                modifier = Modifier.padding(end = 8.dp)
            ) {
                displayLines.forEachIndexed { index, _ ->
                    Text(
                        text = (index + 1).toString().padStart(lineNumberWidth, ' '),
                        fontSize = textStyle.fontSize,
                        lineHeight = textStyle.lineHeight,
                        fontFamily = JetbrainsMono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        softWrap = false,
                    )
                }
            }
        }

        // 代码列
        SelectionContainer {
            HighlightText(
                code = displayCode,
                language = language,
                modifier = Modifier.animateContentSize(),
                fontSize = textStyle.fontSize,
                lineHeight = textStyle.lineHeight,
                colors = colorPalette,
                overflow = TextOverflow.Visible,
                softWrap = autoWrap,
                fontFamily = JetbrainsMono
            )
        }
    }
}

@Composable
private fun HighlightCodeActions(
    language: String,
    scope: CoroutineScope,
    clipboardManager: Clipboard,
    code: String,
    createDocumentLauncher: ManagedActivityResultLauncher<String, Uri?>,
    navController: Navigator,
    completeCodeBlock: Boolean = true,
    previewMode: Boolean = false,
    canInlinePreview: Boolean = false,
    onTogglePreviewMode: () -> Unit = {},
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = language,
            fontSize = 12.sp,
            lineHeight = 12.sp,
            fontFamily = JetbrainsMono,
            color = MaterialTheme.colorScheme.onSurfaceVariant
                .copy(alpha = 0.5f),
        )
        Spacer(Modifier.weight(1f))
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val iconSize = 16.dp
            val iconTint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)

            Icon(
                imageVector = HugeIcons.Download04,
                contentDescription = stringResource(id = R.string.chat_page_save),
                tint = iconTint,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .onClick {
                        val extension = when (language.lowercase()) {
                            "kotlin" -> "kt"
                            "java" -> "java"
                            "python" -> "py"
                            "javascript" -> "js"
                            "typescript" -> "ts"
                            "cpp", "c++" -> "cpp"
                            "c" -> "c"
                            "html" -> "html"
                            "css" -> "css"
                            "xml" -> "xml"
                            "json" -> "json"
                            "yaml", "yml" -> "yml"
                            "markdown", "md" -> "md"
                            "sql" -> "sql"
                            "sh", "bash" -> "sh"
                            "svg" -> "svg"
                            else -> "txt"
                        }
                        createDocumentLauncher.launch(
                            "code_${
                                Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
                            }.$extension"
                        )
                    }
                    .padding(4.dp)
                    .size(iconSize)
            )

            Icon(
                imageVector = HugeIcons.Copy01,
                contentDescription = stringResource(id = R.string.code_block_copy),
                tint = iconTint,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .onClick {
                        scope.launch {
                            clipboardManager.setClipEntry(ClipEntry(ClipData.newPlainText("code", code)))
                        }
                    }
                    .padding(4.dp)
                    .size(iconSize)
            )

            val normalizedLanguage = language.lowercase()
            if (canInlinePreview) {
                Icon(
                    imageVector = if (previewMode) HugeIcons.Code else HugeIcons.View,
                    contentDescription = if (previewMode) "Code" else stringResource(id = R.string.code_block_preview),
                    tint = iconTint,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .onClick {
                            onTogglePreviewMode()
                        }
                        .padding(4.dp)
                        .size(iconSize)
                )
            }

            if (completeCodeBlock && normalizedLanguage in PREVIEWABLE_LANGUAGES) {
                Icon(
                    imageVector = HugeIcons.Eye,
                    contentDescription = stringResource(id = R.string.code_block_preview),
                    tint = iconTint,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .onClick {
                            val content = buildCodePreviewHtml(code = code, language = normalizedLanguage)
                            val contentId = WebViewContentCache.store(context.cacheDir, content)
                            navController.navigate(Screen.WebView(contentId = contentId))
                        }
                        .padding(4.dp)
                        .size(iconSize)
                )
            }
        }
    }
}

@Composable
private fun CodeBlockPreview(
    code: String,
    language: String,
    modifier: Modifier = Modifier,
) {
    // 内容高度（CSS px，1:1 缩放下等同 dp）。0 = 尚未测到，先用占位高度
    var contentHeightCssPx by remember(code, language) { mutableIntStateOf(0) }

    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    // JS 回传高度的接口（与项目内 HtmlContent.kt 同款机制）
    val heightInterface = remember(code, language) {
        object {
            @android.webkit.JavascriptInterface
            fun postHeight(height: Int) {
                if (height > 0) mainHandler.post { contentHeightCssPx = height }
            }
        }
    }

    val state = rememberWebViewState(
        data = buildCodePreviewHtml(code = code, language = language),
        baseUrl = "https://rikkahub.local",
        mimeType = "text/html",
        interfaces = mapOf("AndroidHeight" to heightInterface),
        settings = {
            builtInZoomControls = true
            displayZoomControls = false
            // 1:1 渲染，不缩放（v4 已验证）
            useWideViewPort = false
            loadWithOverviewMode = false
        }
    )

    // 页面加载后注入高度监听：ResizeObserver 持续测量内容高度并回传。
    // 同时锁定 overflow:hidden —— WebView 自身不滚动，高度纯由内容撑开，
    // 滚动完全交给外层 LazyColumn。这是防止"动态高度触发重算缩放→压缩"的关键。
    LaunchedEffect(state.isLoading) {
        if (!state.isLoading) {
            state.webView?.evaluateJavascript(
                """
                (function(){
                    try{
                        document.documentElement.style.overflow='hidden';
                        if(document.body) document.body.style.overflow='hidden';
                    }catch(e){}
                    console.error('[PREVIEW-DIAG] dpr='+window.devicePixelRatio
                        +' innerW='+window.innerWidth+' innerH='+window.innerHeight
                        +' docSH='+document.documentElement.scrollHeight
                        +' bodySH='+(document.body?document.body.scrollHeight:0)
                        +' docCH='+document.documentElement.clientHeight
                        +' bodyCH='+(document.body?document.body.clientHeight:0));
                    if(window.__hObsInstalled) return;
                    window.__hObsInstalled=true;
                    var last=0, timer=null;
                    function measure(){
                        var h=Math.max(
                            document.body?document.body.scrollHeight:0,
                            document.documentElement?document.documentElement.scrollHeight:0
                        );
                        if(Math.abs(h-last)>=8){ last=h; console.error('[PREVIEW-DIAG] postHeight h='+h); AndroidHeight.postHeight(h); }
                    }
                    function deb(){ if(timer)clearTimeout(timer); timer=setTimeout(measure,150); }
                    if(typeof ResizeObserver!=='undefined'){
                        new ResizeObserver(deb).observe(document.documentElement);
                    }
                    window.addEventListener('load',measure,{passive:true});
                    window.addEventListener('resize',measure,{passive:true});
                    measure();
                })();
                """.trimIndent(), null
            )
        }
    }

    // WebView 高度 = 内容全高（CSS px 直接当 dp），把它当成一张"长图"
    val webViewHeightDp = if (contentHeightCssPx == 0) 220.dp else contentHeightCssPx.dp.coerceAtMost(8000.dp)
    val scrollState = rememberScrollState()

    // 外面套一个固定高度的 Compose 滚动框：框内可上下滑动浏览 HTML，
    // 滑到边界后由 Compose 原生嵌套滚动自动接力给外层聊天列表（不冲突）。
    // WebView 自身不滚动（高度=内容+overflow:hidden），只作为静态长图被滚动框承载。
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .height(420.dp)
            .verticalScroll(scrollState)
    ) {
        WebView(
            state = state,
            modifier = Modifier.fillMaxWidth().height(webViewHeightDp),
            onCreated = { webView ->
                // 强制 1:1 初始缩放，防止内容被压缩
                webView.setInitialScale(100)
                // 切断 WebView 嵌套滚动协议，自身完全不参与滚动
                webView.isNestedScrollingEnabled = false
            },
        )
    }
}

private fun buildCodePreviewHtml(code: String, language: String): String {
    return if (language == "svg") {
        """<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width, initial-scale=1.0"></head><body style="margin:0;display:flex;justify-content:center;align-items:center;min-height:100vh;">$code</body></html>"""
    } else {
        val trimmed = code.trim()
        val hasViewport = trimmed.contains("name=\"viewport\"", ignoreCase = true)
        val isCompleteDoc = trimmed.startsWith("<!DOCTYPE", ignoreCase = true) ||
            trimmed.startsWith("<html", ignoreCase = true)
        when {
            // 完整文档且已含 viewport：原样返回
            isCompleteDoc && hasViewport -> code
            // 完整文档但缺 viewport：注入 viewport，避免被当成宽页面缩放
            isCompleteDoc -> code.replaceFirst(
                Regex("<head[^>]*>", RegexOption.IGNORE_CASE),
                "$0<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">"
            )
            // HTML 片段：包裹成带 viewport 的完整文档
            else -> """<!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1.0"></head><body style="margin:8px">$code</body></html>"""
        }
    }
}

class HighlightCodeVisualTransformation(
    val language: String,
    val highlighter: Highlighter,
    val darkMode: Boolean
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val annotatedString = try {
            val colorPalette = if (darkMode) AtomOneDarkPalette else AtomOneLightPalette
            if (text.text.isEmpty()) {
                AnnotatedString("")
            } else {
                runBlocking {
                    val tokens = highlighter.highlight(text.text, language)
                    buildAnnotatedString {
                        tokens.forEach { token ->
                            buildHighlightText(token, colorPalette)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            AnnotatedString(text.text)
        }

        return TransformedText(
            text = annotatedString,
            offsetMapping = OffsetMapping.Identity
        )
    }

    companion object {
        @Composable
        fun regex() = HighlightCodeVisualTransformation(
            language = "regex",
            highlighter = LocalHighlighter.current,
            darkMode = LocalDarkMode.current,
        )
    }
}
