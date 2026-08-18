@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package me.rerere.rikkahub.acp

import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.client.ClientOperationsFactory
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.ClientCapabilities
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.PermissionOptionKind
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.transport.StdioTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.GenerationChunk
import java.util.concurrent.ConcurrentHashMap

/**
 * RikkaHub 的 ACP 客户端运行时 —— 把一个实现了 Agent Client Protocol 的外部
 * agent（codex / gemini-cli / deepseek-harness）作为可选的后端接入聊天。
 *
 * 与 OmniBot 的 LocalAcpRuntime 对应：spawn 一个 agent 子进程、走 stdio
 * NDJSON、把 session update 翻译成 RikkaHub 的时间线消息，并在 agent 请求
 * 权限时挂起等待用户审批。
 *
 * 与 RikkaHub 现有 Provider 循环的关键差异：ACP agent 自带工具并自行执行，
 * 只向宿主（RikkaHub）请求审批，因此这里是一条独立于 GenerationHandler 的
 * 执行路径（在 ChatService.handleMessageComplete 里分流）。
 */
class AcpRuntime(
    private val scope: CoroutineScope,
    private val processBuilderFactory: suspend (AcpAgentProfile) -> ProcessBuilder,
) {
    private val connectMutex = Mutex()

    /** conversationId -> ACP session */
    private val sessions = ConcurrentHashMap<String, ClientSession>()

    /** requestId(=toolCallId) -> 待审批请求 */
    private val pendingPermissions = ConcurrentHashMap<String, PendingPermission>()

    /** conversationId -> 进行中的 turn 状态 */
    private val turns = ConcurrentHashMap<String, TurnState>()

    @Volatile
    private var connection: AcpProcessConnection? = null

    @Volatile
    private var protocol: Protocol? = null

    @Volatile
    private var client: Client? = null

    @Volatile
    private var activeProfile: AcpAgentProfile? = null

    val isConnected: Boolean
        get() = connection?.isRunning == true && client != null

    private data class PendingPermission(
        val conversationId: String,
        val options: List<PermissionOption>,
        val response: CompletableDeferred<PermissionOption?>,
    )

    private data class TurnState(
        val conversationId: String,
        val baseMessages: List<UIMessage>,
        val assistant: MutableStateFlow<UIMessage>,
        val onChunk: suspend (GenerationChunk) -> Unit,
    )

    // ---- 连接生命周期 ----

    suspend fun connect(profile: AcpAgentProfile) = connectMutex.withLock {
        if (isConnected && activeProfile?.id == profile.id) return@withLock
        disconnect()
        val conn = AcpProcessConnection(scope, Dispatchers.IO) { processBuilderFactory(profile) }
        val transport = StdioTransport(
            parentScope = scope,
            ioDispatcher = Dispatchers.IO,
            input = conn.input,
            output = conn::writeLine,
            name = "rikkahub-acp-${profile.id}",
        )
        val nextProtocol = Protocol(scope, transport)
        val nextClient = Client(nextProtocol)
        conn.start()
        nextProtocol.start()
        nextClient.initialize(
            ClientInfo(
                capabilities = ClientCapabilities(
                    // 不给 agent 暴露客户端 fs 能力：agent 在 proot 工作区里
                    // 直接读写 /workspace，无需宿主代读。
                    fs = null,
                    terminal = false,
                ),
                implementation = Implementation(
                    name = "rikkahub-app",
                    version = "1.0.0",
                    title = "RikkaHub",
                ),
            )
        )
        connection = conn
        protocol = nextProtocol
        client = nextClient
        activeProfile = profile
    }

    fun disconnect() {
        runCatching { protocol?.close() }
        protocol = null
        client = null
        activeProfile = null
        val conn = connection
        connection = null
        conn?.let { c -> scope.launch { runCatching { c.close() } } }
        sessions.clear()
        pendingPermissions.values.forEach { it.response.complete(null) }
        pendingPermissions.clear()
    }

    // ---- turn 执行 ----

    /**
     * 执行一轮对话。传入本轮的 [baseMessages]（含刚追加的用户消息）与
     * [userPrompt]（要发给 agent 的文本，通常是最后一条用户消息）。
     * 流式进度通过 [onChunk] 以 GenerationChunk.Messages 回传，与
     * GenerationHandler 的产物同构，ChatService 的 collect 逻辑可直接复用。
     */
    suspend fun runTurn(
        conversationId: String,
        profile: AcpAgentProfile,
        baseMessages: List<UIMessage>,
        userPrompt: String,
        onChunk: suspend (GenerationChunk) -> Unit,
    ) {
        connect(profile)
        val session = ensureSession(conversationId, profile.cwd)
        val assistant = MutableStateFlow(
            UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())
        )
        turns[conversationId] = TurnState(conversationId, baseMessages, assistant, onChunk)
        try {
            val blocks = listOf(ContentBlock.Text(userPrompt))
            session.prompt(blocks).collect { event ->
                when (event) {
                    is Event.SessionUpdateEvent -> handleSessionUpdate(conversationId, event.update)
                    is Event.PromptResponseEvent -> Unit // stopReason 已在 collect 结束时自然收尾
                }
            }
        } catch (error: CancellationException) {
            runCatching { session.cancel() }
            throw error
        } catch (error: Throwable) {
            appendAssistantText(conversationId, "\n[ACP 错误] ${error.message ?: error.javaClass.simpleName}")
            throw error
        } finally {
            emitChunk(conversationId) // 收尾：确保最终 assistant 状态已回传
            turns.remove(conversationId)
        }
    }

    /** 该 toolCallId 是否是一个等待中的 ACP 权限请求（ChatService 据此分流审批）。 */
    fun isPendingPermission(toolCallId: String): Boolean = pendingPermissions.containsKey(toolCallId)

    /**
     * 回应用户的审批决定。翻转 assistant 里对应 Tool 的 approvalState 并唤醒
     * 挂起的 prompt 流程（agent 继续执行）。
     */
    suspend fun respondPermission(toolCallId: String, approved: Boolean, reason: String = "", answer: String? = null) {
        val pending = pendingPermissions[toolCallId] ?: return
        val newState: ToolApprovalState = when {
            answer != null -> ToolApprovalState.Answered(answer)
            approved -> ToolApprovalState.Approved
            else -> ToolApprovalState.Denied(reason)
        }
        turns[pending.conversationId]?.assistant?.update { msg ->
            msg.copy(parts = msg.parts.map { part ->
                if (part is UIMessagePart.Tool && part.toolCallId == toolCallId) {
                    part.copy(approvalState = newState)
                } else part
            })
        }
        emitChunk(pending.conversationId)

        val selected = pending.options.firstOrNull { option ->
            when {
                answer != null -> false
                approved -> option.kind == PermissionOptionKind.ALLOW_ONCE ||
                    option.kind == PermissionOptionKind.ALLOW_ALWAYS
                else -> option.kind == PermissionOptionKind.REJECT_ONCE ||
                    option.kind == PermissionOptionKind.REJECT_ALWAYS
            }
        }
        pending.response.complete(selected)
        pendingPermissions.remove(toolCallId)
    }

    suspend fun interrupt(conversationId: String) {
        runCatching { sessions[conversationId]?.cancel() }
        pendingPermissions.values.filter { it.conversationId == conversationId }.forEach {
            it.response.complete(null)
        }
    }

    // ---- 内部 ----

    private suspend fun ensureSession(conversationId: String, cwd: String): ClientSession {
        sessions[conversationId]?.let { return it }
        val created = requireClient().newSession(
            SessionCreationParameters(cwd, emptyList()),
            ClientOperationsFactory { _, _ -> AcpClientOperations(conversationId) },
        )
        sessions[conversationId] = created
        return created
    }

    private suspend fun handleSessionUpdate(conversationId: String, update: SessionUpdate) {
        when (update) {
            is SessionUpdate.AgentMessageChunk ->
                appendAssistantText(conversationId, update.content.textPayload())

            is SessionUpdate.AgentThoughtChunk ->
                appendReasoning(conversationId, update.content.textPayload())

            is SessionUpdate.ToolCall -> {
                updateToolPartById(conversationId, update.toolCallId.value) { existing ->
                    if (existing != null) existing else UIMessagePart.Tool(
                        toolCallId = update.toolCallId.value,
                        toolName = resolveToolName(update.title, update.rawInput) ?: "tool",
                        input = update.rawInput?.toString() ?: "{}",
                    )
                }
                emitChunk(conversationId)
            }

            is SessionUpdate.ToolCallUpdate -> {
                val outputText: String? = when (update.status) {
                    ToolCallStatus.FAILED -> update.errorMessage() ?: "failed"
                    ToolCallStatus.COMPLETED -> update.resultText() ?: update.terminalOutput()
                    else -> update.terminalOutput() // 命令工具进行中的流式输出
                }
                updateToolPartById(conversationId, update.toolCallId.value) { existing ->
                    val tool = existing ?: UIMessagePart.Tool(
                        toolCallId = update.toolCallId.value,
                        toolName = resolveToolName(update.title, update.rawInput) ?: "tool",
                        input = update.rawInput?.toString() ?: "{}",
                    )
                    if (outputText != null && tool.output.isEmpty()) {
                        tool.copy(output = listOf(UIMessagePart.Text(outputText)))
                    } else tool
                }
                emitChunk(conversationId)
            }

            else -> Unit // plan / usage / config 等暂不渲染
        }
    }

    private suspend fun appendAssistantText(conversationId: String, delta: String) {
        if (delta.isEmpty()) return
        val turn = turns[conversationId] ?: return
        turn.assistant.update { msg ->
            val last = msg.parts.lastOrNull()
            if (last is UIMessagePart.Text) {
                msg.copy(parts = msg.parts.dropLast(1) + last.copy(text = last.text + delta))
            } else {
                msg.copy(parts = msg.parts + UIMessagePart.Text(delta))
            }
        }
        emitChunk(conversationId)
    }

    private suspend fun appendReasoning(conversationId: String, delta: String) {
        if (delta.isEmpty()) return
        val turn = turns[conversationId] ?: return
        turn.assistant.update { msg ->
            val last = msg.parts.lastOrNull()
            if (last is UIMessagePart.Reasoning) {
                msg.copy(parts = msg.parts.dropLast(1) + last.copy(reasoning = last.reasoning + delta))
            } else {
                msg.copy(parts = msg.parts + UIMessagePart.Reasoning(reasoning = delta))
            }
        }
        emitChunk(conversationId)
    }

    private suspend fun updateToolPartById(
        conversationId: String,
        toolCallId: String,
        transform: (UIMessagePart.Tool?) -> UIMessagePart.Tool,
    ) {
        val turn = turns[conversationId] ?: return
        turn.assistant.update { msg ->
            val idx = msg.parts.indexOfFirst { it is UIMessagePart.Tool && it.toolCallId == toolCallId }
            val existing = if (idx >= 0) msg.parts[idx] as UIMessagePart.Tool else null
            val updated = transform(existing)
            if (idx >= 0) msg.copy(parts = msg.parts.toMutableList().also { it[idx] = updated })
            else msg.copy(parts = msg.parts + updated)
        }
    }

    private suspend fun emitChunk(conversationId: String) {
        val turn = turns[conversationId] ?: return
        turn.onChunk(GenerationChunk.Messages(turn.baseMessages + turn.assistant.value))
    }

    private fun requireClient(): Client = client
        ?: throw IllegalStateException("ACP agent is not connected")

    private inner class AcpClientOperations(
        private val conversationId: String,
    ) : ClientSessionOperations {
        override suspend fun requestPermissions(
            toolCall: SessionUpdate.ToolCallUpdate,
            permissions: List<PermissionOption>,
            _meta: JsonElement?,
        ): RequestPermissionResponse {
            val requestId = toolCall.toolCallId.value
            val pending = PendingPermission(
                conversationId = conversationId,
                options = permissions,
                response = CompletableDeferred(),
            )
            pendingPermissions[requestId] = pending
            // 时间线里加入一个待审批的工具卡片，等用户在 UI 上决定。
            updateToolPartById(conversationId, requestId) { existing ->
                val tool = existing ?: UIMessagePart.Tool(
                    toolCallId = requestId,
                    toolName = resolveToolName(toolCall.title, toolCall.rawInput) ?: "tool",
                    input = toolCall.rawInput?.toString() ?: "{}",
                )
                tool.copy(approvalState = ToolApprovalState.Pending)
            }
            emitChunk(conversationId)

            val selected = pending.response.await()
            pendingPermissions.remove(requestId)
            return RequestPermissionResponse(
                outcome = selected?.let { RequestPermissionOutcome.Selected(it.optionId) }
                    ?: RequestPermissionOutcome.Cancelled,
            )
        }

        override suspend fun notify(notification: SessionUpdate, _meta: JsonElement?) {
            handleSessionUpdate(conversationId, notification)
        }
    }

    private suspend fun com.agentclientprotocol.model.ContentBlock.textPayload(): String = when (this) {
        is com.agentclientprotocol.model.ContentBlock.Text -> text
        is com.agentclientprotocol.model.ContentBlock.ResourceLink -> title ?: name
        is com.agentclientprotocol.model.ContentBlock.Image -> uri ?: ""
        is com.agentclientprotocol.model.ContentBlock.Audio -> ""
        is com.agentclientprotocol.model.ContentBlock.Resource -> resource.toString()
    }
}

