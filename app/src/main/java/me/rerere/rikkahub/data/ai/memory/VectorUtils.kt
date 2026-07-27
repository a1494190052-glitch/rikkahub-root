package me.rerere.rikkahub.data.ai.memory

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 向量工具类：FloatArray ↔ ByteArray 转换、余弦相似度、归一化、int8 量化
 */
object VectorUtils {

    /**
     * FloatArray → ByteArray (little-endian IEEE 754)
     */
    fun floatArrayToByteArray(floats: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        floats.forEach { buffer.putFloat(it) }
        return buffer.array()
    }

    /**
     * ByteArray → FloatArray (little-endian IEEE 754)
     */
    fun byteArrayToFloatArray(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val floats = FloatArray(bytes.size / 4)
        for (i in floats.indices) {
            floats[i] = buffer.getFloat()
        }
        return floats
    }

    /**
     * 余弦相似度: cos(θ) = (A·B) / (|A| * |B|)
     * 返回值范围 [-1, 1]，1 表示完全相同方向
     */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "Vector dimensions must match: ${a.size} vs ${b.size}" }
        if (a.isEmpty()) return 0f

        var dotProduct = 0.0
        var normA = 0.0
        var normB = 0.0

        for (i in a.indices) {
            dotProduct += a[i].toDouble() * b[i].toDouble()
            normA += a[i].toDouble() * a[i].toDouble()
            normB += b[i].toDouble() * b[i].toDouble()
        }

        val denominator = sqrt(normA) * sqrt(normB)
        if (denominator == 0.0) return 0f

        return (dotProduct / denominator).toFloat()
    }

    /**
     * 向量归一化 (L2 norm)
     */
    fun normalize(vector: FloatArray): FloatArray {
        val norm = sqrt(vector.sumOf { (it * it).toDouble() }).toFloat()
        if (norm == 0f) return vector.copyOf()
        return FloatArray(vector.size) { vector[it] / norm }
    }

    // ==================== Int8 量化 ====================

    /**
     * 将浮点向量量化为 int8 (ByteArray)
     * 使用对称量化: quantized = round(value / scale)
     * 存储格式: [4 bytes scale (float LE)] + [N bytes int8 values]
     * 体积缩小约 75% (384*4=1536 bytes → 4+384=388 bytes)
     *
     * @param v 输入浮点向量
     * @return 量化后的字节数组
     */
    fun quantize(v: FloatArray): ByteArray {
        if (v.isEmpty()) return ByteArray(4) // 仅 scale=0

        // 计算 scale: 使最大绝对值映射到 127
        val maxAbs = v.maxOf { kotlin.math.abs(it) }
        val scale = if (maxAbs > 0f) maxAbs / 127f else 1f

        // 4 bytes scale + N bytes quantized values
        val result = ByteArray(4 + v.size)
        val buffer = ByteBuffer.wrap(result, 0, 4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putFloat(scale)

        for (i in v.indices) {
            val quantized = (v[i] / scale).toInt().coerceIn(-128, 127)
            result[4 + i] = quantized.toByte()
        }

        return result
    }

    /**
     * 将 int8 量化字节数组反量化为浮点向量
     *
     * @param b 量化字节数组 (格式: [4 bytes scale] + [N bytes int8])
     * @return 反量化后的浮点向量
     */
    fun dequantize(b: ByteArray): FloatArray {
        if (b.size <= 4) return FloatArray(0)

        val buffer = ByteBuffer.wrap(b, 0, 4).order(ByteOrder.LITTLE_ENDIAN)
        val scale = buffer.getFloat()

        val size = b.size - 4
        val result = FloatArray(size)
        for (i in 0 until size) {
            result[i] = b[4 + i].toInt() * scale
        }
        return result
    }

    /**
     * 直接在量化域计算余弦相似度（避免反量化开销）
     * 对于对称量化，scale 在点积和范数中会约掉：
     * cos(A,B) = (sum(a_i * b_i)) / (sqrt(sum(a_i^2)) * sqrt(sum(b_i^2)))
     * 其中 a_i, b_i 是 int8 值（scale 相同维度上约分）
     *
     * 注意：两个向量的 scale 可能不同，但由于余弦相似度对缩放不变，
     * 直接用 int8 值计算即可得到正确结果。
     *
     * @param qa 量化向量 A
     * @param qb 量化向量 B
     * @return 余弦相似度 [-1, 1]
     */
    fun cosineSimilarityQuantized(qa: ByteArray, qb: ByteArray): Float {
        require(qa.size == qb.size) { "Quantized vector sizes must match: ${qa.size} vs ${qb.size}" }
        if (qa.size <= 4) return 0f

        val size = qa.size - 4
        var dotProduct = 0.0
        var normA = 0.0
        var normB = 0.0

        for (i in 0 until size) {
            val a = qa[4 + i].toInt().toDouble()
            val b = qb[4 + i].toInt().toDouble()
            dotProduct += a * b
            normA += a * a
            normB += b * b
        }

        val denominator = sqrt(normA) * sqrt(normB)
        if (denominator == 0.0) return 0f

        return (dotProduct / denominator).toFloat()
    }

    /**
     * 混合余弦相似度：一个浮点向量 vs 一个量化向量
     * 先反量化再计算（用于兼容新旧数据混合场景）
     *
     * @param floatVec 浮点向量
     * @param quantizedVec 量化向量
     * @return 余弦相似度 [-1, 1]
     */
    fun cosineSimilarityMixed(floatVec: FloatArray, quantizedVec: ByteArray): Float {
        val dequantized = dequantize(quantizedVec)
        if (floatVec.size != dequantized.size) return 0f
        return cosineSimilarity(floatVec, dequantized)
    }

    /**
     * 计算两个向量的欧几里得距离
     */
    fun euclideanDistance(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "Vector dimensions must match" }
        return sqrt(a.indices.sumOf { i ->
            val diff = (a[i] - b[i]).toDouble()
            diff * diff
        }).toFloat()
    }

    /**
     * 检测字节数组是否为量化格式（通过长度启发式判断）
     * 量化格式: 4 + N bytes (N = 维度数)
     * 浮点格式: N * 4 bytes
     * 对于 384 维: 量化 = 388 bytes, 浮点 = 1536 bytes
     */
    fun isQuantizedFormat(bytes: ByteArray, expectedDim: Int = 384): Boolean {
        return bytes.size == 4 + expectedDim
    }
}
