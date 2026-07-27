package me.rerere.rikkahub.data.ai.memory

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.LongBuffer
import kotlin.math.min

private const val TAG = "LocalEmbeddingEngine"
private const val MODEL_FILE_NAME = "embedding.onnx"
private const val VOCAB_FILE_NAME = "vocab.txt"
private const val MAX_SEQUENCE_LENGTH = 128
private const val EMBEDDING_DIM = 384

/**
 * 本地 ONNX Runtime Embedding 引擎
 * 支持 all-MiniLM-L6-v2 模型（384维），完全离线运行
 *
 * 模型文件路径: context.filesDir/models/embedding.onnx
 * 词表文件路径: context.filesDir/models/vocab.txt
 *
 * 如果模型文件不存在，所有方法 graceful 返回 null / empty
 */
class LocalEmbeddingEngine(private val context: Context) {

    private var ortEnvironment: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var vocab: Map<String, Int> = emptyMap()
    private val mutex = Mutex()
    private var initialized = false
    private var initFailed = false

    private val modelFile: File
        get() = File(context.filesDir, "models/$MODEL_FILE_NAME")

    private val vocabFile: File
        get() = File(context.filesDir, "models/$VOCAB_FILE_NAME")

    /**
     * 检查本地模型是否可用（模型文件 + 词表文件都存在）
     */
    fun isModelAvailable(): Boolean {
        return modelFile.exists() && vocabFile.exists()
    }

