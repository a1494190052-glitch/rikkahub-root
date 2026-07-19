package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.entity.ScheduledTaskEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.service.scheduler.ScheduledTaskRepository
import me.rerere.rikkahub.service.scheduler.TaskScheduler
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.uuid.Uuid

/**
 * 定时任务工具：让 AI 通过对话创建/管理"主动消息"调度
 * （"每天早 8 点叫我起床并讲个笑话" → create_schedule）
 */
private fun typeDesc(type: String): String = when (type) {
    ScheduledTaskEntity.TYPE_ONCE -> "一次性"
    ScheduledTaskEntity.TYPE_DAILY -> "每天"
    ScheduledTaskEntity.TYPE_WEEKLY -> "每周"
    ScheduledTaskEntity.TYPE_INTERVAL -> "间隔"
    else -> type
}

private fun formatTask(task: ScheduledTaskEntity): String = buildString {
    append("- [${task.id.take(8)}] 「${task.title}」（${typeDesc(task.type)}")
    when (task.type) {
        ScheduledTaskEntity.TYPE_DAILY -> append(" %02d:%02d".format(task.timeMinutes / 60, task.timeMinutes % 60))
        ScheduledTaskEntity.TYPE_WEEKLY -> append(" 周${task.weekDays} %02d:%02d".format(task.timeMinutes / 60, task.timeMinutes % 60))
        ScheduledTaskEntity.TYPE_INTERVAL -> append(" 每${task.intervalMinutes}分钟")
        ScheduledTaskEntity.TYPE_ONCE -> append(" " + SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(task.startAt)))
    }
    append(if (task.enabled) "，启用中" else "，已停用")
    val next = TaskScheduler.computeNextTrigger(task)
    if (next != null && task.enabled) {
        append("，下次 " + SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(next)))
    }
    append("）")
}

