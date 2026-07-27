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
 * OpenAI 兼容的 Embedding API 调用服务
 * 使用项目已有的 OkHttpClient，支持批量嵌入、重试、缓存
 */
class EmbeddingService(
    private val okHttpClient: OkHttpClient,
    private val settingsStore: SettingsStore,
    private val json: Json,
) {
    // 简单 LRU 缓存：text hash -> embedding
    private val embeddingCache = ConcurrentHashMap<Int, FloatArray>()

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
     * @param model 嵌入模型名称（默认 text-embedding-3-small）
     * @return 嵌入向量，失败返回 null
     */
    suspend fun embed(text: String, model: String = DEFAULT_MODEL): FloatArray? {
        return embedBatch(listOf(text), model).firstOrNull()
    }

    /**
     * 批量生成嵌入向量
     * @param texts 输入文本列表
     * @param model 嵌入模型名称
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

        // 调用 API
        val embeddings = callEmbeddingApi(uncachedTexts, model)
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
    }
}

class EmbeddingException(message: String) : Exception(message)
