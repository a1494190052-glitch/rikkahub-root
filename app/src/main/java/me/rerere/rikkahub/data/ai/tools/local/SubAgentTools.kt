package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.model.Assistant

/**
 * 智能体集群（招聘雇佣助手）
 *
 * 主管助手通过 hire_agent / hire_team 工具把任务拆给子代理并行执行。
 * 子代理 = 虚拟 Assistant（角色化 systemPrompt + 继承父助手启用的工具，
 * 但剔除 SubAgents 防递归）+ 独立消息上下文，复用 GenerationHandler 的
 * 完整生成管线（含工具调用循环）。
 */
class SubAgentExecutor(
    private val settingsStore: SettingsStore,
    private val generationHandler: GenerationHandler,
    private val localTools: LocalTools,
) {
    data class SubTask(
        val role: String,
        val task: String,
        val model: String = "fast", // "fast" | "smart"
    )

    data class SubResult(
        val role: String,
        val ok: Boolean,
        val output: String = "",
        val error: String = "",
        val model: String = "fast",
        val rounds: Int = 0,
        val elapsedMs: Long = 0,
    )

    private val semaphore = Semaphore(MAX_PARALLEL)

    /** 并行执行一组子任务（最多 [MAX_PARALLEL] 个），单个失败不拖垮全队 */
    suspend fun runTeam(tasks: List<SubTask>, parent: Assistant): Pair<List<SubResult>, Long> {
        val started = System.currentTimeMillis()
        val results = coroutineScope {
            tasks.take(MAX_PARALLEL).map { t ->
                async {
                    semaphore.withPermit {
                        runCatching { runOne(t, parent) }
                            .getOrElse { e ->
                                SubResult(
                                    role = t.role, ok = false,
                                    error = e.message?.take(300) ?: e.javaClass.simpleName,
                                    model = t.model,
                                )
                            }
                    }
                }
            }.map { it.await() }
        }
        return results to (System.currentTimeMillis() - started)
    }

    private suspend fun runOne(task: SubTask, parent: Assistant): SubResult {
        val started = System.currentTimeMillis()
        val settings = settingsStore.settingsFlow.first()

        // 模型选择: smart = 父助手当前模型; fast = 全局快速模型(未配置时回退父模型)
        val model = when (task.model.lowercase()) {
            "smart" -> settings.findModelById(parent.chatModelId, fallback = settings.fastModelId)
            else -> settings.findModelById(settings.fastModelId)
                ?: settings.findModelById(parent.chatModelId)
        } ?: error("没有可用模型（请先在设置里配置快速模型或给助手绑定模型）")

        // 虚拟子代理助手: 角色化提示词, 剔除 SubAgents 防递归, 关记忆, 非流式,
        // 清空预置消息/模式注入——子代理是干净角色, 不继承父助手的上下文装饰
        val worker = parent.copy(
            systemPrompt = buildWorkerPrompt(parent, task.role),
            localTools = parent.localTools.filter { it != LocalToolOption.SubAgents },
            enableMemory = false,
            useGlobalMemory = false,
            enableRecentChatsReference = false,
            streamOutput = false,
            presetMessages = emptyList(),
            modeInjectionIds = emptySet(),
        )
        // 子代理用无持久会话的工具集: 并行子代理共享持久 root 会话会互相污染 cwd
        val tools = localTools.forSubAgent().getTools(worker.localTools)

        val finalChunk = withTimeout(TIMEOUT_MS) {
            generationHandler.generateText(
                settings = settings,
                model = model,
                messages = listOf(UIMessage.user(task.task)),
                assistant = worker,
                tools = tools,
                maxSteps = MAX_STEPS,
            ).last()
        }

        val finalMessages = (finalChunk as? GenerationChunk.Messages)?.messages ?: emptyList()
        val output = finalMessages.lastOrNull()?.toText()?.trim().orEmpty()
        val rounds = finalMessages.size - 1 // 减去用户任务消息 ≈ 生成轮数
        // 死胡同检测：最后一条 assistant 消息含有未执行的工具调用
        // （典型为本地工具审批悬置，子代理无人可批准 → 生成循环提前结束）
        val lastAssistant = finalMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
        val pendingToolNames = lastAssistant?.parts
            ?.filterIsInstance<UIMessagePart.Tool>()
            ?.filter { !it.isExecuted }
            ?.map { it.toolName }
            .orEmpty()
        if (pendingToolNames.isNotEmpty()) {
            return SubResult(
                role = task.role,
                ok = false,
                output = output,
                error = "子代理结束于未完成的工具调用 ${pendingToolNames.joinToString()}（可能是本地工具审批悬置，子代理无人批准）。建议在子代理任务描述中改用只读/自动批准的工具。",
                model = task.model,
                rounds = rounds.coerceAtLeast(1),
                elapsedMs = System.currentTimeMillis() - started,
            )
        }
        return SubResult(
            role = task.role,
            ok = true,
            output = output.ifBlank { "（子代理未产生输出）" },
            model = task.model,
            rounds = rounds.coerceAtLeast(1),
            elapsedMs = System.currentTimeMillis() - started,
        )
    }

    private fun buildWorkerPrompt(parent: Assistant, role: String): String = """
        你是「${parent.name.ifBlank { "主管助手" }}」智能体团队招聘的专业子代理，角色定位：$role。

        工作准则：
        - 专注完成分配给你的子任务，直接输出最终成果，不要寒暄、不要汇报过程、不要复述任务
        - 你看不到团队的对话历史，任务描述里包含了你需要的全部信息
        - 如需调用工具，请高效规划（最多 $MAX_STEPS 轮工具调用）
        - 若工具返回审批/权限错误，你无法获得人工批准——立即换用其他自动批准的工具或在结果中说明受阻原因，不要重复尝试同一工具
        - 输出必须完整、自包含——主管会把你的成果整合进最终答案，不会再来追问你
    """.trimIndent()

    companion object {
        const val MAX_PARALLEL = 4
        const val MAX_STEPS = 8
        const val TIMEOUT_MS = 5 * 60 * 1000L

        fun List<SubResult>.toTeamMarkdown(totalMs: Long): String = buildString {
            append("## 子代理团队成果（${this@toTeamMarkdown.size} 人并行 · 总耗时 ${totalMs / 1000.0}s）\n\n")
            this@toTeamMarkdown.forEachIndexed { i, r ->
                append("### ${i + 1}. ${r.role}（${r.model} 模型 · ${r.rounds} 轮 · ${r.elapsedMs / 1000.0}s）\n\n")
                append(if (r.ok) r.output else "⚠️ 执行失败：${r.error}")
                if (i != this@toTeamMarkdown.lastIndex) append("\n\n---\n\n")
            }
        }
    }
}

