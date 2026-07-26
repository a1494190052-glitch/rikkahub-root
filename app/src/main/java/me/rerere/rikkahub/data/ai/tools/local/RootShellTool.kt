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
import me.rerere.rikkahub.data.db.entity.ShellAuditEntity
import me.rerere.rikkahub.service.shell.ShellAuditLogger
import me.rerere.workspace.RootShellRunner
import me.rerere.workspace.ShellSessionManager

private const val ROOT_SHELL_TIMEOUT_MAX_SECONDS = 600L
private const val ROOT_SHELL_DEFAULT_TIMEOUT_MS = 30_000L

internal fun buildRootShellTool(
    shellSessionManager: ShellSessionManager? = null,
    shellAuditLogger: ShellAuditLogger? = null,
    isSubAgent: Boolean = false,
    // 用户全局审批覆盖: 返回 false = 一律自动放行; null = 默认(写操作需审批)
    approvalOverride: () -> Boolean? = { null },
): Tool {
    val runner = RootShellRunner()
    return Tool(
        name = "root_shell",
        description = """
            Run a shell command on the Android host system with ROOT privileges (via su).
            The device must be rooted and the user must have granted this app root access.
            Useful for system-level operations: pm (install/uninstall apps), am (start activities),
            input (tap/swipe/keyevent UI automation), screencap (screenshots to /sdcard),
            settings (system settings), reading/writing protected files, setprop, etc.
            Commands run in a PERSISTENT root shell session: cd, exported variables and
            background processes (&) carry over between calls (pass fresh=true for a one-shot process).
            The response includes the session's current cwd.
            INTERACTIVE & REMOTE patterns: (1) ssh with KEY auth works here: run
            `ssh -i KEYFILE -o StrictHostKeyChecking=accept-new user@host` once in this session —
            afterwards your commands run on the REMOTE host (reported exitCode/cwd follow the
            remote shell; `exit` returns to local). (2) sudo reads passwords from the tty, not stdin;
            pipe it instead: `echo 'password' | sudo -S cmd`. (3) NEVER launch full-screen TUI apps
            (vim, nano, htop, top, less) — edit files non-interactively (sed, awk, cat <<'EOF' > file).
            (4) For password-based ssh or any prompt-driven interaction (REPL, apt confirmations),
            use the pty_exec tool instead — it drives a real terminal with expect/respond steps.
            This is powerful and dangerous: always explain to the user what a command does
            before running anything destructive. Dangerous commands may be blocked by a safety guard.
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
                    put("fresh", buildJsonObject {
                        put("type", "boolean")
                        put("description", "true = run in a fresh one-shot process instead of the persistent session")
                    })
                },
                required = listOf("command"),
            )
        },
        needsApproval = if (isSubAgent) {
            { false } // 子代理无人审批, 全部自动放行 (子代理环境已受限)
        } else {
            { json ->
                approvalOverride() ?: run {
                    val command = json.jsonObject["command"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    ShellSafety.classify(command) == ShellRisk.WRITE
                }
            }
        },
        execute = {
            val params = it.jsonObject
            val command = params["command"]?.jsonPrimitive?.contentOrNull
                ?: error("command is required")
            val fresh = params["fresh"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() == true
            val timeoutMillis = params["timeout"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                ?.coerceIn(1L, ROOT_SHELL_TIMEOUT_MAX_SECONDS)
                ?.times(1_000L)
                ?: ROOT_SHELL_DEFAULT_TIMEOUT_MS

            // 安全闸门: 高危命令直接拒绝
            ShellSafety.blockReason(command)?.let { reason ->
                shellAuditLogger?.logCompleted(
                    source = ShellAuditEntity.SOURCE_AI_ROOT,
                    command = command,
                    status = ShellAuditEntity.STATUS_BLOCKED,
                    outputPreview = "Blocked: $reason",
                )
                return@Tool listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("blocked", true)
                            put("reason", reason)
                            put("message", "This command was blocked by the safety guard and was NOT executed.")
                        }.toString()
                    )
                )
            }

            val startedAt = System.currentTimeMillis()
            val auditId = shellAuditLogger?.start(
                source = ShellAuditEntity.SOURCE_AI_ROOT,
                command = command,
            )
            val result = try {
                if (!fresh && shellSessionManager != null) {
                    runInterruptible(Dispatchers.IO) {
                        shellSessionManager.execHostRoot(command, cwd = null, timeoutMillis = timeoutMillis)
                    }
                } else {
                    withContext(Dispatchers.IO) {
                        runInterruptible {
                            runner.execute(command, timeoutMillis)
                        }
                    }
                }
            } catch (e: Throwable) {
                shellAuditLogger?.finish(auditId ?: "", startedAt, ShellAuditEntity.STATUS_ERROR, null, e.message)
                throw e
            }
            val status = if (result.timedOut) ShellAuditEntity.STATUS_TIMEOUT else ShellAuditEntity.STATUS_DONE
            shellAuditLogger?.finish(
                auditId ?: "", startedAt, status, result.exitCode,
                (result.stdout + "\n" + result.stderr).trim(),
            )
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("exitCode", result.exitCode)
                        put("stdout", result.stdout)
                        put("stderr", result.stderr)
                        put("timedOut", result.timedOut)
                        if (result.truncated) put("truncated", true)
                        if (!fresh && shellSessionManager != null) {
                            put("cwd", shellSessionManager.currentCwd(null))
                        }
                    }.toString()
                )
            )
        },
    )
}
