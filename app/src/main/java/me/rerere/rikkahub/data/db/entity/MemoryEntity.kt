package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class MemoryEntity(
    @PrimaryKey(true)
    val id: Int = 0,
    @ColumnInfo("assistant_id")
    val assistantId: String,
    @ColumnInfo("content")
    val content: String = "",
    @ColumnInfo("embedding")
    val embedding: ByteArray? = null,
    @ColumnInfo("importance")
    val importance: Int = 0,
    @ColumnInfo("created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo("updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo("access_count")
    val accessCount: Int = 0,
    @ColumnInfo("last_accessed")
    val lastAccessed: Long = 0,
    @ColumnInfo("tags")
    val tags: String = "",
    @ColumnInfo("source")
    val source: String = "manual",
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MemoryEntity) return false
        return id == other.id &&
            assistantId == other.assistantId &&
            content == other.content &&
            embedding.contentEquals(other.embedding) &&
            importance == other.importance &&
            createdAt == other.createdAt &&
            updatedAt == other.updatedAt &&
            accessCount == other.accessCount &&
            lastAccessed == other.lastAccessed &&
            tags == other.tags &&
            source == other.source
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + assistantId.hashCode()
        result = 31 * result + content.hashCode()
        result = 31 * result + (embedding?.contentHashCode() ?: 0)
        result = 31 * result + importance
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + updatedAt.hashCode()
        result = 31 * result + accessCount
        result = 31 * result + lastAccessed.hashCode()
        result = 31 * result + tags.hashCode()
        result = 31 * result + source.hashCode()
        return result
    }
}
