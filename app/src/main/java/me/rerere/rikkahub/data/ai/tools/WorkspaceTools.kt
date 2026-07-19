package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.DiffMetadata
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import me.rerere.rikkahub.data.ai.tools.local.ShellRisk
import me.rerere.rikkahub.data.ai.tools.local.ShellSafety
import me.rerere.rikkahub.data.db.entity.ShellAuditEntity
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.service.shell.BackgroundShellManager
import me.rerere.rikkahub.service.shell.ShellAuditLogger
import me.rerere.rikkahub.utils.generateUnifiedDiff
import me.rerere.workspace.ShellSessionManager
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceStorageArea
import org.koin.java.KoinJavaComponent.getKoin
import java.io.ByteArrayOutputStream

/** 清理终端 ANSI 转义序列(颜色/光标控制), 避免污染 LLM 上下文 */
private val ANSI_REGEX = Regex("""\x1B\[[0-?]*[ -/]*[@-~]|\x1B\][^\x07]*(\x07|\x1B\\)|\x1B[@-Z\\-_]""")

internal fun String.stripAnsi(): String = replace(ANSI_REGEX, "")

private const val SHELL_TIMEOUT_MAX_SECONDS = 600L
private const val MAX_READ_FILE_BYTES = 8L * 1024 * 1024
private const val MAX_READ_IMAGE_BYTES = 32L * 1024 * 1024

val WorkspaceToolDefaultApprovals: Map<String, Boolean> = mapOf(
    "workspace_read_file" to false,
    "workspace_write_file" to false,
    "workspace_edit_file" to false,
    "workspace_shell" to false,
    "workspace_snapshot" to false,
    "workspace_shell_bg" to false,
    "workspace_shell_task_output" to false,
    "workspace_shell_task_kill" to false,
)

fun resolveWorkspaceToolApproval(name: String, overrides: Map<String, Boolean>): Boolean =
    overrides[name] ?: WorkspaceToolDefaultApprovals[name] ?: false

suspend fun createWorkspaceTools(
    workspaceId: String?,
    workspaceRepository: WorkspaceRepository,
    cwd: String? = null,
    shellSessionManager: ShellSessionManager? = null,
    backgroundShellManager: BackgroundShellManager? = null,
    shellAuditLogger: ShellAuditLogger? = null,
): List<Tool> {
    if (workspaceId.isNullOrBlank()) return emptyList()
    val approvalOverrides = workspaceRepository.getById(workspaceId)?.toolApprovalOverrides().orEmpty()
    fun needsApproval(name: String) = resolveWorkspaceToolApproval(name, approvalOverrides)

    /** shell 类工具按命令风险分级: 只读走用户设置(默认免审批), 写操作默认要审批, 高危在 execute 里直接拒 */
    fun shellApproval(command: String): Boolean = when (ShellSafety.classify(command)) {
        ShellRisk.READ_ONLY -> resolveWorkspaceToolApproval("workspace_shell", approvalOverrides)
        ShellRisk.WRITE -> approvalOverrides["workspace_shell"] ?: true
        ShellRisk.BLOCKED -> false
    }

    val shellCwd = cwd?.removePrefix("/workspace/")?.removePrefix("/workspace")

    return buildList {
        add(createReadFileTool(workspaceId, ::needsApproval, workspaceRepository))
        add(createWriteFileTool(workspaceId, ::needsApproval, workspaceRepository))
        add(createEditFileTool(workspaceId, ::needsApproval, workspaceRepository))
        add(
            createShellTool(
                workspaceId,
                ::shellApproval,
                workspaceRepository,
                shellCwd,
                rootMode = workspaceRepository.isRootMode(),
                shellSessionManager = shellSessionManager,
                shellAuditLogger = shellAuditLogger,
            )
        )
        add(createSnapshotTool(workspaceId, ::needsApproval, workspaceRepository))
        if (backgroundShellManager != null && shellAuditLogger != null) {
            add(createBackgroundStartTool(workspaceId, ::shellApproval, backgroundShellManager, shellAuditLogger))
            add(createBackgroundOutputTool(backgroundShellManager))
            add(createBackgroundKillTool(backgroundShellManager))
        }
    }
}

