package me.rerere.rikkahub.ui.components.richtext

import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import ru.noties.jlatexmath.JLatexMathDrawable
import ru.noties.jlatexmath.JLatexMathSplitter

/**
 * 行内公式尺寸测量结果缓存：同一 (公式,字号) 常量复用 Rect，
 * 避免长对话/流式渲染时反复重建 JLatexMathDrawable（测量阶段的主要开销）。
 * 用 LRU 之后优先：保持条目数量可控，命中即移动到最后，超过上限淘汰最旧。
 */
private class LatexSizeCache : java.util.LinkedHashMap<LatexSizeKey, Rect>(64, 0.75f, true) {
    class LatexSizeKey(val latex: String, val fontSize: Float) {
        override fun equals(other: Any?) =
            other is LatexSizeKey && other.latex == latex && other.fontSize == fontSize
        override fun hashCode() = latex.hashCode() * 31 + fontSize.hashCode()
    }
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<LatexSizeKey, Rect>?) = size > 256
}

private val latexSizeCache = LatexSizeCache()

fun assumeLatexSize(latex: String, fontSize: Float): Rect {
    val key = LatexSizeCache.LatexSizeKey(latex, fontSize)
    synchronized(latexSizeCache) {
        latexSizeCache[key]?.let { return it }
    }
    // 缓存未命中：构建一次 Drawable 测量尺寸并存回缓存
    val measured = runCatching {
        JLatexMathDrawable.builder(latex)
            .textSize(fontSize)
            .padding(0)
            .build()
            .bounds
    }.getOrElse { Rect(0, 0, 0, 0) }
    synchronized(latexSizeCache) {
        latexSizeCache[key] = measured
    }
    return measured
}

@Composable
fun LatexText(
    latex: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = TextUnit.Unspecified,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current
) {
    val style = style.merge(
        fontSize = fontSize,
        color = color
    )
    val density = LocalDensity.current

    val drawable = remember(latex, fontSize, style) {
        runCatching {
            with(density) {
                getLatexDrawable(
                    latex = processLatex(latex),
                    fontSize = fontSize.toPx(),
                    color = style.color.toArgb(),
                    background = style.background.toArgb()
                )
            }
        }.onFailure {
            it.printStackTrace()
        }.getOrNull()
    }

    if (drawable != null) {
        with(density) {
            Canvas(
                modifier = modifier
                    .size(
                        width = drawable.bounds.width().toDp(),
                        height = drawable.bounds.height().toDp()
                    )
            ) {
                drawable.draw(drawContext.canvas.nativeCanvas)
            }
        }
    } else {
        Text(
            text = latex,
            style = style,
            modifier = modifier
        )
    }
}

fun getLatexDrawable(
    latex: String,
    fontSize: Float,
    color: Int,
    background: Int
): JLatexMathDrawable? {
    return runCatching {
        JLatexMathDrawable.builder(processLatex(latex))
            .textSize(fontSize)
            .color(color)
            .background(background)
            .padding(0)
            .align(JLatexMathDrawable.ALIGN_LEFT)
            .build()
    }.onFailure {
        it.printStackTrace()
    }.getOrNull()
}

/**
 * 将一条行内公式按顶层运算符水平拆分为多段 Drawable，
 * 以便在文本流中换行，避免单体公式过长被挤出屏幕。
 * 拆分失败时返回空列表，调用方需自行回退。
 */
fun splitLatex(
    latex: String,
    maxWidthPx: Float,
    fontSize: Float,
    color: Int
): List<JLatexMathDrawable> {
    return runCatching {
        JLatexMathSplitter.split(processLatex(latex), maxWidthPx, fontSize, color)
    }.onFailure {
        it.printStackTrace()
    }.getOrElse { emptyList() }
}

@Composable
fun LatexDrawable(
    drawable: JLatexMathDrawable,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    with(density) {
        Canvas(
            modifier = modifier.size(
                width = drawable.bounds.width().toDp(),
                height = drawable.bounds.height().toDp()
            )
        ) {
            drawable.draw(drawContext.canvas.nativeCanvas)
        }
    }
}

private val inlineDollarRegex = Regex("""^\$(.*?)\$""", RegexOption.DOT_MATCHES_ALL)
private val displayDollarRegex = Regex("""^\$\$(.*?)\$\$""", RegexOption.DOT_MATCHES_ALL)
private val inlineParenRegex = Regex("""^\\\((.*?)\\\)""", RegexOption.DOT_MATCHES_ALL)
private val displayBracketRegex = Regex("""^\\\[(.*?)\\\]""", RegexOption.DOT_MATCHES_ALL)

private fun processLatex(latex: String): String {
    val trimmed = latex.trim()
    return when {
        displayDollarRegex.matches(trimmed) ->
            displayDollarRegex.find(trimmed)?.groupValues?.get(1)?.trim() ?: trimmed

        inlineDollarRegex.matches(trimmed) ->
            inlineDollarRegex.find(trimmed)?.groupValues?.get(1)?.trim() ?: trimmed

        displayBracketRegex.matches(trimmed) ->
            displayBracketRegex.find(trimmed)?.groupValues?.get(1)?.trim() ?: trimmed

        inlineParenRegex.matches(trimmed) ->
            inlineParenRegex.find(trimmed)?.groupValues?.get(1)?.trim() ?: trimmed

        else -> trimmed
    }
}