internal fun buildCreateScheduleTool(repo: ScheduledTaskRepository, assistant: Assistant): Tool = Tool(
    name = "create_schedule",
    description = """
        Create a scheduled task: you will proactively send a message at the scheduled time
        (the generated message is posted to a dedicated conversation and a system notification).
        Use when the user asks for reminders / scheduled briefings / proactive messages like
        "每天早上给我新闻摘要" or "10分钟后提醒我". Types: ONCE (at a specific time),
        DAILY (every day at HH:mm), WEEKLY (specific weekdays at HH:mm), INTERVAL (every N minutes).
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("title", buildJsonObject { put("type", "string"); put("description", "Short task name, e.g. '早安播报'") })
                put("prompt", buildJsonObject { put("type", "string"); put("description", "The instruction to run at trigger time. For action_type=SHELL this is the shell command (runs as root).") })
                put("action_type", buildJsonObject {
                    put("type", "string")
                    put("enum", JsonArray(listOf("LLM", "SHELL").map { JsonPrimitive(it) }))
                    put("description", "LLM (default): prompt is sent to you as a user message; SHELL: prompt is executed as a root shell command and its output is posted")
                })
                put("type", buildJsonObject {
                    put("type", "string")
                    put("enum", JsonArray(listOf("ONCE", "DAILY", "WEEKLY", "INTERVAL").map { JsonPrimitive(it) }))
                })
                put("time", buildJsonObject { put("type", "string"); put("description", "HH:mm, required for DAILY/WEEKLY") })
                put("weekdays", buildJsonObject { put("type", "string"); put("description", "For WEEKLY: comma-separated 1-7 (1=Mon..7=Sun), e.g. '1,3,5'") })
                put("interval_minutes", buildJsonObject { put("type", "integer"); put("description", "For INTERVAL: minutes between runs, >= 15") })
                put("start_at", buildJsonObject { put("type", "integer"); put("description", "For ONCE: epoch millis of the trigger time") })
            },
            required = listOf("title", "prompt", "type")
        )
    },
    needsApproval = { json ->
        // SHELL 任务以 root 执行命令: 强制用户审批
        json.jsonObject["action_type"]?.jsonPrimitive?.contentOrNull?.uppercase() == "SHELL"
    },
    execute = { args ->
        val obj = args.jsonObject
        val title = obj["title"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool listOf(UIMessagePart.Text("错误: 缺少 title"))
        val prompt = obj["prompt"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool listOf(UIMessagePart.Text("错误: 缺少 prompt"))
        val type = obj["type"]?.jsonPrimitive?.contentOrNull?.uppercase()
            ?: return@Tool listOf(UIMessagePart.Text("错误: 缺少 type"))

        val timeStr = obj["time"]?.jsonPrimitive?.contentOrNull
        val timeMinutes = timeStr?.split(':')?.let {
            val h = it.getOrNull(0)?.toIntOrNull() ?: return@Tool listOf(UIMessagePart.Text("错误: time 格式应为 HH:mm"))
            val m = it.getOrNull(1)?.toIntOrNull() ?: 0
            if (h !in 0..23 || m !in 0..59) return@Tool listOf(UIMessagePart.Text("错误: time 格式应为 HH:mm"))
            h * 60 + m
        }
        val interval = obj["interval_minutes"]?.jsonPrimitive?.intOrNull
        val startAt = obj["start_at"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()

        // 参数校验
        val err = when (type) {
            ScheduledTaskEntity.TYPE_DAILY -> if (timeMinutes == null) "DAILY 需要 time (HH:mm)" else null
            ScheduledTaskEntity.TYPE_WEEKLY -> {
                val days = obj["weekdays"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val valid = days.split(',').filter { it.isNotBlank() }.all { it.trim().toIntOrNull() in 1..7 }
                when {
                    timeMinutes == null -> "WEEKLY 需要 time (HH:mm)"
                    days.isBlank() || !valid -> "WEEKLY 需要 weekdays（1=周一..7=周日，逗号分隔，如 1,3,5）"
                    else -> null
                }
            }
            ScheduledTaskEntity.TYPE_INTERVAL ->
                if (interval == null || interval < 15) "INTERVAL 需要 interval_minutes 且 >= 15" else null
            ScheduledTaskEntity.TYPE_ONCE ->
                if (startAt == null || startAt <= System.currentTimeMillis()) "ONCE 需要未来的 start_at（epoch 毫秒）" else null
            else -> "未知 type: $type"
        }
        if (err != null) return@Tool listOf(UIMessagePart.Text("错误: $err"))

        val actionType = obj["action_type"]?.jsonPrimitive?.contentOrNull?.uppercase()
            ?.takeIf { it == ScheduledTaskEntity.ACTION_SHELL }
            ?: ScheduledTaskEntity.ACTION_LLM

        // SHELL 命令过安全闸门: 高危命令直接拒绝创建
        if (actionType == ScheduledTaskEntity.ACTION_SHELL) {
            ShellSafety.blockReason(prompt)?.let { reason ->
                return@Tool listOf(UIMessagePart.Text("错误: 命令被安全闸门拦截，未创建: $reason"))
            }
        }

        val task = ScheduledTaskEntity(
            id = Uuid.random().toString(),
            assistantId = assistant.id.toString(),
            title = title,
            prompt = prompt,
            type = type,
            timeMinutes = timeMinutes ?: 9 * 60,
            weekDays = obj["weekdays"]?.jsonPrimitive?.contentOrNull ?: "",
            intervalMinutes = interval ?: 60,
            startAt = startAt ?: 0,
            actionType = actionType,
        )
        repo.upsert(task)
        listOf(UIMessagePart.Text("定时任务已创建：\n" + formatTask(task)))
    }
)

internal fun buildListSchedulesTool(repo: ScheduledTaskRepository, assistant: Assistant): Tool = Tool(
    name = "list_schedules",
    description = "List all scheduled tasks of the current assistant (id, type, time, next trigger).",
    parameters = { InputSchema.Obj(properties = buildJsonObject {}, required = emptyList()) },
    execute = {
        val tasks = repo.getAll().filter { it.assistantId == assistant.id.toString() }
        if (tasks.isEmpty()) {
            listOf(UIMessagePart.Text("当前没有定时任务。"))
        } else {
            listOf(UIMessagePart.Text("当前定时任务：\n" + tasks.joinToString("\n") { formatTask(it) }))
        }
    }
)

internal fun buildDeleteScheduleTool(repo: ScheduledTaskRepository, assistant: Assistant): Tool = Tool(
    name = "delete_schedule",
    description = "Delete a scheduled task by its id (the 8-char prefix shown by list_schedules is accepted).",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject { put("type", "string"); put("description", "Task id or its prefix") })
            },
            required = listOf("id")
        )
    },
    execute = { args ->
        val id = args.jsonObject["id"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool listOf(UIMessagePart.Text("错误: 缺少 id"))
        if (id.length < 4) return@Tool listOf(UIMessagePart.Text("错误: id 前缀至少 4 位"))
        // 只允许操作当前助手的任务(防跨助手越权)
        val mine = repo.getAll().filter { it.assistantId == assistant.id.toString() }
        val matches = mine.filter { it.id == id || it.id.startsWith(id) }
        when {
            matches.isEmpty() -> return@Tool listOf(UIMessagePart.Text("未找到任务: $id"))
            matches.size > 1 -> return@Tool listOf(
                UIMessagePart.Text(
                    "id 前缀匹配到 ${matches.size} 个任务，请提供更长的 id：\n" +
                        matches.joinToString("\n") { formatTask(it) }
                )
            )
        }
        val task = matches.first()
        repo.delete(task.id)
        listOf(UIMessagePart.Text("已删除定时任务「${task.title}」。"))
    }
)

internal fun buildToggleScheduleTool(repo: ScheduledTaskRepository, assistant: Assistant): Tool = Tool(
    name = "toggle_schedule",
    description = "Enable or disable a scheduled task by id (prefix accepted).",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject { put("type", "string"); put("description", "Task id or its prefix") })
                put("enabled", buildJsonObject { put("type", "boolean") })
            },
            required = listOf("id", "enabled")
        )
    },
    execute = { args ->
        val id = args.jsonObject["id"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool listOf(UIMessagePart.Text("错误: 缺少 id"))
        val enabled = args.jsonObject["enabled"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
            ?: return@Tool listOf(UIMessagePart.Text("错误: 缺少 enabled (true/false)"))
        if (id.length < 4) return@Tool listOf(UIMessagePart.Text("错误: id 前缀至少 4 位"))
        val mine = repo.getAll().filter { it.assistantId == assistant.id.toString() }
        val matches = mine.filter { it.id == id || it.id.startsWith(id) }
        when {
            matches.isEmpty() -> return@Tool listOf(UIMessagePart.Text("未找到任务: $id"))
            matches.size > 1 -> return@Tool listOf(
                UIMessagePart.Text("id 前缀匹配到 ${matches.size} 个任务，请提供更长的 id。")
            )
        }
        val task = matches.first()
        repo.setEnabled(task.id, enabled)
        listOf(UIMessagePart.Text("已${if (enabled) "启用" else "停用"}定时任务「${task.title}」。"))
    }
)