private const val HIRE_USAGE_HINT =
    "你可以通过 hire_agent / hire_team 招聘子代理并行干活。当任务可拆成多个独立子任务时" +
    "（多角度调研、分块写作、方案对比），优先用 hire_team 一次并行派工，比逐个做快数倍。" +
    "派给子代理的任务必须自包含（子代理看不到本对话）。"

/** 招聘单个子代理 */
internal fun buildHireAgentTool(executor: SubAgentExecutor, parent: Assistant): Tool = Tool(
    name = "hire_agent",
    description = """
        Hire ONE sub-agent (independent AI worker) to complete a subtask autonomously.
        The sub-agent has a clean context (CANNOT see this conversation) and inherits your enabled LOCAL tools only
        (shell, workspace files, memory, schedule etc. — NO web search, MCP, pty_exec, or other remote/approval-gated tools).
        Local tools requiring user approval will dead-end inside a sub-agent (no one can approve);
        write tasks so read-only/auto-approved tools suffice.
        Use for decomposable work: research from multiple angles, drafting sections, comparing options.
        IMPORTANT: 'task' must be fully self-contained with all necessary context.
        For 2+ independent subtasks, prefer hire_team (parallel, much faster).
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("role", buildJsonObject {
                    put("type", "string")
                    put("description", "Role of the sub-agent, e.g. 'industry researcher', 'copywriter'")
                })
                put("task", buildJsonObject {
                    put("type", "string")
                    put("description", "Self-contained task description with ALL necessary context")
                })
                put("model", buildJsonObject {
                    put("type", "string")
                    put("enum", JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("fast"), kotlinx.serialization.json.JsonPrimitive("smart"))))
                    put("description", "'fast' (default, cheap & quick) or 'smart' (same model tier as you, for hard reasoning)")
                })
            },
            required = listOf("role", "task")
        )
    },
    systemPrompt = { _, _ -> HIRE_USAGE_HINT },
    execute = { args ->
        val role = args.jsonObject["role"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool listOf(UIMessagePart.Text("错误: 缺少 role 参数"))
        val task = args.jsonObject["task"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool listOf(UIMessagePart.Text("错误: 缺少 task 参数"))
        val model = args.jsonObject["model"]?.jsonPrimitive?.contentOrNull ?: "fast"
        val (results, totalMs) = executor.runTeam(listOf(SubAgentExecutor.SubTask(role, task, model)), parent)
        listOf(UIMessagePart.Text(with(SubAgentExecutor) { results.toTeamMarkdown(totalMs) }))
    }
)

/** 招聘子代理团队并行执行 */
internal fun buildHireTeamTool(executor: SubAgentExecutor, parent: Assistant): Tool = Tool(
    name = "hire_team",
    description = """
        Hire up to ${SubAgentExecutor.MAX_PARALLEL} sub-agents to work on independent subtasks in PARALLEL
        (one call, all run concurrently, results returned together). Much faster than sequential hire_agent calls.
        Use when you have 2+ independent subtasks. Each task must be self-contained.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("tasks", buildJsonObject {
                    put("type", "array")
                    put("description", "List of subtasks to run in parallel (max ${SubAgentExecutor.MAX_PARALLEL})")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("role", buildJsonObject {
                                put("type", "string")
                                put("description", "Role of the sub-agent")
                            })
                            put("task", buildJsonObject {
                                put("type", "string")
                                put("description", "Self-contained task description")
                            })
                            put("model", buildJsonObject {
                                put("type", "string")
                                put("enum", JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("fast"), kotlinx.serialization.json.JsonPrimitive("smart"))))
                                put("description", "'fast' (default) or 'smart'")
                            })
                        })
                        put("required", JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("role"), kotlinx.serialization.json.JsonPrimitive("task"))))
                    })
                })
            },
            required = listOf("tasks")
        )
    },
    systemPrompt = { _, _ -> HIRE_USAGE_HINT },
    execute = { args ->
        val tasksEl = args.jsonObject["tasks"]?.jsonArray
            ?: return@Tool listOf(UIMessagePart.Text("错误: 缺少 tasks 参数"))
        val tasks = tasksEl.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val role = obj["role"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val task = obj["task"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val model = obj["model"]?.jsonPrimitive?.contentOrNull ?: "fast"
            SubAgentExecutor.SubTask(role, task, model)
        }
        if (tasks.isEmpty()) {
            return@Tool listOf(UIMessagePart.Text("错误: tasks 为空或格式不正确（需要 [{role, task, model?}]）"))
        }
        val truncated = tasks.size > SubAgentExecutor.MAX_PARALLEL
        val (results, totalMs) = executor.runTeam(tasks.take(SubAgentExecutor.MAX_PARALLEL), parent)
        val md = buildString {
            if (truncated) append("⚠️ 子任务数量超过上限，已截取前 ${SubAgentExecutor.MAX_PARALLEL} 个并行执行。\n\n")
            append(with(SubAgentExecutor) { results.toTeamMarkdown(totalMs) })
        }
        listOf(UIMessagePart.Text(md))
    }
)
