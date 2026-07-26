package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.workspace.RootShellRunner
import java.io.File

private const val SCREENSHOT_TIMEOUT_MS = 20_000L
private const val SCREENSHOT_KEEP_COUNT = 4

/**
 * root_screenshot：通过 root 的 screencap 截取当前屏幕，
 * 以图片形式返回给多模态模型，让 AI 能够"看见"屏幕内容。
 */
internal fun buildRootScreenshotTool(context: Context): Tool {
    val runner = RootShellRunner()
    return Tool(
        name = "root_screenshot",
        description = "Capture a screenshot of the device's current screen via root (screencap) " +
            "and return it as an image that you can see. Use it to observe the screen before or " +
            "after UI automation actions (input tap/swipe/keyevent via root_shell), or whenever " +
            "the user asks what is on the screen. Requires root.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {},
                required = emptyList(),
            )
        },
        needsApproval = { false },
        execute = {
            val dir = File(context.cacheDir, "screenshots").apply { mkdirs() }
            dir.listFiles()
                ?.sortedBy { it.lastModified() }
                ?.dropLast(SCREENSHOT_KEEP_COUNT)
                ?.forEach { it.delete() }
            val file = File(dir, "screen_${System.currentTimeMillis()}.png")
            val result = withContext(Dispatchers.IO) {
                runInterruptible {
                    runner.execute("screencap -p ${file.absolutePath}", SCREENSHOT_TIMEOUT_MS)
                }
            }
            if (result.exitCode == 0 && file.exists() && file.length() > 0L) {
                listOf(
                    UIMessagePart.Text(
                        "Screenshot captured successfully. The image of the current screen is attached."
                    ),
                    UIMessagePart.Image(url = "file://${file.absolutePath}"),
                )
            } else {
                file.delete()
                listOf(
                    UIMessagePart.Text(
                        "Screenshot failed (exitCode=${result.exitCode}): ${result.stderr}"
                    )
                )
            }
        },
    )
}