private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg")

private fun String.isImagePath(): Boolean =
    substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

private fun createReadFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_read_file",
    description = """
        Read a file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use /workspace for the workspace files area.
        Supports UTF-8 text files and image files (png, jpg, jpeg, gif, webp, bmp).
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
            },
            required = listOf("path"),
        )
    },
    needsApproval = { needsApproval("workspace_read_file") },
    execute = {
        val path = it.jsonObject.absolutePath("path")
        if (path.isImagePath()) {
            workspaceRepository.readImageInRootfs(workspaceId, path)
        } else {
            val text = workspaceRepository.readTextInRootfs(workspaceId, path)
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("path", path)
                        put("text", text)
                    }.toString()
                )
            )
        }
    },
)

private fun createWriteFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_write_file",
    description = """
        Write a UTF-8 text file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use /workspace for the workspace files area.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "UTF-8 text content to write")
                })
                put("overwrite", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to overwrite an existing file. Defaults to true.")
                })
            },
            required = listOf("path", "text"),
        )
    },
    needsApproval = { needsApproval("workspace_write_file") || it.pathOutsideWritableRoots("path") },
    execute = {
        val params = it.jsonObject
        val path = params.absolutePath("path")
        val text = params.string("text") ?: error("text is required")
        val overwrite = params["overwrite"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
        val entry = workspaceRepository.writeTextInRootfs(workspaceId, path, text, overwrite)
        listOf(UIMessagePart.Text(entry.toJson().toString()))
    },
)