    /**
     * 初始化 ONNX Runtime 会话和词表
     * @return true 如果初始化成功
     */
    suspend fun initialize(): Boolean = mutex.withLock {
        if (initialized) return true
        if (initFailed) return false
        if (!isModelAvailable()) {
            Log.w(TAG, "Model files not found at ${modelFile.absolutePath}")
            initFailed = true
            return false
        }

        try {
            withContext(Dispatchers.IO) {
                // 加载词表
                vocab = loadVocab(vocabFile)
                Log.d(TAG, "Loaded vocab with ${vocab.size} tokens")

                // 初始化 ONNX Runtime
                ortEnvironment = OrtEnvironment.getEnvironment()
                val sessionOptions = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(2) // 手机端限制线程数
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                }
                ortSession = ortEnvironment!!.createSession(modelFile.absolutePath, sessionOptions)
                Log.i(TAG, "ONNX session created for model: ${modelFile.absolutePath}")
            }
            initialized = true
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ONNX Runtime", e)
            initFailed = true
            close()
            return false
        }
    }

    /**
     * 生成单条文本的嵌入向量
     * @param text 输入文本
     * @return 384维嵌入向量，失败返回 null
     */
    suspend fun embed(text: String): FloatArray? {
        return embedBatch(listOf(text)).firstOrNull()
    }

    /**
     * 批量生成嵌入向量
     * @param texts 输入文本列表
     * @return 嵌入向量列表，失败返回空列表
     */
    suspend fun embedBatch(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        if (!initialize()) {
            Log.w(TAG, "Engine not initialized, cannot embed")
            return emptyList()
        }

        return withContext(Dispatchers.Default) {
            try {
                texts.map { text -> runInference(text) }
            } catch (e: Exception) {
                Log.e(TAG, "Batch embedding failed", e)
                emptyList()
            }
        }
    }

    /**
     * 执行单条推理
     */
    private fun runInference(text: String): FloatArray {
        val env = ortEnvironment ?: throw IllegalStateException("ORT environment not initialized")
        val session = ortSession ?: throw IllegalStateException("ORT session not initialized")

        // Tokenize
        val tokens = tokenize(text)
        val seqLen = tokens.size.toLong()

        // 构建输入张量 [1, seqLen]
        val inputIds = LongBuffer.wrap(tokens.toLongArray())
        val attentionMask = LongBuffer.wrap(LongArray(tokens.size) { 1L })
        val tokenTypeIds = LongBuffer.wrap(LongArray(tokens.size) { 0L })

        val shape = longArrayOf(1, seqLen)

        val inputIdsTensor = OnnxTensor.createTensor(env, inputIds, shape)
        val attentionMaskTensor = OnnxTensor.createTensor(env, attentionMask, shape)
        val tokenTypeIdsTensor = OnnxTensor.createTensor(env, tokenTypeIds, shape)

        try {
            val inputs = mapOf(
                "input_ids" to inputIdsTensor,
                "attention_mask" to attentionMaskTensor,
                "token_type_ids" to tokenTypeIdsTensor,
            )

            session.run(inputs).use { results ->
                // 输出: last_hidden_state [1, seqLen, 384]
                // 使用 mean pooling (带 attention mask)
                val output = results[0].value as Array<Array<FloatArray>>
                val hiddenStates = output[0] // [seqLen, 384]

                // Mean pooling: 对非 padding token 的 hidden states 取平均
                val embedding = FloatArray(EMBEDDING_DIM)
                var count = 0
                for (i in hiddenStates.indices) {
                    // 跳过 [CLS] (index 0) 和 [SEP] (最后一个)
                    if (i == 0 || i == hiddenStates.size - 1) continue
                    for (d in 0 until EMBEDDING_DIM) {
                        embedding[d] += hiddenStates[i][d]
                    }
                    count++
                }
                if (count > 0) {
                    for (d in 0 until EMBEDDING_DIM) {
                        embedding[d] /= count
                    }
                }

                // L2 归一化
                return VectorUtils.normalize(embedding)
            }
        } finally {
            inputIdsTensor.close()
            attentionMaskTensor.close()
            tokenTypeIdsTensor.close()
        }
    }

    // ==================== WordPiece Tokenizer ====================

    /**
     * 简单 WordPiece 分词器
     * 输出: [CLS] tokens [SEP]，截断到 MAX_SEQUENCE_LENGTH
     */
    private fun tokenize(text: String): List<Int> {
        val clsId = vocab["[CLS]"] ?: 101
        val sepId = vocab["[SEP]"] ?: 102
        val unkId = vocab["[UNK]"] ?: 100

        val tokens = mutableListOf<Int>()
        tokens.add(clsId)

        // 基本分词：按空白和标点分割，中文按字分割
        val words = basicTokenize(text)
        for (word in words) {
            val wordPieceIds = wordPieceTokenize(word, unkId)
            tokens.addAll(wordPieceIds)
            if (tokens.size >= MAX_SEQUENCE_LENGTH - 1) break
        }

        // 截断并添加 [SEP]
        val maxTokens = min(tokens.size, MAX_SEQUENCE_LENGTH - 1)
        val result = tokens.subList(0, maxTokens).toMutableList()
        result.add(sepId)
        return result
    }

    /**
     * 基本分词：空白分割 + 中文按字分割 + 小写化
     */
    private fun basicTokenize(text: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()

        for (char in text.lowercase()) {
            when {
                char.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        result.add(current.toString())
                        current.clear()
                    }
                }
                isCjkCharacter(char) || isPunctuation(char) -> {
                    if (current.isNotEmpty()) {
                        result.add(current.toString())
                        current.clear()
                    }
                    result.add(char.toString())
                }
                else -> current.append(char)
            }
        }
        if (current.isNotEmpty()) {
            result.add(current.toString())
        }
        return result
    }

    /**
     * WordPiece 子词分词
     */
    private fun wordPieceTokenize(word: String, unkId: Int): List<Int> {
        val result = mutableListOf<Int>()
        var start = 0

        while (start < word.length) {
            var end = word.length
            var foundId: Int? = null

            while (start < end) {
                val substr = if (start == 0) word.substring(start, end)
                else "##" + word.substring(start, end)

                val id = vocab[substr]
                if (id != null) {
                    foundId = id
                    break
                }
                end--
            }

            if (foundId == null) {
                result.add(unkId)
                break
            }
            result.add(foundId)
            start = end
        }
        return result
    }

    private fun isCjkCharacter(c: Char): Boolean {
        val cp = c.code
        return (cp in 0x4E00..0x9FFF) ||
                (cp in 0x3400..0x4DBF) ||
                (cp in 0x20000..0x2A6DF) ||
                (cp in 0x2A700..0x2B73F) ||
                (cp in 0x2B740..0x2B81F) ||
                (cp in 0x2B820..0x2CEAF) ||
                (cp in 0xF900..0xFAFF) ||
                (cp in 0x2F800..0x2FA1F) ||
                (cp in 0x3000..0x303F) ||  // CJK 标点
                (cp in 0x3040..0x309F) ||  // 日文平假名
                (cp in 0x30A0..0x30FF) ||  // 日文片假名
                (cp in 0xAC00..0xD7AF)     // 韩文
    }

    private fun isPunctuation(c: Char): Boolean {
        val cp = c.code
        if ((cp in 33..47) || (cp in 58..64) || (cp in 91..96) || (cp in 123..126)) return true
        return c.category.let {
            it == CharCategory.CONNECTOR_PUNCTUATION ||
                    it == CharCategory.DASH_PUNCTUATION ||
                    it == CharCategory.END_PUNCTUATION ||
                    it == CharCategory.FINAL_QUOTE_PUNCTUATION ||
                    it == CharCategory.INITIAL_QUOTE_PUNCTUATION ||
                    it == CharCategory.OTHER_PUNCTUATION ||
                    it == CharCategory.START_PUNCTUATION
        }
    }

    /**
     * 加载 WordPiece 词表文件 (每行一个 token)
     */
    private fun loadVocab(file: File): Map<String, Int> {
        val vocabMap = mutableMapOf<String, Int>()
        file.bufferedReader().useLines { lines ->
            lines.forEachIndexed { index, line ->
                val token = line.trim()
                if (token.isNotEmpty()) {
                    vocabMap[token] = index
                }
            }
        }
        return vocabMap
    }

    /**
     * 释放资源
     */
    fun close() {
        try {
            ortSession?.close()
            // OrtEnvironment 是全局单例，不需要关闭
        } catch (e: Exception) {
            Log.w(TAG, "Error closing ORT session", e)
        }
        ortSession = null
        initialized = false
    }

    companion object {
        /** 本地模型输出维度 */
        const val DIMENSION = EMBEDDING_DIM
    }
}
