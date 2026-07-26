package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 24 → 25: 新增 scheduled_tasks 表(定时任务).
 * 手写迁移(替代 AutoMigration), 因为仓库缺 25.json schema 快照 —— Room 推导
 * AutoMigration(24,25) 需要 24.json 与 25.json 两份 schema, 而当前实体已是 v26.
 */
val Migration_24_25 = object : Migration(24, 25) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `scheduled_tasks` (
                `id` TEXT NOT NULL,
                `assistant_id` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `prompt` TEXT NOT NULL,
                `type` TEXT NOT NULL,
                `time_minutes` INTEGER NOT NULL,
                `week_days` TEXT NOT NULL,
                `interval_minutes` INTEGER NOT NULL,
                `start_at` INTEGER NOT NULL,
                `enabled` INTEGER NOT NULL,
                `conversation_id` TEXT,
                `last_run_at` INTEGER NOT NULL,
                `created_at` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
    }
}
