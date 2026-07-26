package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.entity.ShellAuditEntity
import me.rerere.rikkahub.service.shell.PtySessionManager
import me.rerere.rikkahub.service.shell.ShellAuditLogger
import java.util.regex.Pattern

private const val PTY_SESSION_TIMEOUT_DEFAULT_S = 30L
private const val PTY_SESSION_TIMEOUT_MAX_S = 300L
private const val PTY_SESSION_MAX_ROWS = 500

/**
 * pty_session: 持久 PTY 会话组 (name 为主键, 同名自动复用).
 *
 * 与 pty_exec 互补:
 * - pty_exec: 一次性 expect 式交互, 完成即关闭
 * - pty_session: 打开后保持, 多次 send/read, name 复用.
 *   典型场景: ssh 密码登录后持续在远端操作, REPL, 交互安装.
 *
 * name 是持久标识: open(name="vps") 后, 后续 send/read/close 也用 name="vps".
 * 即使对话很长 AI 忘了 id, 重新 open 同名会话会直接复用已有连接.
 */
internal fun buildPtySessionTool(
    context: Context,
    ptySessionManager: PtySessionManager,
    shellAuditLogger: ShellAuditLogger? = null,
    // 用户全局审批覆盖: 返回 false = 一律自动放行; null = 默认(open/send/close 需审批)
    approvalOverride: () -> Boolean? = { null },
): Tool {
    return Tool(
        name = "pty_session",
        description = """
Manage persistent PTY sessions for multi-turn interactive terminal work. Actions: open, send, read, close, list.
Use for: ssh login then run many commands in same session, REPLs (python/node/mysql), interactive installers.
Do NOT use for: one-shot non-interactive commands (use workspace_shell/root_shell). Do NOT drive TUI apps (vim/htop).
name is the durable key: open(name="vps",target="host") once, then send/read/close all use the same name.
If you forget the session id, just call open with the same name again — it reuses the existing connection.
Send with optional expect (regex) to wait for pattern. Each session auto-expires after 10min idle.
""".trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put("description", "One of: open, send, read, close, list")
                    })
                    put("id", buildJsonObject {
                        put("type", "string")
                        put("description", "Session id or name. Required for send/read/close. name is preferred (durable across turns).")
                    })
                    put("target", buildJsonObject {
                        put("type", "string")
                        put("description", "For open: \"host\" (default) = Android host ROOT shell via su; or a workspace name = that workspace's Ubuntu rootfs")
                    })
                    put("name", buildJsonObject {
                        put("type", "string")
                        put("description", "Mnemoic name for the session (e.g. \"vps\", \"repl\"). This is the durable key: reopen with same name reuses the session.")
                    })
                    put("text", buildJsonObject {
                        put("type", "string")
                        put("description", "Text to send (newline appended automatically). Required for send.")
                    })
                    put("expect", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional regex — after sending, wait for this pattern in screen output")
                    })
                    put("timeout", buildJsonObject {
                        put("type", "integer")
                        put("description", "Timeout in seconds for send+expect. Default $PTY_SESSION_TIMEOUT_DEFAULT_S, max $PTY_SESSION_TIMEOUT_MAX_S")
                    })
                    put("rows", buildJsonObject {
                        put("type", "integer")
                        put("description", "Number of tail rows to return (0 = full transcript, max $PTY_SESSION_MAX_ROWS). Default 60.")
                    })
                },
                required = listOf("action"),
            )
        },
        needsApproval = { params ->
            approvalOverride() ?: run {
                val action = params.jsonObject["action"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                action in setOf("open", "send", "close")
            }
        },
        execute = {
            val params = it.jsonObject
            val action = params["action"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().lowercase()

            when (action) {
                "open" -> executeOpen(params, ptySessionManager, shellAuditLogger)
                "send" -> executeSend(params, ptySessionManager, shellAuditLogger)
                "read" -> executeRead(params, ptySessionManager)
                "close" -> executeClose(params, ptySessionManager, shellAuditLogger)
                "list" -> executeList(ptySessionManager)
                else -> errorResult("unknown action: '$action'")
            }
        },
    )
}

// ---- resolve: id or name -> entry ----

private fun resolveSession(
    params: kotlinx.serialization.json.JsonObject,
    manager: PtySessionManager,
): PtySessionManager.PtySessionEntry {
    val idOrName = params["id"]?.jsonPrimitive?.contentOrNull?.trim()
        ?: error("id is required")
    return manager.get(idOrName)
        ?: error("session '$idOrName' not found (expired or closed); use list to see active sessions")
}

// ---- open ----