private fun createEditFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_edit_file",
    description = """
        Edit a UTF-8 text file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use /workspace for the workspace files area.
        Provide old_text and new_text. By default old_text must occur exactly once; set replace_all=true to replace every occurrence.
        If no exact match is found, whitespace-tolerant line matching is attempted automatically.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                put("old_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Exact text to replace")
                })
                put("new_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Replacement text")
                })
                put("replace_all", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to replace every occurrence. Defaults to false.")
                })
            },
            required = listOf("path", "old_text", "new_text"),
        )
    },
    needsApproval = { needsApproval("workspace_edit_file") || it.pathOutsideWritableRoots("path") },
    execute = {
        val params = it.jsonObject
        val path = params.absolutePath("path")
        val oldText = params.string("old_text") ?: error("old_text is required")
        val newText = params.string("new_text") ?: error("new_text is required")
        val replaceAll = params["replace_all"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        require(oldText.isNotEmpty()) { "old_text must not be empty" }

        val original = workspaceRepository.readTextInRootfs(workspaceId, path)
        // 逐级尝试 exact -> line_trimmed -> block_anchor 替换器, 见 TextReplacers.kt
        val result = try {
            replaceText(original, oldText, newText, replaceAll)
        } catch (e: IllegalArgumentException) {
            error("${e.message} (path: $path)")
        }
        val entry = workspaceRepository.writeTextInRootfs(workspaceId, path, result.updated, overwrite = true)
        val diff = generateUnifiedDiff(original, result.updated, entry.path)
        listOf(
            UIMessagePart.Text(
                text = buildJsonObject {
                    put("path", entry.path)
                    put("replacements", result.replacements)
                    if (result.strategy != ExactReplacer.name) put("matchStrategy", result.strategy)
                    put("sizeBytes", entry.sizeBytes)
                    put("updatedAt", entry.updatedAt)
                }.toString(),
                // diff 存入 metadata 供 UI 渲染 diff view, 不会随工具结果发送给 API
                metadata = diff?.let { d -> DiffMetadata(diff = d).toMetadata() },
            )
        )
    },
)

private fun createShellTool(
    workspaceId: String,
    shellApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    defaultCwd: String? = null,
    rootMode: Boolean = false,
    shellSessionManager: ShellSessionManager? = null,
    shellAuditLogger: ShellAuditLogger? = null,
) = Tool(
    name = "workspace_shell",
    description = buildString {
        if (rootMode) {
            append("Run a shell command on the Android HOST system with ROOT privileges (via su). ")
            append("Commands do NOT run inside the workspace Rootfs container; they run on the real device as root. ")
            append("The workspace files area is NOT mounted at /workspace; use absolute paths under the app's files directory instead. ")
            append("You can use system tools like pm, am, input, screencap, settings. ")
        } else {
            append("Run a shell command in the assistant's bound workspace Rootfs. The workspace files area is mounted at /workspace. ")
        }
        if (shellSessionManager != null) {
            append("Commands run in a PERSISTENT shell session: cd, exported variables and background processes (&) carry over between calls. ")
            append("Omit cwd to stay in the current directory (the response includes the current cwd). Do not run interactive commands that read stdin (e.g. read, top without -n). ")
        } else {
            append("Use cwd for a path relative to the workspace files root. ")
        }
        if (!defaultCwd.isNullOrBlank()) {
            append("Initial directory: '$defaultCwd'. ")
        }
        if (!rootMode) {
            append("Requires Rootfs to be installed and ready. ")
        }
        append("Dangerous commands may be blocked by a safety guard.")
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Shell command to run")
                })
                put("cwd", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Working directory relative to the workspace files root. Omit to keep the session's current directory."
                    )
                })
                put("timeout", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "Command timeout in seconds. Defaults to 30, max $SHELL_TIMEOUT_MAX_SECONDS."
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
    needsApproval = { json ->
        val command = json.jsonObject["command"]?.jsonPrimitive?.contentOrNull.orEmpty()
        shellApproval(command)
    },
    execute = {
        val params = it.jsonObject
        val command = params.string("command") ?: error("command is required")
        val rawCwd = params.string("cwd")
            ?.removePrefix("/workspace/")?.removePrefix("/workspace")
        val fresh = params["fresh"]?.jsonPrimitive?.booleanOrNull == true
        val timeoutMillis = params.string("timeout")?.toLongOrNull()
            ?.coerceIn(1L, SHELL_TIMEOUT_MAX_SECONDS)
            ?.times(1_000L)
            ?: WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS

        // 安全闸门: 高危命令直接拒绝执行
        ShellSafety.blockReason(command)?.let { reason ->
            shellAuditLogger?.logCompleted(
                source = ShellAuditEntity.SOURCE_AI_WORKSPACE,
                command = command,
                status = ShellAuditEntity.STATUS_BLOCKED,
                cwd = rawCwd.orEmpty(),
                workspaceId = workspaceId,
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

        val useSession = !fresh && shellSessionManager != null
        // 持久模式: 仅当 AI 显式传 cwd 或会话刚创建时 cd, 否则保持会话当前目录
        val effectiveCwd = when {
            !useSession -> rawCwd ?: defaultCwd.orEmpty()
            rawCwd != null -> rawCwd
            shellSessionManager?.hasSession(workspaceId) == true -> null
            else -> defaultCwd
        }
        val startedAt = System.currentTimeMillis()
        val auditId = shellAuditLogger?.start(
            source = ShellAuditEntity.SOURCE_AI_WORKSPACE,
            command = command,
            cwd = effectiveCwd.orEmpty(),
            workspaceId = workspaceId,
        )
        val result = try {
            if (useSession) {
                runInterruptible(Dispatchers.IO) {
                    shellSessionManager!!.exec(workspaceId, command, effectiveCwd, timeoutMillis)
                }
            } else {
                workspaceRepository.executeCommand(workspaceId, command, effectiveCwd.orEmpty(), timeoutMillis)
            }
        } catch (e: Throwable) {
            shellAuditLogger?.finish(
                auditId ?: "", startedAt,
                ShellAuditEntity.STATUS_ERROR, null, e.message,
            )
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
                    put("stdout", result.stdout.stripAnsi())
                    put("stderr", result.stderr.stripAnsi())
                    put("timedOut", result.timedOut)
                    if (result.truncated) put("truncated", true)
                    if (useSession) put("cwd", shellSessionManager?.currentCwd(workspaceId).orEmpty())
                }.toString()
            )
        )
    },
)

private fun kotlinx.serialization.json.JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

// ---- 快照工具: AI 改文件前的保险丝 ----

private fun createSnapshotTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_snapshot",
    description = """
        Manage file snapshots of the workspace files area (pure file backup, no git required).
        Use 'create' BEFORE making risky/bulk changes so the user can roll back.
        'restore' replaces the whole files area with a snapshot (a pre-restore safety snapshot is taken automatically).
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("description", "One of: create, list, restore, delete")
                })
                put("label", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional label for 'create'")
                })
                put("name", buildJsonObject {
                    put("type", "string")
                    put("description", "Snapshot name for 'restore'/'delete' (from list)")
                })
            },
            required = listOf("action"),
        )
    },
    needsApproval = { json ->
        val action = json.jsonObject["action"]?.jsonPrimitive?.contentOrNull.orEmpty()
        when (action) {
            "restore", "delete" -> true // 破坏性操作必须审批
            else -> needsApproval("workspace_snapshot")
        }
    },
    execute = {
        val params = it.jsonObject
        val action = params.string("action") ?: error("action is required")
        val result = when (action) {
            "create" -> {
                val snap = workspaceRepository.createSnapshot(workspaceId, params.string("label"))
                buildJsonObject {
                    put("ok", true)
                    put("name", snap.name)
                    put("sizeBytes", snap.sizeBytes)
                    put("fileCount", snap.fileCount)
                }
            }
            "list" -> {
                val snaps = workspaceRepository.listSnapshots(workspaceId)
                buildJsonObject {
                    put("count", snaps.size)
                    put("snapshots", buildJsonArray {
                        snaps.forEach { s ->
                            add(buildJsonObject {
                                put("name", s.name)
                                put("createdAt", s.createdAt)
                                put("sizeBytes", s.sizeBytes)
                                put("fileCount", s.fileCount)
                            })
                        }
                    })
                }
            }
            "restore" -> {
                val name = params.string("name") ?: error("name is required for restore")
                val safety = workspaceRepository.restoreSnapshot(workspaceId, name)
                buildJsonObject {
                    put("ok", true)
                    put("restored", name)
                    put("safetySnapshot", safety.name)
                }
            }
            "delete" -> {
                val name = params.string("name") ?: error("name is required for delete")
                buildJsonObject { put("ok", workspaceRepository.deleteSnapshot(workspaceId, name)) }
            }
            else -> error("Unknown action: $action (expected create/list/restore/delete)")
        }
        listOf(UIMessagePart.Text(result.toString()))
    },
)

