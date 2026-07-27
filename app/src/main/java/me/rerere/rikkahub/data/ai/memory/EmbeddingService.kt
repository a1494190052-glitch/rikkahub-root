package me.rerere.rikkahub.data.ai.memory

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.SettingsStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "EmbeddingService"
private const val MAX_RETRIES = 3
private const val RETRY_DELAY_MS = 1000L
private const val MAX_CACHE_SIZE = 512

/**
 * Embedding 后端策略
 */
enum class EmbeddingBackend {
    /** 仅使用本地 ONNX 模型 */
    LOCAL,
    /** 仅使用远程 OpenAI 兼容 API */
    REMOTE,
    /** 自动：本地优先，远程兜底 */
    AUTO,
}

/**
 * 统一 Embedding 服务 — 策略模式
 *
 * 支持三种后端：
 * - LOCAL: 本地 ONNX Runtime 推理（离线可用，384 维）
 * - REMOTE: OpenAI 兼容 API（需网络，1536 维）
 * - AUTO: 本地优先，本地不可用时自动降级到远程
 *
 * 注意：本地和远程产出的向量维度不同（384 vs 1536），
 * 搜索时 SemanticMemoryManager 会跳过维度不匹配的向量比较。
 */
class EmbeddingService(
    private val okHttpClient: OkHttpClient,
    private val settingsStore: SettingsStore,
    private val json: Json,
    private val localEmbedding: LocalEmbeddingService? = null,
) {
    // 简单 LRU 缓存：text hash -> embedding
    private val embeddingCache = ConcurrentHashMap<Int, FloatArray>()

    /** 当前使用的后端策略 */
    @Volatile
    var backend: EmbeddingBackend = EmbeddingBackend.AUTO

    /** 当前实际使用的向量维度（用于外部判断） */
    val activeDimension: Int
        get() = when {
            backend == EmbeddingBackend.LOCAL -> LocalEmbeddingService.MODEL_DIMENSION
            backend == EmbeddingBackend.REMOTE -> REMOTE_DIMENSION
            // AUTO: 本地可用则 384，否则 1536
            localEmbedding?.isAvailable() == true -> LocalEmbeddingService.MODEL_DIMENSION
            else -> REMOTE_DIMENSION
        }

    /**
     * 获取第一个可用的 OpenAI 兼容 provider 配置
     */
    private fun getEmbeddingProviderConfig(): Pair<String, String>? {
        val settings = settingsStore.settingsFlow.value
        // 优先查找 OpenAI 类型的 provider（支持 /v1/embeddings）
        val provider = settings.providers
            .filterIsInstance<ProviderSetting.OpenAI>()
            .firstOrNull { it.enabled && it.apiKey.isNotBlank() }
            ?: return null
        return Pair(provider.baseUrl.trimEnd('/'), provider.apiKey)
    }

    /**
     * 生成单条文本的嵌入向量
     * @param text 输入文本
     * @param model 嵌入模型名称（仅远程模式使用）
     * @return 嵌入向量，失败返回 null
     */
    suspend fun embed(text: String, model: String = DEFAULT_MODEL): FloatArray? {
        return embedBatch(listOf(text), model).firstOrNull()
    }

    /**
     * 批量生成嵌入向量
     * @param texts 输入文本列表
     * @param model 嵌入模型名称（仅远程模式使用）
     * @return 嵌入向量列表（与输入顺序对应），失败的条目为 null
     */
    suspend fun embedBatch(texts: List<String>, model: String = DEFAULT_MODEL): List<FloatArray?> {
        if (texts.isEmpty()) return emptyList()

        val results = arrayOfNulls<FloatArray>(texts.size)
        val uncachedIndices = mutableListOf<Int>()
        val uncachedTexts = mutableListOf<String>()

        // 检查缓存
        texts.forEachIndexed { index, text ->
            val cacheKey = text.hashCode()
            val cached = embeddingCache[cacheKey]
            if (cached != null) {
                results[index] = cached
            } else {
                uncachedIndices.add(index)
                uncachedTexts.add(text)
            }
        }

        if (uncachedTexts.isEmpty()) return results.toList()

        // 根据后端策略选择推理路径
        val embeddings = when (backend) {
            EmbeddingBackend.LOCAL -> embedLocalBatch(uncachedTexts)
            EmbeddingBackend.REMOTE -> callEmbeddingApi(uncachedTexts, model)
            EmbeddingBackend.AUTO -> {
                // 本地优先，远程兜底
                val localResult = if (localEmbedding?.isAvailable() == true) {
                    embedLocalBatch(uncachedTexts)
                } else {
                    null
                }
                // 如果本地全部成功，直接用；否则对失败的条目尝试远程
                if (localResult != null && localResult.all { it != null }) {
                    localResult
                } else if (localResult != null) {
                    // 部分失败，对失败的尝试远程
                    val failedIndices = localResult.indices.filter { localResult[it] == null }
                    if (failedIndices.isNotEmpty()) {
                        val failedTexts = failedIndices.map { uncachedTexts[it] }
                        val remoteResult = callEmbeddingApi(failedTexts, model)
                        if (remoteResult != null) {
                            val merged = localResult.toMutableList()
                            failedIndices.forEachIndexed { i, origIdx ->
                                merged[origIdx] = remoteResult.getOrNull(i)
                            }
                            merged
                        } else {
                            localResult
                        }
                    } else {
                        localResult
                    }
                } else {
                    // 本地完全不可用，走远程
                    callEmbeddingApi(uncachedTexts, model)
                }
            }
        }

        if (embeddings != null) {
            embeddings.forEachIndexed { i, embedding ->
                if (embedding != null) {
                    val originalIndex = uncachedIndices[i]
                    results[originalIndex] = embedding
                    // 存入缓存
                    val cacheKey = uncachedTexts[i].hashCode()
                    if (embeddingCache.size >= MAX_CACHE_SIZE) {
                        // 简单清理：移除一半缓存
                        val keysToRemove = embeddingCache.keys.take(MAX_CACHE_SIZE / 2)
                        keysToRemove.forEach { embeddingCache.remove(it) }
                    }
                    embeddingCache[cacheKey] = embedding
                }
            }
        }

        return results.toList()
    }

    /**
     * 本地 ONNX 批量推理
     */
    private suspend fun embedLocalBatch(texts: List<String>): List<FloatArray?>? {
        val local = localEmbedding ?: return null
        if (!local.isAvailable()) return null
        return try {
            local.embedBatch(texts)
        } catch (e: Exception) {
            Log.w(TAG, "Local embedding failed, will fallback", e)
            null
        }
    }

    /**
     * 调用 OpenAI 兼容的 /v1/embeddings API
     */
    private suspend fun callEmbeddingApi(texts: List<String>, model: String): List<FloatArray?>? {
        val config = getEmbeddingProviderConfig()
        if (config == null) {
            Log.w(TAG, "No available OpenAI-compatible provider for embeddings")
            return null
        }
        val (baseUrl, apiKey) = config

        // 构建请求体
        val requestBody = buildJsonObject {
            put("model", model)
            put("input", buildJsonArray {
                texts.forEach { text ->
                    add(JsonPrimitive(text))
                }
            })
        }.toString()

        val url = "$baseUrl/embeddings"
        Log.d(TAG, "Calling embeddings API: $url, model=$model, texts=${texts.size}")

        var lastException: Exception? = null
        for (attempt in 1..MAX_RETRIES) {
            try {
                val result = withContext(Dispatchers.IO) {
                    val request = Request.Builder()
                        .url(url)
                        .addHeader("Authorization", "Bearer $apiKey")
                        .addHeader("Content-Type", "application/json")
                        .post(requestBody.toRequestBody("application/json".toMediaType()))
                        .build()

                    okHttpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            val errorBody = response.body?.string() ?: "no body"
                            throw EmbeddingException("API error ${response.code}: $errorBody")
                        }
                        val body = response.body?.string()
                            ?: throw EmbeddingException("Empty response body")
                        parseEmbeddingResponse(body)
                    }
                }
                return result
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "Embedding API attempt $attempt failed: ${e.message}")
                if (attempt < MAX_RETRIES) {
                    delay(RETRY_DELAY_MS * attempt)
                }
            }
        }

        Log.e(TAG, "All embedding API attempts failed", lastException)
        return null
    }

    /**
     * 解析 OpenAI embeddings API 响应
     * 格式: {"data": [{"embedding": [0.1, 0.2, ...], "index": 0}, ...]}
     */
    private fun parseEmbeddingResponse(body: String): List<FloatArray?> {
        val responseJson = json.parseToJsonElement(body).jsonObject
        val dataArray = responseJson["data"]?.jsonArray
            ?: throw EmbeddingException("No 'data' field in response")

        val results = arrayOfNulls<FloatArray>(dataArray.size)
        for (item in dataArray) {
            val obj = item.jsonObject
            val index = obj["index"]?.jsonPrimitive?.int ?: continue
            val embeddingArray = obj["embedding"]?.jsonArray ?: continue
            val floats = FloatArray(embeddingArray.size) { i ->
                embeddingArray[i].jsonPrimitive.float
            }
            if (index in results.indices) {
                results[index] = floats
            }
        }
        return results.toList()
    }

    /**
     * 清除缓存
     */
    fun clearCache() {
        embeddingCache.clear()
    }

    companion object {
        const val DEFAULT_MODEL = "text-embedding-3-small"
        const val REMOTE_DIMENSION = 1536
    }
}

class EmbeddingException(message: String) : Exception(message)