private suspend fun executeOpen(
    params: kotlinx.serialization.json.JsonObject,
    manager: PtySessionManager,
    auditLogger: ShellAuditLogger?,
): List<UIMessagePart> {
    val target = params["target"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifEmpty { "host" }
    val name = params["name"]?.jsonPrimitive?.contentOrNull?.trim()
        ?: return errorResult("name is required for open")
    val startedAt = System.currentTimeMillis()
    val auditId = auditLogger?.start(
        source = ShellAuditEntity.SOURCE_AI_PTY,
        command = "pty_session open (target=$target, name=$name)",
    )
    return try {
        val entry = manager.open(target, name)
        val screenTail = entry.pty.readScreen(10).trim().takeLast(1000)
        auditLogger?.finish(auditId ?: "", startedAt, ShellAuditEntity.STATUS_DONE, 0,
            "session: ${entry.id} (name=${entry.name})")
        listOf(UIMessagePart.Text(buildJsonObject {
            put("id", entry.id)
            put("name", entry.name)
            put("target", entry.target)
            put("screenTail", screenTail)
        }.toString()))
    } catch (e: Throwable) {
        auditLogger?.finish(auditId ?: "", startedAt, ShellAuditEntity.STATUS_ERROR, null, e.message)
        errorResult("failed to open session: ${e.message}")
    }
}

// ---- send ----

private suspend fun executeSend(
    params: kotlinx.serialization.json.JsonObject,
    manager: PtySessionManager,
    auditLogger: ShellAuditLogger?,
): List<UIMessagePart> {
    val entry = try { resolveSession(params, manager) } catch (e: Throwable) { return errorResult(e.message ?: "session not found") }
    val text = params["text"]?.jsonPrimitive?.contentOrNull
        ?: return errorResult("text is required")
    val expect = params["expect"]?.jsonPrimitive?.contentOrNull?.trim()
    val timeoutS = params["timeout"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        ?.coerceIn(1L, PTY_SESSION_TIMEOUT_MAX_S) ?: PTY_SESSION_TIMEOUT_DEFAULT_S
    val rows = params["rows"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        ?.coerceIn(0, PTY_SESSION_MAX_ROWS) ?: 60

    ShellSafety.blockReason(text)?.let { reason ->
        auditLogger?.logCompleted(source = ShellAuditEntity.SOURCE_AI_PTY,
            command = "pty_session send (${entry.id}): $text", status = ShellAuditEntity.STATUS_BLOCKED,
            outputPreview = "Blocked: $reason")
        return listOf(UIMessagePart.Text(buildJsonObject {
            put("blocked", true); put("reason", reason)
        }.toString()))
    }

    val pty = entry.pty
    val startedAt = System.currentTimeMillis()
    val auditId = auditLogger?.start(source = ShellAuditEntity.SOURCE_AI_PTY,
        command = "pty_session send (${entry.id}): $text")

    return try {
        pty.send(text)
        var matched = false
        if (!expect.isNullOrBlank()) {
            val pattern = try { Pattern.compile(expect) }
                catch (e: Exception) {
                    auditLogger?.finish(auditId ?: "", startedAt, ShellAuditEntity.STATUS_ERROR, null, "invalid regex: ${e.message}")
                    return errorResult("invalid expect regex: ${e.message}")
                }
            matched = pty.waitPattern(pattern, timeoutS * 1000) != null
        }
        val screenText = pty.readScreen(if (rows == 0) Int.MAX_VALUE else rows)
        val status = if (expect.isNullOrBlank() || matched) ShellAuditEntity.STATUS_DONE else ShellAuditEntity.STATUS_TIMEOUT
        auditLogger?.finish(auditId ?: "", startedAt, status, null, screenText.takeLast(2000))
        listOf(UIMessagePart.Text(buildJsonObject {
            put("matched", matched); put("screen", screenText.trimEnd())
        }.toString()))
    } catch (e: Throwable) {
        auditLogger?.finish(auditId ?: "", startedAt, ShellAuditEntity.STATUS_ERROR, null, e.message)
        errorResult("send failed: ${e.message}")
    }
}

// ---- read ----

private fun executeRead(
    params: kotlinx.serialization.json.JsonObject,
    manager: PtySessionManager,
): List<UIMessagePart> {
    val entry = try { resolveSession(params, manager) } catch (e: Throwable) { return errorResult(e.message ?: "session not found") }
    val rows = params["rows"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        ?.coerceIn(0, PTY_SESSION_MAX_ROWS) ?: 60
    val screenText = entry.pty.readScreen(if (rows == 0) Int.MAX_VALUE else rows)
    return listOf(UIMessagePart.Text(buildJsonObject {
        put("screen", screenText.trimEnd())
    }.toString()))
}

// ---- close ----

private suspend fun executeClose(
    params: kotlinx.serialization.json.JsonObject,
    manager: PtySessionManager,
    auditLogger: ShellAuditLogger?,
): List<UIMessagePart> {
    val idOrName = params["id"]?.jsonPrimitive?.contentOrNull?.trim()
        ?: return errorResult("id is required")
    val startedAt = System.currentTimeMillis()
    val auditId = auditLogger?.start(source = ShellAuditEntity.SOURCE_AI_PTY,
        command = "pty_session close ($idOrName)")
    val entry = manager.close(idOrName)
    val screenTail = entry?.pty?.readScreen(10)?.trim()?.takeLast(500) ?: "(already closed)"
    auditLogger?.finish(auditId ?: "", startedAt, ShellAuditEntity.STATUS_DONE, null, "closed: $idOrName")
    return listOf(UIMessagePart.Text(buildJsonObject {
        put("closed", idOrName); put("screenTail", screenTail)
    }.toString()))
}

// ---- list ----

private fun executeList(manager: PtySessionManager): List<UIMessagePart> {
    val now = System.currentTimeMillis()
    val sessions = manager.list().map { entry ->
        buildJsonObject {
            put("id", entry.id)
            put("name", entry.name)
            put("target", entry.target)
            put("idleSeconds", (now - entry.lastUsedAt) / 1000)
            put("screenTail", entry.pty.readScreen(1).trimEnd().takeLast(200))
        }
    }
    return listOf(UIMessagePart.Text(JsonArray(sessions).toString()))
}

private fun errorResult(message: String): List<UIMessagePart> =
    listOf(UIMessagePart.Text(buildJsonObject {
        put("error", message)
    }.toString()))
