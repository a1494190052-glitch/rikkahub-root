package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.workspace.RootShellRunner
import org.xmlpull.v1.XmlPullParser
import java.io.File

private const val UI_TREE_TIMEOUT_MS = 20_000L
private const val UI_TREE_MAX_ELEMENTS = 220

/**
 * root_ui_tree：通过 root 的 uiautomator 导出当前屏幕 UI 控件树，
 * 解析为紧凑的元素清单（中心坐标 + 文字 + 可点击标记）返回给 AI，
 * 用于精准定位点击位置。比截图更快、更省 token。
 */
internal fun buildUiTreeTool(context: Context): Tool {
    val runner = RootShellRunner()
    return Tool(
        name = "root_ui_tree",
        description = "Dump the current screen's UI control tree via root (uiautomator) and return " +
            "a compact list of on-screen elements: center coordinates, class, text/description and " +
            "flags (click/scroll/edit). Use it to precisely locate elements before tapping with " +
            "root_shell `input tap X Y`. Prefer this over root_screenshot for normal app screens " +
            "(faster, cheaper); use root_screenshot when the tree is empty or the UI is canvas/game-like. " +
            "Requires root.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {},
                required = emptyList(),
            )
        },
        needsApproval = { false },
        execute = {
            val file = File(context.cacheDir, "ui_dump.xml")
            val result = withContext(Dispatchers.IO) {
                runInterruptible {
                    runner.execute("uiautomator dump ${file.absolutePath}", UI_TREE_TIMEOUT_MS)
                }
            }
            val body = if (result.exitCode == 0 && file.exists() && file.length() > 0L) {
                val elements = runCatching { parseUiXml(file) }.getOrDefault(emptyList())
                file.delete()
                if (elements.isEmpty()) {
                    "UI tree is empty (the foreground app may block uiautomator, or the screen is " +
                        "off/locked). Try root_screenshot instead."
                } else {
                    buildString {
                        appendLine("UI elements: (centerX,centerY) [flags] class \"text\"")
                        elements.take(UI_TREE_MAX_ELEMENTS).forEach { appendLine(it) }
                        if (elements.size > UI_TREE_MAX_ELEMENTS) {
                            appendLine("... (${elements.size - UI_TREE_MAX_ELEMENTS} more truncated)")
                        }
                        append("Tap with: root_shell -> input tap centerX centerY")
                    }
                }
            } else {
                file.delete()
                "uiautomator dump failed (exitCode=${result.exitCode}): ${result.stderr}. " +
                    "Try root_screenshot instead."
            }
            listOf(UIMessagePart.Text(body))
        },
    )
}

private val BOUNDS_REGEX = Regex("""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""")

private fun parseUiXml(file: File): List<String> {
    val out = ArrayList<String>()
    file.inputStream().use { ins ->
        val parser = Xml.newPullParser()
        parser.setInput(ins, null)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "node") {
                val text = parser.getAttributeValue(null, "text").orEmpty()
                    .replace('\n', ' ').trim()
                val desc = parser.getAttributeValue(null, "content-desc").orEmpty()
                    .replace('\n', ' ').trim()
                val clazz = parser.getAttributeValue(null, "class").orEmpty().substringAfterLast('.')
                val bounds = parser.getAttributeValue(null, "bounds").orEmpty()
                val clickable = parser.getAttributeValue(null, "clickable") == "true"
                val scrollable = parser.getAttributeValue(null, "scrollable") == "true"
                val checkable = parser.getAttributeValue(null, "checkable") == "true"
                if (clickable || scrollable || checkable || text.isNotEmpty() || desc.isNotEmpty()) {
                    val m = BOUNDS_REGEX.find(bounds)
                    val center = if (m != null) {
                        val (l, t, r, b) = m.destructured
                        "(${(l.toInt() + r.toInt()) / 2},${(t.toInt() + b.toInt()) / 2})"
                    } else {
                        "(?,?)"
                    }
                    val flags = buildList {
                        if (clickable) add("click")
                        if (scrollable) add("scroll")
                        if (checkable) add("check")
                        if (clazz.contains("EditText")) add("edit")
                    }.joinToString(",").ifEmpty { "-" }
                    val label = when {
                        text.isNotEmpty() -> "\"$text\""
                        desc.isNotEmpty() -> "desc=\"$desc\""
                        else -> ""
                    }
                    out.add("$center [$flags] $clazz $label".trim())
                }
            }
            event = parser.next()
        }
    }
    return out
}
