package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
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
 * pty_session: 持久 PTY 会话组工具.
 *
 * 与 pty_exec 互补:
 * - pty_exec: 一次性 expect 式交互, 整个调用完成即关闭
 * - pty_session: 打开后保持, 多次 send/read 直到 close
 *
 * 典型场景: ssh 密码登录一次后持续在远端操作、REPL 长会话、交互式安装向导.
 *
 * 审批规则:
 * - open/send/close: 需要审批 (有副作用)
 * - read/list: 免审批 (纯观察)
 */
internal fun buildPtySessionTool(
    context: Context,
    ptySessionManager: PtySessionManager,
    shellAuditLogger: ShellAuditLogger? = null,
): Tool {
    return Tool(
        name = "pty_session",
        description = """
            Manage persistent PTY sessions for multi-turn interactive terminal work.
            Actions: open (create session), send (type text + optional expect), read (get screen text), close (destroy session), list (show active sessions).
            Use this for: ssh password login then run many commands in the same session, REPLs (python/node/mysql), interactive installers with multiple prompts.
            Do NOT use for: one-shot non-interactive commands (use workspace_shell / root_shell instead).
            Do NOT drive full-screen TUI apps (vim/nano/htop/less) — edit files non-interactively instead.
            The `target` for open: "host" (default) = Android host ROOT shell via su; or a workspace name = that workspace's Ubuntu rootfs.
            After open, the returned `id` is used for send/read/close.
            Send with optional `expect` (regex) — waits for it to appear in the screen before returning.
            Each session auto-expires after 10 minutes of inactivity.
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
                        put("description", "Session id (from open). Required for send/read/close.")
                    })
                    put("target", buildJsonObject {
                        put("type", "string")
                        put("description", "For open: \"host\" (default) or a workspace name")
                    })
                    put("name", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional mnemonic name for the session (e.g. \"vps\", \"repl\")")
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
        // open/send/close 有副作用需审批; read/list 纯观察免审批
        needsApproval = { params ->
            val action = params.jsonObject["action"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            action in setOf("open", "send", "close")
        },
        execute = {
            val params = it.jsonObject
            val action = params["action"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                .lowercase()

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

private suspend fun executeOpen(
    params: kotlinx.serialization.json.JsonObject,
    manager: PtySessionManager,
    auditLogger: ShellAuditLogger?,
): List<UIMessagePart> {
    val target = params["target"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifEmpty { "host" }
    val name = params["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    val startedAt = System.currentTimeMillis()
    val auditId = auditLogger?.start(
        source = ShellAuditEntity.SOURCE_AI_PTY,
        command = "pty_session open (target=$target, name=$name)",
    )
    return try {
        val id = manager.open(target, name)
        val entry = manager.get(id)
        val screenTail = entry?.pty?.readScreen(10) ?: ""
        auditLogger?.finish(
            auditId ?: "", startedAt, ShellAuditEntity.STATUS_DONE, 0,
            "session opened: $id",
        )
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("id", id)
                    put("target", target)
                    put("name", name.ifEmpty { target })
                    put("screenTail", screenTail.trim())
                }.toString()
            )
        )
    } catch (e: Throwable) {
        auditLogger?.finish(auditId ?: "", startedAt, ShellAuditEntity.STATUS_ERROR, null, e.message)
        errorResult("failed to open session: ${e.message}")
    }
}

private suspend fun executeSend(
    params: kotlinx.serialization.json.JsonObject,
    manager: PtySessionManager,
    auditLogger: ShellAuditLogger?,
): List<UIMessagePart> {
    val id = params["id"]?.jsonPrimitive?.contentOrNull?.trim()
        ?: return errorResult("id is required")
    val text = params["text"]?.jsonPrimitive?.contentOrNull
        ?: return errorResult("text is required")
    val expect = params["expect"]?.jsonPrimitive?.contentOrNull?.trim()
    val timeoutS = params["timeout"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        ?.coerceIn(1L, PTY_SESSION_TIMEOUT_MAX_S)
        ?: PTY_SESSION_TIMEOUT_DEFAULT_S
    val rows = params["rows"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        ?.coerceIn(0, PTY_SESSION_MAX_ROWS)
        ?: 60

    // 安全闸门: 发送内容过高危检测
    ShellSafety.blockReason(text)?.let { reason ->
        auditLogger?.logCompleted(
            source = ShellAuditEntity.SOURCE_AI_PTY,
            command = "pty_session send (id=$id): $text",
            status = ShellAuditEntity.STATUS_BLOCKED,
            outputPreview = "Blocked: $reason",
        )
        return listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("blocked", true)
                    put("reason", reason)
                }.toString()
            )
        )
    }

    val entry = manager.get(id)
        ?: return errorResult("session '$id' not found (expired or already closed)")
    val pty = entry.pty
    val startedAt = System.currentTimeMillis()
    val auditId = auditLogger?.start(
        source = ShellAuditEntity.SOURCE_AI_PTY,
        command = "pty_session send (id=$id): $text",
    )

    return try {
        pty.send(text)
        var matched = false
        if (!expect.isNullOrBlank()) {
            val pattern = try {
                Pattern.compile(expect)
            } catch (e: Exception) {
                auditLogger?.finish(auditId ?: "", startedAt, ShellAuditEntity.STATUS_ERROR, null, "invalid regex: ${e.message}")
                return errorResult("invalid expect regex: ${e.message}")
            }
            val result = pty.waitPattern(pattern, timeoutS * 1000)
            matched = result != null
        }
        val screenText = pty.readScreen(if (rows == 0) Int.MAX_VALUE else rows)
        val status = if (expect.isNullOrBlank() || matched) ShellAuditEntity.STATUS_DONE else ShellAuditEntity.STATUS_TIMEOUT
        auditLogger?.finish(auditId ?: "", startedAt, status, null, screenText.takeLast(2000))
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("matched", matched)
                    put("screen", screenText.trimEnd())
                }.toString()
            )
        )
    } catch (e: Throwable) {
        auditLogger?.finish(auditId ?: "", startedAt, ShellAuditEntity.STATUS_ERROR, null, e.message)
        errorResult("send failed: ${e.message}")
    }
}

private fun executeRead(
    params: kotlinx.serialization.json.JsonObject,
    manager: PtySessionManager,
): List<UIMessagePart> {
    val id = params["id"]?.jsonPrimitive?.contentOrNull?.trim()
        ?: return errorResult("id is required")
    val rows = params["rows"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        ?.coerceIn(0, PTY_SESSION_MAX_ROWS)
        ?: 60

    val entry = manager.get(id)
        ?: return errorResult("session '$id' not found (expired or already closed)")
    val screenText = entry.pty.readScreen(if (rows == 0) Int.MAX_VALUE else rows)
    return listOf(
        UIMessagePart.Text(
            buildJsonObject {
                put("screen", screenText.trimEnd())
            }.toString()
        )
    )
}

private suspend fun executeClose(
    params: kotlinx.serialization.json.JsonObject,
    manager: PtySessionManager,
    auditLogger: ShellAuditLogger?,
): List<UIMessagePart> {
    val id = params["id"]?.jsonPrimitive?.contentOrNull?.trim()
        ?: return errorResult("id is required")
    val startedAt = System.currentTimeMillis()
    val auditId = auditLogger?.start(
        source = ShellAuditEntity.SOURCE_AI_PTY,
        command = "pty_session close (id=$id)",
    )
    val entry = manager.close(id)
    val screenTail = entry?.pty?.readScreen(10) ?: "(session already closed)"
    auditLogger?.finish(auditId ?: "", startedAt, ShellAuditEntity.STATUS_DONE, null, "session closed: $id")
    return listOf(
        UIMessagePart.Text(
            buildJsonObject {
                put("closed", id)
                put("screenTail", screenTail.trim())
            }.toString()
        )
    )
}

private fun executeList(
    manager: PtySessionManager,
): List<UIMessagePart> {
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
    return listOf(
        UIMessagePart.Text(
            kotlinx.serialization.json.JsonArray(sessions).toString()
        )
    )
}

private fun errorResult(message: String): List<UIMessagePart> =
    listOf(
        UIMessagePart.Text(
            buildJsonObject {
                put("error", message)
            }.toString()
        )
    )