// ---- 后台任务工具: 长任务挂后台, 轮询收割 ----

private fun createBackgroundStartTool(
    workspaceId: String,
    shellApproval: (String) -> Boolean,
    backgroundShellManager: BackgroundShellManager,
    shellAuditLogger: ShellAuditLogger,
) = Tool(
    name = "workspace_shell_bg",
    description = """
        Start a shell command in the BACKGROUND and return immediately with a task id.
        Use for long-running jobs: builds, downloads, servers, watchers.
        Poll progress with workspace_shell_task_output, stop with workspace_shell_task_kill.
        For quick commands prefer workspace_shell.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Shell command to run in background")
                })
                put("cwd", buildJsonObject {
                    put("type", "string")
                    put("description", "Working directory relative to the workspace files root")
                })
            },
            required = listOf("command"),
        )
    },
    needsApproval = { json ->
        val command = json.jsonObject["command"]?.jsonPrimitive?.contentOrNull.orEmpty()
        shellApproval(command)
    },
    execute = {
        val params = it.jsonObject
        val command = params.string("command") ?: error("command is required")
        val cwd = params.string("cwd")
            ?.removePrefix("/workspace/")?.removePrefix("/workspace")
            .orEmpty()

        ShellSafety.blockReason(command)?.let { reason ->
            shellAuditLogger.logCompleted(
                source = ShellAuditEntity.SOURCE_AI_BACKGROUND,
                command = command,
                status = ShellAuditEntity.STATUS_BLOCKED,
                cwd = cwd,
                workspaceId = workspaceId,
                outputPreview = "Blocked: $reason",
            )
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("blocked", true)
                        put("reason", reason)
                    }.toString()
                )
            )
        }

        val task = backgroundShellManager.start(workspaceId, workspaceId, command, cwd)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("taskId", task.id)
                    put("status", task.status)
                    put("logFile", task.logFile)
                    put("hint", "Poll with workspace_shell_task_output(task_id=\"${task.id}\")")
                }.toString()
            )
        )
    },
)

private fun createBackgroundOutputTool(
    backgroundShellManager: BackgroundShellManager,
) = Tool(
    name = "workspace_shell_task_output",
    description = "Read the latest output (tail) and status of a background shell task.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("task_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Task id returned by workspace_shell_bg")
                })
                put("tail_lines", buildJsonObject {
                    put("type", "integer")
                    put("description", "Number of lines from the end of the log. Defaults to 200.")
                })
            },
            required = listOf("task_id"),
        )
    },
    needsApproval = { false },
    execute = {
        val params = it.jsonObject
        val taskId = params.string("task_id") ?: error("task_id is required")
        val tailLines = params.string("tail_lines")?.toIntOrNull()?.coerceIn(1, 2000) ?: 200
        val task = backgroundShellManager.get(taskId) ?: error("Task not found: $taskId")
        val output = runCatching { backgroundShellManager.output(taskId, tailLines) }
            .getOrElse { e -> "failed to read output: ${e.message}" }
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("taskId", task.id)
                    put("status", task.status)
                    task.exitCode?.let { code -> put("exitCode", code) }
                    put("output", output.stripAnsi())
                }.toString()
            )
        )
    },
)

private fun createBackgroundKillTool(
    backgroundShellManager: BackgroundShellManager,
) = Tool(
    name = "workspace_shell_task_kill",
    description = "Kill a running background shell task by id.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("task_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Task id returned by workspace_shell_bg")
                })
            },
            required = listOf("task_id"),
        )
    },
    needsApproval = { false },
    execute = {
        val params = it.jsonObject
        val taskId = params.string("task_id") ?: error("task_id is required")
        val killed = backgroundShellManager.kill(taskId)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("taskId", taskId)
                    put("killed", killed)
                }.toString()
            )
        )
    },
)

private suspend fun WorkspaceRepository.readTextInRootfs(
    workspaceId: String,
    path: String,
): String {
    val (area, relativePath) = rootfsPathToAreaAndRelative(path)
    val size = fileSize(workspaceId, area, relativePath)
    require(size <= MAX_READ_FILE_BYTES) {
        "File is too large to read: $path (${size / 1024 / 1024}MB, max ${MAX_READ_FILE_BYTES / 1024 / 1024}MB). Use shell commands like head, tail, or grep to read parts of it."
    }
    val buffer = ByteArrayOutputStream(size.toInt())
    exportFile(workspaceId, area, relativePath, buffer)
    return buffer.toString(Charsets.UTF_8.name())
}

private fun rootfsPathToAreaAndRelative(path: String): Pair<WorkspaceStorageArea, String> {
    val trimmed = path.trimEnd('/')
    return if (trimmed == "/workspace" || trimmed.startsWith("/workspace/")) {
        WorkspaceStorageArea.FILES to trimmed.removePrefix("/workspace").trimStart('/')
    } else {
        WorkspaceStorageArea.LINUX to trimmed.trimStart('/')
    }
}

private suspend fun WorkspaceRepository.readImageInRootfs(
    workspaceId: String,
    path: String,
): List<UIMessagePart> {
    val (area, relativePath) = rootfsPathToAreaAndRelative(path)
    // 图片全量进内存 + base64 渲染，超大图会 OOM —— 限制 32MB
    val size = fileSize(workspaceId, area, relativePath)
    require(size <= MAX_READ_IMAGE_BYTES) {
        "Image is too large to read inline: $path (${size / 1024 / 1024}MB, max ${MAX_READ_IMAGE_BYTES / 1024 / 1024}MB). Downscale it first (e.g. with ImageMagick) or inspect via shell."
    }
    val buffer = ByteArrayOutputStream(size.toInt().coerceAtLeast(8192))
    exportFile(workspaceId, area, relativePath, buffer)
    val bytes = buffer.toByteArray()

    val filesManager = getKoin().get<FilesManager>()
    val uris = filesManager.createChatFilesByByteArrays(listOf(bytes))
    return listOf(
        UIMessagePart.Image(url = uris.first().toString()),
        UIMessagePart.Text(
            buildJsonObject {
                put("path", path)
                put("description", "Image file read successfully")
            }.toString()
        ),
    )
}

private suspend fun WorkspaceRepository.writeTextInRootfs(
    workspaceId: String,
    path: String,
    text: String,
    overwrite: Boolean,
): WorkspaceFileEntry {
    // 与读取（fileSize/exportFile）同走文件 IO：
    // root 模式下 rootfs shell 在宿主机真 root 环境执行，shell 重定向会写到
    // 宿主机真实路径而不是 workspace 目录，写操作必须避开 shell。
    val (area, relativePath) = rootfsPathToAreaAndRelative(path)
    val entry = writeTextInArea(workspaceId, area, relativePath, text, overwrite)
    // entry.path 是相对存储区的路径，统一对外呈现 rootfs 绝对路径
    return entry.copy(path = path, name = path.substringAfterLast('/'))
}

private fun kotlinx.serialization.json.JsonObject.absolutePath(name: String): String {
    val path = string(name)?.replace('\\', '/')?.trim() ?: error("$name is required")
    require(path.isNotBlank()) { "$name is required" }
    require(path.startsWith("/")) { "$name must be an absolute path inside Rootfs" }
    require(!path.contains('\u0000')) { "$name contains invalid character" }
    return path
}

// 免强制审批的可写安全区: 工作区文件目录, 以及临时目录 /tmp
private val WRITABLE_ROOT_PREFIXES = listOf("/workspace", "/tmp")

private fun kotlinx.serialization.json.JsonElement.pathOutsideWritableRoots(name: String): Boolean =
    runCatching {
        jsonObject.absolutePath(name).isOutsideWritableRoots()
    }.getOrDefault(true)

private fun String.isOutsideWritableRoots(): Boolean {
    val normalized = trimEnd('/').ifBlank { "/" }
    return WRITABLE_ROOT_PREFIXES.none { prefix ->
        normalized == prefix || normalized.startsWith("$prefix/")
    }
}

private fun JsonObjectBuilder.putPathProperty(required: Boolean) {
    put("path", buildJsonObject {
        put("type", "string")
        put(
            "description",
            if (required) {
                "Absolute path inside Rootfs. Use /workspace for the workspace files area."
            } else {
                "Optional absolute path inside Rootfs. Use /workspace for the workspace files area."
            }
        )
    })
}

private fun WorkspaceFileEntry.toJson() = buildJsonObject {
    put("path", path)
    put("name", name)
    put("isDirectory", isDirectory)
    put("sizeBytes", sizeBytes)
    put("updatedAt", updatedAt)
}
