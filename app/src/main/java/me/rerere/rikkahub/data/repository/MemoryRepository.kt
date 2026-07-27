package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.ai.memory.SemanticMemoryManager
import me.rerere.rikkahub.data.ai.memory.SemanticSearchResult
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.model.AssistantMemory

class MemoryRepository(
    private val memoryDAO: MemoryDAO,
    private val semanticMemoryManager: SemanticMemoryManager? = null,
) {
    companion object {
        const val GLOBAL_MEMORY_ID = "__global__"
    }

    fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(assistantId)
            .map { entities ->
                entities.map { AssistantMemory(it.id, it.content) }
            }

    suspend fun getMemoriesOfAssistant(assistantId: String): List<AssistantMemory> {
        return memoryDAO.getMemoriesOfAssistant(assistantId)
            .map { AssistantMemory(it.id, it.content) }
    }

    fun getGlobalMemoriesFlow(): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(GLOBAL_MEMORY_ID)
            .map { entities ->
                entities.map { AssistantMemory(it.id, it.content) }
            }

    suspend fun getGlobalMemories(): List<AssistantMemory> {
        return memoryDAO.getMemoriesOfAssistant(GLOBAL_MEMORY_ID)
            .map { AssistantMemory(it.id, it.content) }
    }

    suspend fun deleteMemoriesOfAssistant(assistantId: String) {
        memoryDAO.deleteMemoriesOfAssistant(assistantId)
    }

    suspend fun updateContent(id: Int, content: String): AssistantMemory {
        // 如果有 SemanticMemoryManager，使用它来更新（自动重新嵌入）
        if (semanticMemoryManager != null) {
            val updated = semanticMemoryManager.updateMemory(id, content)
            if (updated != null) {
                return AssistantMemory(id = updated.id, content = updated.content)
            }
        }
        // 回退：简单更新
        val old = memoryDAO.getMemoryById(id) ?: error("Memory record #$id not found")
        val newMemory = old.copy(
            content = content,
            updatedAt = System.currentTimeMillis(),
        )
        memoryDAO.updateMemory(newMemory)
        return AssistantMemory(
            id = newMemory.id,
            content = newMemory.content,
        )
    }

    suspend fun addMemory(assistantId: String, content: String): AssistantMemory {
        // 如果有 SemanticMemoryManager，使用它来创建（自动生成嵌入）
        if (semanticMemoryManager != null) {
            val entity = semanticMemoryManager.addMemory(
                assistantId = assistantId,
                content = content,
                source = "manual",
            )
            return AssistantMemory(id = entity.id, content = entity.content)
        }
        // 回退：简单创建
        val newId = memoryDAO.insertMemory(
            MemoryEntity(
                assistantId = assistantId,
                content = content,
            )
        ).toInt()
        return AssistantMemory(id = newId, content = content)
    }

    suspend fun deleteMemory(id: Int) {
        memoryDAO.deleteMemory(id)
    }

    // === RAG 语义搜索扩展 ===

    /**
     * 语义搜索记忆
     */
    suspend fun searchMemories(
        assistantId: String,
        query: String,
        topK: Int = 5,
    ): List<SemanticSearchResult> {
        return semanticMemoryManager?.searchMemories(assistantId, query, topK)
            ?: emptyList()
    }

    /**
     * 根据对话上下文自动检索相关记忆
     */
    suspend fun getRelevantMemories(
        assistantId: String,
        context: String,
        topK: Int = 5,
    ): List<SemanticSearchResult> {
        return semanticMemoryManager?.getRelevantMemories(assistantId, context, topK)
            ?: emptyList()
    }

    /**
     * 合并相似记忆
     */
    suspend fun consolidateMemories(assistantId: String): Int {
        return semanticMemoryManager?.consolidateMemories(assistantId) ?: 0
    }

    /**
     * 获取记忆实体列表（含完整字段，用于 UI 展示）
     */
    fun getMemoryEntitiesFlow(assistantId: String): Flow<List<MemoryEntity>> =
        memoryDAO.getMemoriesOfAssistantFlow(assistantId)

    /**
     * 获取所有记忆实体
     */
    fun getAllMemoryEntitiesFlow(): Flow<List<MemoryEntity>> =
        memoryDAO.getAllMemoriesFlow()

    /**
     * 获取记忆数量
     */
    suspend fun countMemories(assistantId: String): Int {
        return memoryDAO.countMemories(assistantId)
    }
}
