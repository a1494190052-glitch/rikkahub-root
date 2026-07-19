package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.entity.ShellAuditEntity
import me.rerere.rikkahub.service.shell.HeadlessPty
import me.rerere.rikkahub.service.shell.ShellAuditLogger
import me.rerere.rikkahub.ui.pages.extensions.workspace.buildWorkspaceProotLaunch
import me.rerere.rikkahub.ui.pages.extensions.workspace.prepareWorkspaceTerminalSession
import me.rerere.rikkahub.ui.pages.extensions.workspace.workspaceRootfsReady
import java.io.File
import java.util.regex.Pattern

private const val PTY_TIMEOUT_MAX_SECONDS = 600L
private const val PTY_DEFAULT_TIMEOUT_MS = 120_000L
private const val PTY_MAX_EXPECTS = 8

private val HOST_ENV = arrayOf(
    "TERM=xterm-256color",
    "PATH=/sbin:/system/bin:/system/xbin:/vendor/bin",
    "HOME=/sdcard",
    "LANG=C.UTF-8",
)

/**
 * pty_exec: 真 PTY + expect 式应答的一次性交互工具.
 * 解锁 workspace_shell / root_shell 做不到的交互场景: ssh 密码登录、sudo 密码、
 * REPL、apt/dpkg 确认提示等. 整次调用强制用户审批; expect.send 在审计日志里脱敏.
 */
