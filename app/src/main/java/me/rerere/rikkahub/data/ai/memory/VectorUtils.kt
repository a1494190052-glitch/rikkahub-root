package me.rerere.rikkahub.data.ai.memory

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * 向量工具类：FloatArray ↔ ByteArray 转换、余弦相似度、归一化
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
}
