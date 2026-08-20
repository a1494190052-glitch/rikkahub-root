package me.rerere.ai.provider.providers

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.json
import me.rerere.common.http.await
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

private const val TAG = "DshProvider"
/** DSH 支持的 agentPreset（模式），作为模型入口展示 */
private val AGENT_PRESETS = setOf("standard", "code", "minimal", "cordis")

/**
 * DeepSeek Harness Provider：把 RikkaHub 作为前端，DSH 作为模型后端。
 *
 * 通过 DSH 的 Web RPC 协议通信：
 *  - 上行：POST /api/<method>，body 为四象限 ClientRequest
 *  - 下行：/api/events.mux WebSocket，推送 session/event 帧（assistant/chunk 即 token 流）
 *
 * 会话复用：以 modelId 为 key 缓存 DSH sessionId，同一模式（模型）的连续消息共享一个 DSH 会话，
 * 完整保留 DSH 的 agent 工作模式（bash 状态、文件操作、多轮工具调用）。
 * DSH 是服务端完整 agent，RikkaHub 只透传用户消息并流式接收最终 assistant 文本。
 */
class DshProvider(
    private val client: OkHttpClient,
) : Provider<ProviderSetting.Dsh> {

    /** modelId -> DSH sessionId 缓存，实现会话复用 */
    private val sessionCache = ConcurrentHashMap<String, String>()

    private fun rpcUrl(baseUrl: String, method: String): String {
        val trimmed = baseUrl.trimEnd('/')
        return "$trimmed/api/$method"
    }

    private fun wsUrl(baseUrl: String, stream: String): String {
        val trimmed = baseUrl.trimEnd('/')
        // http(s) -> ws(s)
        val wsBase = trimmed.replaceFirst("^http".toRegex(), "ws")
        return "$wsBase/api/events.$stream"
    }

    private fun clientRequest(rpcId: String, method: String, payload: JsonObject): String {
        return json.encodeToString(
            buildJsonObject {
                put("type", "client-request")
                put("rpcId", rpcId)
                put("method", method)
                put("payload", payload)
            }
        )
    }

    /** 执行一个 unary RPC，返回 server-response 的 result 对象（{ok, value} 或 {ok, error}）。 */
    private suspend fun callRpc(
        baseUrl: String,
        method: String,
        payload: JsonObject,
    ): JsonObject = withContext(Dispatchers.IO) {
        val body = clientRequest("rpc-" + Uuid.random(), method, payload)
        val request = Request.Builder()
            .url(rpcUrl(baseUrl, method))
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            error("DSH RPC $method failed: HTTP ${response.code} ${response.body?.string()}")
        }
        val bodyStr = response.body?.string() ?: ""
        val root = json.parseToJsonElement(bodyStr).jsonObject
        root["result"]?.jsonObject ?: error("DSH RPC $method: no result in $bodyStr")
    }

    /** 确保返回 ok:true，否则抛出 RPC 错误。 */
    private fun requireOk(result: JsonObject, method: String): JsonObject {
        val ok = result["ok"]?.jsonPrimitive?.contentOrNull
        if (ok != "true") {
            val err = result["error"]?.jsonObject
            val code = err?.get("code")?.jsonPrimitive?.contentOrNull ?: "unknown"
            val detail = err?.get("details")?.toString() ?: ""
            error("DSH RPC $method failed: code=$code $detail")
        }
        return result["value"]?.jsonObject ?: buildJsonObject {}
    }

    /** 获取或创建 DSH 会话（会话复用）。必要时设置权限预设。 */
    private suspend fun ensureSession(providerSetting: ProviderSetting.Dsh, modelId: String): String {
        val cached = sessionCache[modelId]
        if (cached != null) {
            // 权限可能变化，每次会话复用前同步一次权限预设（幂等）
            setPermission(providerSetting, cached)
            return cached
        }
        // 模式由所选模型（modelId）决定；回退到 providerSetting.agentPreset
        val preset = if (modelId in AGENT_PRESETS) modelId else providerSetting.agentPreset
        // 创建会话并指定模式
        val createPayload = buildJsonObject {
            put("agentPreset", preset)
        }
        val createResult = requireOk(callRpc(providerSetting.baseUrl, "session.create", createPayload), "session.create")
        val sessionId = createResult["sessionId"]?.jsonPrimitive?.contentOrNull
            ?: error("DSH session.create: no sessionId")
        sessionCache[modelId] = sessionId
        // 设置权限预设
        setPermission(providerSetting, sessionId)
        return sessionId
    }

    /** 通过 /permission 斜杠命令设置权限预设（read-only / workspace-write / danger-full-access）。 */
    private suspend fun setPermission(providerSetting: ProviderSetting.Dsh, sessionId: String) {
        val preset = providerSetting.sandboxMode
        // /permission <preset> 是斜杠命令，经 session.prompt 发送
        val promptPayload = buildJsonObject {
            put("sessionId", sessionId)
            put("mode", "queue")
            putJsonArray("content") {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", "/permission $preset")
                })
            }
        }
        // 权限命令是幂等的；失败不阻塞主流程
        runCatching {
            requireOk(callRpc(providerSetting.baseUrl, "session.prompt", promptPayload), "session.prompt")
        }
    }

    override suspend fun listModels(providerSetting: ProviderSetting.Dsh): List<Model> {
        // 模式（agentPreset）作为独立模型入口，供用户在模型选择器中动态选择
        return listOf(
            Model(
                id = Uuid.random(),
                modelId = "standard",
                displayName = "标准模式 (Standard)",
            ),
            Model(
                id = Uuid.random(),
                modelId = "minimal",
                displayName = "极简模式 (Minimal)",
            ),
            Model(
                id = Uuid.random(),
                modelId = "code",
                displayName = "PTC 模式 (Code)",
            ),
            Model(
                id = Uuid.random(),
                modelId = "cordis",
                displayName = "创造模式 (Cordis)",
            ),
        )
    }

    override suspend fun generateText(
        providerSetting: ProviderSetting.Dsh,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): MessageChunk {
        // 非流式：收集 streamText 的完整结果
        var fullText = ""
        streamText(providerSetting, messages, params).collect { chunk ->
            val delta = chunk.choices.getOrNull(0)?.delta
            delta?.parts?.filterIsInstance<UIMessagePart.Text>()?.forEach { fullText += it.text }
        }
        return MessageChunk(
            id = Uuid.random().toString(),
            model = params.model.modelId,
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    delta = UIMessage.assistant(fullText),
                    message = null,
                    finishReason = "stop",
                )
            ),
        )
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.Dsh,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<MessageChunk> = callbackFlow {
        val modelId = params.model.modelId
        // 1. 获取/创建 DSH 会话
        val sessionId = ensureSession(providerSetting, modelId)

        // 2. 提取最新一条用户文本消息
        val userText = messages.lastOrNull { it.role == me.rerere.ai.core.MessageRole.USER }
            ?.parts
            ?.filterIsInstance<UIMessagePart.Text>()
            ?.joinToString("\n") { it.text }
            ?.trim()
            ?: ""
        if (userText.isEmpty()) {
            close(IllegalStateException("No user text message to send to DSH"))
            return@callbackFlow
        }

        // 3. 发送消息
        val promptPayload = buildJsonObject {
            put("sessionId", sessionId)
            put("mode", "queue")
            putJsonArray("content") {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", userText)
                })
            }
        }
        val promptResult = requireOk(callRpc(providerSetting.baseUrl, "session.prompt", promptPayload), "session.prompt")
        // 斜杠命令：命令直接返回成功文本，无 agent 回合
        val cmd = promptResult["command"]?.jsonObject
        if (cmd != null) {
            val text = cmd["text"]?.jsonPrimitive?.contentOrNull
            if (!text.isNullOrBlank()) {
                trySend(textChunk(params, text))
            }
            close()
            return@callbackFlow
        }

        // 4. 连接 WebSocket events.mux，接收当前会话的流式事件
        val wsUrl = wsUrl(providerSetting.baseUrl, "mux")
        val wsRequest = Request.Builder().url(wsUrl).build()
        var ws: WebSocket? = null
        val done = java.util.concurrent.atomic.AtomicBoolean(false)

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "events.mux connected")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val root = json.parseToJsonElement(text).jsonObject
                    // ServerRequest 帧：{type:"server-request", method:"session/event", payload:{sessionId,event}}
                    if (root["method"]?.jsonPrimitive?.contentOrNull != "session/event") return
                    val payload = root["payload"]?.jsonObject ?: return
                    val frameSessionId = payload["sessionId"]?.jsonPrimitive?.contentOrNull ?: return
                    if (frameSessionId != sessionId) return
                    val event = payload["event"]?.jsonObject ?: return
                    val eventType = event["type"]?.jsonPrimitive?.contentOrNull ?: return

                    when (eventType) {
                        "assistant/chunk" -> {
                            val chunk = event["data"]?.jsonObject?.get("chunk")?.jsonObject ?: return
                            val chunkType = chunk["type"]?.jsonPrimitive?.contentOrNull ?: return
                            // 只取正式文本 token（text-delta），跳过推理（reasoning-delta）
                            if (chunkType == "text-delta") {
                                val text = chunk["text"]?.jsonPrimitive?.contentOrNull ?: ""
                                if (text.isNotEmpty()) {
                                    trySend(textChunk(params, text))
                                }
                            }
                        }
                        "turn/end", "finish" -> {
                            // 回合结束，发射结束信号
                            trySend(
                                MessageChunk(
                                    id = Uuid.random().toString(),
                                    model = params.model.modelId,
                                    choices = listOf(
                                        UIMessageChoice(
                                            index = 0,
                                            delta = null,
                                            message = null,
                                            finishReason = "stop",
                                        )
                                    ),
                                )
                            )
                            if (done.compareAndSet(false, true)) {
                                close()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "onMessage parse error", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "events.mux failure", t)
                if (done.compareAndSet(false, true)) {
                    close(t)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (done.compareAndSet(false, true)) {
                    close()
                }
            }
        }

        ws = client.newWebSocket(wsRequest, listener)

        awaitClose {
            if (done.compareAndSet(false, true)) {
                ws?.close(1000, "client done")
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun textChunk(params: TextGenerationParams, text: String): MessageChunk {
        return MessageChunk(
            id = Uuid.random().toString(),
            model = params.model.modelId,
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    delta = UIMessage(
                        role = me.rerere.ai.core.MessageRole.ASSISTANT,
                        parts = listOf(UIMessagePart.Text(text)),
                    ),
                    message = null,
                    finishReason = null,
                )
            ),
        )
    }
}