internal fun buildPtyExecTool(
    context: Context,
    shellAuditLogger: ShellAuditLogger? = null,
): Tool {
    return Tool(
        name = "pty_exec",
        description = """
            Run an INTERACTIVE command inside a real pseudo-terminal (PTY) with scripted expect/respond steps.
            Use when a program asks questions that workspace_shell / root_shell cannot answer: ssh with PASSWORD login, sudo password prompts, remote shells, REPLs (python/node/mysql/psql), apt/dpkg confirmations, interactive installers.
            How it works: `command` starts in a fresh PTY; the engine walks `expect` in order — when recent screen text matches `pattern` (regex), `send` is typed (newline appended automatically). After all expects match, the optional `then` runs in the SAME session (e.g. commands inside the ssh login), and the call returns when it finishes.
            Response JSON: final screen text (clean, no ANSI codes), session exit code, matched expect count.
            `target`: "host" (default) = Android host ROOT shell via su; or a workspace name = that workspace's Ubuntu rootfs (has bash/apt; for ssh install the client once first: apt install -y openssh-client).
            Rules: every call requires user approval. Do NOT drive full-screen editors (vim/nano/htop/less) — that is fragile; edit files non-interactively instead. Do NOT use for plain non-interactive commands (use workspace_shell / root_shell).
            Secrets: expect.send values are redacted from the persistent audit log, but they DO appear in this chat — prefer ssh keys over passwords when possible.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("command", buildJsonObject {
                        put("type", "string")
                        put("description", "Interactive command to start in the PTY, e.g. ssh root@203.0.113.10 or sudo apt upgrade")
                    })
                    put("expect", buildJsonObject {
                        put("type", "array")
                        put(
                            "description",
                            "Ordered expect/respond steps: [{\"pattern\": \"assword:\", \"send\": \"my-password\"}, {\"pattern\": \"yes/no\", \"send\": \"yes\"}]. pattern is a regex matched against recent screen text; send gets a newline appended. Max $PTY_MAX_EXPECTS steps."
                        )
                        put("items", buildJsonObject { put("type", "object") })
                    })
                    put("then", buildJsonObject {
                        put("type", "string")
                        put("description", "Command to run in the SAME session after all expects matched (e.g. remote commands inside the ssh login). Its exit code is reported.")
                    })
                    put("target", buildJsonObject {
                        put("type", "string")
                        put("description", "\"host\" (default) = Android host root shell via su; or a workspace name = inside that workspace's Ubuntu rootfs")
                    })
                    put("timeout", buildJsonObject {
                        put("type", "integer")
                        put("description", "Total timeout in seconds for the whole interaction. Defaults to 120, max $PTY_TIMEOUT_MAX_SECONDS.")
                    })
                },
                required = listOf("command"),
            )
        },
        // 交互式会话整体视为写操作: 一律需要用户审批
        needsApproval = { true },
        execute = {
            val params = it.jsonObject
            val command = params["command"]?.jsonPrimitive?.contentOrNull
                ?: error("command is required")
            val thenCommand = params["then"]?.jsonPrimitive?.contentOrNull
            val target = params["target"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                .ifEmpty { "host" }
            val timeoutMillis = params["timeout"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                ?.coerceIn(1L, PTY_TIMEOUT_MAX_SECONDS)
                ?.times(1_000L)
                ?: PTY_DEFAULT_TIMEOUT_MS

            val expectArray = params["expect"]?.jsonArray.orEmpty()
            require(expectArray.size <= PTY_MAX_EXPECTS) { "expect supports at most $PTY_MAX_EXPECTS steps" }
            val expects = expectArray.map { el ->
                val obj = el.jsonObject
                val pattern = obj["pattern"]?.jsonPrimitive?.contentOrNull
                    ?: error("expect[].pattern is required")
                val send = obj["send"]?.jsonPrimitive?.contentOrNull
                    ?: error("expect[].send is required")
                val compiled = try {
                    Pattern.compile(pattern)
                } catch (e: Exception) {
                    error("invalid regex in expect[].pattern: ${e.message}")
                }
                compiled to send
            }
            val sendValues = expects.map { pair -> pair.second }

            // 安全闸门: 启动命令与收尾命令都过高危检测
            listOfNotNull(command, thenCommand).forEach { cmd ->
                ShellSafety.blockReason(cmd)?.let { reason ->
                    shellAuditLogger?.logCompleted(
                        source = ShellAuditEntity.SOURCE_AI_PTY,
                        command = redactSecrets(cmd, sendValues),
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
            }

            // 解析目标环境
            val pty = if (target == "host") {
                HeadlessPty("su", "/", emptyArray(), HOST_ENV, killTreeWithSu = true)
            } else {
                val workspacesDir = File(context.filesDir, "workspaces")
                val wsDir = File(workspacesDir, target)
                if (!wsDir.isDirectory) {
                    val available = workspacesDir.listFiles()
                        ?.filter { f -> f.isDirectory }
                        ?.map { f -> f.name }
                        .orEmpty()
                    return@Tool listOf(
                        UIMessagePart.Text(
                            buildJsonObject {
                                put("error", "workspace '$target' not found")
                                put("availableWorkspaces", available.joinToString(", ").ifEmpty { "(none)" })
                            }.toString()
                        )
                    )
                }
                if (!workspaceRootfsReady(context, target)) {
                    return@Tool listOf(
                        UIMessagePart.Text(
                            buildJsonObject {
                                put("error", "workspace '$target' rootfs is not installed yet (install it from the workspace page first)")
                            }.toString()
                        )
                    )
                }
                withContext(Dispatchers.IO) {
                    prepareWorkspaceTerminalSession(context, target)
                }
                val launch = buildWorkspaceProotLaunch(context, target)
                HeadlessPty(launch.shellPath, launch.cwd, launch.args, launch.env, killTreeWithSu = false)
            }

            val auditCommand = buildString {
                append(redactSecrets(command, sendValues))
                if (!thenCommand.isNullOrBlank()) {
                    append("  ⇒  ").append(redactSecrets(thenCommand, sendValues))
                }
                if (target != "host") append("   [workspace: ").append(target).append(']')
            }
            val startedAt = System.currentTimeMillis()
            val auditId = shellAuditLogger?.start(
                source = ShellAuditEntity.SOURCE_AI_PTY,
                command = auditCommand,
            )
            val result = try {
                withContext(Dispatchers.IO) {
                    pty.runExpect(command, expects, thenCommand, timeoutMillis)
                }
            } catch (e: Throwable) {
                shellAuditLogger?.finish(auditId ?: "", startedAt, ShellAuditEntity.STATUS_ERROR, null, e.message)
                throw e
            }
            val status = if (result.timedOut) ShellAuditEntity.STATUS_TIMEOUT else ShellAuditEntity.STATUS_DONE
            shellAuditLogger?.finish(
                auditId ?: "", startedAt, status, result.exitCode,
                redactSecrets(result.screen, sendValues),
            )
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("exitCode", result.exitCode)
                        put("timedOut", result.timedOut)
                        put("matchedExpects", result.matchedExpects)
                        put("totalExpects", expects.size)
                        if (result.truncated) put("truncated", true)
                        put("screen", result.screen)
                    }.toString()
                )
            )
        },
    )
}

/** 审计/日志里把 expect.send 的值脱敏(交互密码通常不回显, 这里防的是命令行内嵌) */
private fun redactSecrets(text: String, sends: List<String>): String {
    var out = text
    sends.map { it.trim() }
        .filter { it.length >= 2 }
        .forEach { secret -> out = out.replace(secret, "•••") }
    return out
}
