package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.MemoryEntity

@Dao
interface MemoryDAO {
    @Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId")
    fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId")
    suspend fun getMemoriesOfAssistant(assistantId: String): List<MemoryEntity>

    @Query("SELECT * FROM memoryentity")
    fun getAllMemoriesFlow(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memoryentity")
    suspend fun getAllMemories(): List<MemoryEntity>

    @Query("SELECT * FROM memoryentity WHERE id = :id")
    suspend fun getMemoryById(id: Int): MemoryEntity?

    @Insert
    suspend fun insertMemory(memory: MemoryEntity): Long

    @Update
    suspend fun updateMemory(memory: MemoryEntity)

    @Query("DELETE FROM memoryentity WHERE id = :id")
    suspend fun deleteMemory(id: Int)

    @Query("DELETE FROM memoryentity WHERE assistant_id = :assistantId")
    suspend fun deleteMemoriesOfAssistant(assistantId: String)

    // === RAG 语义记忆扩展查询 ===

    @Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId AND embedding IS NOT NULL")
    suspend fun getMemoriesWithEmbedding(assistantId: String): List<MemoryEntity>

    @Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId ORDER BY updated_at DESC LIMIT :limit")
    suspend fun getRecentMemories(assistantId: String, limit: Int): List<MemoryEntity>

    @Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId AND importance >= :minImportance ORDER BY importance DESC, updated_at DESC")
    suspend fun getImportantMemories(assistantId: String, minImportance: Int): List<MemoryEntity>

    @Query("UPDATE memoryentity SET access_count = access_count + 1, last_accessed = :timestamp WHERE id = :id")
    suspend fun incrementAccessCount(id: Int, timestamp: Long = System.currentTimeMillis())

    @Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId AND source = :source")
    suspend fun getMemoriesBySource(assistantId: String, source: String): List<MemoryEntity>

    @Query("SELECT COUNT(*) FROM memoryentity WHERE assistant_id = :assistantId")
    suspend fun countMemories(assistantId: String): Int
}
