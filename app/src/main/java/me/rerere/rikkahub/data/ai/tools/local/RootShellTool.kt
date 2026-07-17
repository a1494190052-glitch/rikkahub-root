package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.workspace.RootShellRunner

private const val ROOT_SHELL_TIMEOUT_MAX_SECONDS = 600L
private const val ROOT_SHELL_DEFAULT_TIMEOUT_MS = 30_000L

internal fun buildRootShellTool(): Tool {
    val runner = RootShellRunner()
    return Tool(
        name = "root_shell",
        description = """
            Run a shell command on the Android host system with ROOT privileges (via su).
            The device must be rooted and the user must have granted this app root access.
            Useful for system-level operations: pm (install/uninstall apps), am (start activities),
            input (tap/swipe/keyevent UI automation), screencap (screenshots to /sdcard),
            settings (system settings), reading/writing protected files, setprop, etc.
            Commands run as the root user on the HOST system, not inside any container.
            This is powerful and dangerous: always explain to the user what a command does
            before running anything destructive.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("command", buildJsonObject {
                        put("type", "string")
                        put("description", "Shell command to run as root")
                    })
                    put("timeout", buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            "Command timeout in seconds. Defaults to 30, max $ROOT_SHELL_TIMEOUT_MAX_SECONDS."
                        )
                    })
                },
                required = listOf("command"),
            )
        },
        needsApproval = { false },
        execute = {
            val params = it.jsonObject
            val command = params["command"]?.jsonPrimitive?.contentOrNull
                ?: error("command is required")
            val timeoutMillis = params["timeout"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                ?.coerceIn(1L, ROOT_SHELL_TIMEOUT_MAX_SECONDS)
                ?.times(1_000L)
                ?: ROOT_SHELL_DEFAULT_TIMEOUT_MS
            val result = withContext(Dispatchers.IO) {
                runInterruptible {
                    runner.execute(command, timeoutMillis)
                }
            }
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("exitCode", result.exitCode)
                        put("stdout", result.stdout)
                        put("stderr", result.stderr)
                        put("timedOut", result.timedOut)
                        if (result.truncated) put("truncated", true)
                    }.toString()
                )
            )
        },
    )
}
