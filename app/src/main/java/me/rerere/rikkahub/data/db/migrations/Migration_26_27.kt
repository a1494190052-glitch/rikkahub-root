package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 26 → 27: memoryentity 表新增 RAG 语义记忆字段.
 * - embedding BLOB: 嵌入向量（FloatArray 的字节序列化）
 * - importance INTEGER: 重要性 0-5
 * - created_at INTEGER: 创建时间戳
 * - updated_at INTEGER: 更新时间戳
 * - access_count INTEGER: 访问次数
 * - last_accessed INTEGER: 最后访问时间戳
 * - tags TEXT: JSON array 标签
 * - source TEXT: 来源 (manual/auto/consolidated)
 */
val Migration_26_27 = object : Migration(26, 27) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `memoryentity` ADD COLUMN `embedding` BLOB DEFAULT NULL")
        db.execSQL("ALTER TABLE `memoryentity` ADD COLUMN `importance` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `memoryentity` ADD COLUMN `created_at` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `memoryentity` ADD COLUMN `updated_at` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `memoryentity` ADD COLUMN `access_count` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `memoryentity` ADD COLUMN `last_accessed` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `memoryentity` ADD COLUMN `tags` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `memoryentity` ADD COLUMN `source` TEXT NOT NULL DEFAULT 'manual'")
    }
}
