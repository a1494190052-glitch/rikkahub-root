package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 25 → 26: 新增 shell_audit 表(shell 命令审计日志) + scheduled_tasks 新增 action_type 列.
 * 手写迁移(不用 AutoMigration)因为仓库里缺少 25.json schema 快照.
 */
val Migration_25_26 = object : Migration(25, 26) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `shell_audit` (
                `id` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `source` TEXT NOT NULL,
                `workspaceId` TEXT,
                `command` TEXT NOT NULL,
                `cwd` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `exitCode` INTEGER,
                `durationMs` INTEGER,
                `outputPreview` TEXT,
                `taskId` TEXT,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            "ALTER TABLE `scheduled_tasks` ADD COLUMN `action_type` TEXT NOT NULL DEFAULT 'LLM'"
        )
    }
}
