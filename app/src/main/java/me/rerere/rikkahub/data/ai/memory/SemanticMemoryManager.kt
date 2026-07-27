package me.rerere.rikkahub.data.ai.memory

import android.util.Log
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.entity.MemoryEntity

private const val TAG = "SemanticMemoryManager"
private const val DEFAULT_TOP_K = 5
private const val CONSOLIDATION_SIMILARITY_THRESHOLD = 0.92f

/**
 * 语义记忆搜索结果
 */
data class SemanticSearchResult(
    val memory: MemoryEntity,
    val score: Float,
)

/**
 * 核心语义记忆管理器，协调嵌入生成、存储、检索
 */
class SemanticMemoryManager(
    private val memoryDAO: MemoryDAO,
    private val embeddingService: EmbeddingService,
) {

    /**
     * 创建记忆 + 生成嵌入向量
     * @return 新创建的记忆实体（含 ID）
     */
    suspend fun addMemory(
        assistantId: String,
        content: String,
        importance: Int = 0,
        tags: String = "",
        source: String = "manual",
    ): MemoryEntity {
        val now = System.currentTimeMillis()
        val embedding = embeddingService.embed(content)
        val embeddingBytes = embedding?.let { VectorUtils.floatArrayToByteArray(it) }

        val entity = MemoryEntity(
            assistantId = assistantId,
            content = content,
            embedding = embeddingBytes,
            importance = importance,
            createdAt = now,
            updatedAt = now,
            accessCount = 0,
            lastAccessed = 0,
            tags = tags,
            source = source,
        )

        val id = memoryDAO.insertMemory(entity).toInt()
        Log.d(TAG, "Added memory #$id for assistant=$assistantId, hasEmbedding=${embedding != null}")
        return entity.copy(id = id)
    }

    /**
     * 更新记忆内容 + 重新生成嵌入
     */
    suspend fun updateMemory(id: Int, content: String): MemoryEntity? {
        val existing = memoryDAO.getMemoryById(id) ?: return null
        val embedding = embeddingService.embed(content)
        val embeddingBytes = embedding?.let { VectorUtils.floatArrayToByteArray(it) }

        val updated = existing.copy(
            content = content,
            embedding = embeddingBytes,
            updatedAt = System.currentTimeMillis(),
        )
        memoryDAO.updateMemory(updated)
        Log.d(TAG, "Updated memory #$id, hasEmbedding=${embedding != null}")
        return updated
    }

    /**
     * 语义搜索：根据查询文本找到最相关的记忆
     * @param assistantId 助手 ID
     * @param query 查询文本
     * @param topK 返回前 K 个结果
     * @return 按相似度降序排列的结果列表
     */
    suspend fun searchMemories(
        assistantId: String,
        query: String,
        topK: Int = DEFAULT_TOP_K,
    ): List<SemanticSearchResult> {
        val queryEmbedding = embeddingService.embed(query)
        if (queryEmbedding == null) {
            Log.w(TAG, "Failed to embed query, falling back to recency")
            return memoryDAO.getRecentMemories(assistantId, topK).map {
                SemanticSearchResult(memory = it, score = 0f)
            }
        }

        val queryDim = queryEmbedding.size
        val memoriesWithEmbedding = memoryDAO.getMemoriesWithEmbedding(assistantId)
        if (memoriesWithEmbedding.isEmpty()) {
            return emptyList()
        }

        return memoriesWithEmbedding
            .mapNotNull { memory ->
                val memEmbedding = memory.embedding?.let { VectorUtils.byteArrayToFloatArray(it) }
                    ?: return@mapNotNull null
                // 维度兼容：跳过维度不匹配的向量（本地 384 vs 远程 1536）
                if (memEmbedding.size != queryDim) {
                    return@mapNotNull null
                }
                val score = VectorUtils.cosineSimilarity(queryEmbedding, memEmbedding)
                SemanticSearchResult(memory = memory, score = score)
            }
            .sortedByDescending { it.score }
            .take(topK)
            .also { results ->
                // 更新访问计数
                results.forEach { result ->
                    memoryDAO.incrementAccessCount(result.memory.id)
                }
            }
    }

    /**
     * 根据对话上下文自动检索相关记忆
     * 结合语义相似度和重要性进行综合排序
     * @param assistantId 助手 ID
     * @param context 对话上下文文本
     * @param topK 返回前 K 个结果
     */
    suspend fun getRelevantMemories(
        assistantId: String,
        context: String,
        topK: Int = DEFAULT_TOP_K,
    ): List<SemanticSearchResult> {
        val contextEmbedding = embeddingService.embed(context)
        if (contextEmbedding == null) {
            // 回退：返回最重要的记忆
            return memoryDAO.getImportantMemories(assistantId, 0).take(topK).map {
                SemanticSearchResult(memory = it, score = 0f)
            }
        }

        val contextDim = contextEmbedding.size
        val memoriesWithEmbedding = memoryDAO.getMemoriesWithEmbedding(assistantId)
        if (memoriesWithEmbedding.isEmpty()) {
            return emptyList()
        }

        return memoriesWithEmbedding
            .mapNotNull { memory ->
                val memEmbedding = memory.embedding?.let { VectorUtils.byteArrayToFloatArray(it) }
                    ?: return@mapNotNull null
                // 维度兼容：跳过维度不匹配的向量
                if (memEmbedding.size != contextDim) {
                    return@mapNotNull null
                }
                val semanticScore = VectorUtils.cosineSimilarity(contextEmbedding, memEmbedding)
                // 综合分数：语义相似度 * 0.7 + 重要性归一化 * 0.2 + 新鲜度 * 0.1
                val importanceScore = memory.importance / 5.0f
                val ageDays = (System.currentTimeMillis() - memory.createdAt) / (1000.0 * 60 * 60 * 24)
                val freshnessScore = (1.0 / (1.0 + ageDays / 30.0)).toFloat()
                val combinedScore = semanticScore * 0.7f + importanceScore * 0.2f + freshnessScore * 0.1f
                SemanticSearchResult(memory = memory, score = combinedScore)
            }
            .sortedByDescending { it.score }
            .take(topK)
            .also { results ->
                results.forEach { result ->
                    memoryDAO.incrementAccessCount(result.memory.id)
                }
            }
    }

    /**
     * 合并相似记忆：找到高度相似的记忆对，合并为一条
     * @return 被合并删除的记忆数量
     */
    suspend fun consolidateMemories(assistantId: String): Int {
        val memories = memoryDAO.getMemoriesWithEmbedding(assistantId)
        if (memories.size < 2) return 0

        val toDelete = mutableSetOf<Int>()
        val mergedContents = mutableMapOf<Int, String>() // keepId -> merged content

        // 找到所有高度相似的记忆对
        for (i in memories.indices) {
            if (memories[i].id in toDelete) continue
            val embeddingI = memories[i].embedding?.let { VectorUtils.byteArrayToFloatArray(it) }
                ?: continue

            for (j in i + 1 until memories.size) {
                if (memories[j].id in toDelete) continue
                val embeddingJ = memories[j].embedding?.let { VectorUtils.byteArrayToFloatArray(it) }
                    ?: continue

                // 维度兼容：只比较同维度向量
                if (embeddingI.size != embeddingJ.size) continue

                val similarity = VectorUtils.cosineSimilarity(embeddingI, embeddingJ)
                if (similarity >= CONSOLIDATION_SIMILARITY_THRESHOLD) {
                    // 保留较旧/较重要的那条，合并内容
                    val keep = if (memories[i].importance >= memories[j].importance) memories[i] else memories[j]
                    val remove = if (keep.id == memories[i].id) memories[j] else memories[i]

                    toDelete.add(remove.id)
                    val existingMerged = mergedContents[keep.id] ?: keep.content
                    if (!existingMerged.contains(remove.content)) {
                        mergedContents[keep.id] = "$existingMerged\n${remove.content}"
                    }
                }
            }
        }

        // 执行合并
        var deletedCount = 0
        for (id in toDelete) {
            memoryDAO.deleteMemory(id)
            deletedCount++
        }

        // 更新合并后的内容
        for ((keepId, mergedContent) in mergedContents) {
            val existing = memoryDAO.getMemoryById(keepId) ?: continue
            val embedding = embeddingService.embed(mergedContent)
            val embeddingBytes = embedding?.let { VectorUtils.floatArrayToByteArray(it) }
            memoryDAO.updateMemory(
                existing.copy(
                    content = mergedContent,
                    embedding = embeddingBytes,
                    updatedAt = System.currentTimeMillis(),
                    source = "consolidated",
                )
            )
        }

        Log.i(TAG, "Consolidated $deletedCount memories for assistant=$assistantId")
        return deletedCount
    }

    /**
     * 为缺少嵌入的记忆批量生成嵌入（后台任务）
     * @return 成功生成嵌入的数量
     */
    suspend fun backfillEmbeddings(assistantId: String): Int {
        val memories = memoryDAO.getMemoriesOfAssistant(assistantId)
        val withoutEmbedding = memories.filter { it.embedding == null && it.content.isNotBlank() }
        if (withoutEmbedding.isEmpty()) return 0

        val texts = withoutEmbedding.map { it.content }
        val embeddings = embeddingService.embedBatch(texts)

        var count = 0
        withoutEmbedding.forEachIndexed { index, memory ->
            val embedding = embeddings.getOrNull(index) ?: return@forEachIndexed
            memoryDAO.updateMemory(
                memory.copy(embedding = VectorUtils.floatArrayToByteArray(embedding))
            )
            count++
        }

        Log.i(TAG, "Backfilled $count embeddings for assistant=$assistantId")
        return count
    }

    /**
     * 使用当前活跃后端重新嵌入所有记忆（维度迁移）
     * 当从远程切换到本地（或反之）时，需要统一向量维度
     * @return 成功重新嵌入的数量
     */
    suspend fun reEmbedAll(assistantId: String): Int {
        val memories = memoryDAO.getMemoriesOfAssistant(assistantId)
        val withContent = memories.filter { it.content.isNotBlank() }
        if (withContent.isEmpty()) return 0

        val targetDim = embeddingService.activeDimension
        var count = 0

        // 分批处理，每批 20 条
        withContent.chunked(20).forEach { batch ->
            val texts = batch.map { it.content }
            val embeddings = embeddingService.embedBatch(texts)

            batch.forEachIndexed { index, memory ->
                val embedding = embeddings.getOrNull(index) ?: return@forEachIndexed
                // 只更新维度不同的
                val existingDim = memory.embedding?.let {
                    VectorUtils.byteArrayToFloatArray(it).size
                } ?: 0
                if (existingDim != targetDim || memory.embedding == null) {
                    memoryDAO.updateMemory(
                        memory.copy(
                            embedding = VectorUtils.floatArrayToByteArray(embedding),
                            updatedAt = System.currentTimeMillis(),
                        )
                    )
                    count++
                }
            }
        }

        Log.i(TAG, "Re-embedded $count memories to dim=$targetDim for assistant=$assistantId")
        return count
    }
}
