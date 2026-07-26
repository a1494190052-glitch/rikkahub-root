package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.ShellAuditEntity

@Dao
interface ShellAuditDAO {

    @Insert
    suspend fun insert(entity: ShellAuditEntity)

    @Query(
        """
        UPDATE shell_audit
        SET status = :status, exitCode = :exitCode, durationMs = :durationMs, outputPreview = :outputPreview
        WHERE id = :id
        """
    )
    suspend fun finish(id: String, status: String, exitCode: Int?, durationMs: Long?, outputPreview: String?)

    @Query("SELECT * FROM shell_audit ORDER BY createdAt DESC LIMIT :limit")
    fun recentFlow(limit: Int = 300): Flow<List<ShellAuditEntity>>

    @Query("SELECT * FROM shell_audit WHERE status = 'running' ORDER BY createdAt DESC")
    fun runningFlow(): Flow<List<ShellAuditEntity>>

    @Query("DELETE FROM shell_audit")
    suspend fun clearAll()

    /** 防表无限膨胀: 只保留最新 1000 条 */
    @Query("DELETE FROM shell_audit WHERE id NOT IN (SELECT id FROM shell_audit ORDER BY createdAt DESC LIMIT 1000)")
    suspend fun trim()
}
