package com.novelreader.app.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // We actually only need version 2 -> 3 now if it was already 2, or both
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // Create new table with updated primary key
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `chapters_new` (
                `novelId` TEXT NOT NULL,
                `chapterIndex` INTEGER NOT NULL,
                `chapterId` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `url` TEXT NOT NULL,
                `content` TEXT,
                `isDownloaded` INTEGER NOT NULL,
                PRIMARY KEY(`novelId`, `chapterId`)
            )
            """.trimIndent()
        )

        // Copy data
        database.execSQL(
            """
            INSERT OR IGNORE INTO `chapters_new` (`novelId`, `chapterIndex`, `chapterId`, `title`, `url`, `content`, `isDownloaded`)
            SELECT `novelId`, `chapterIndex`, `chapterId`, `title`, `url`, `content`, `isDownloaded` FROM `chapters`
            """.trimIndent()
        )

        // Remove old table
        database.execSQL("DROP TABLE `chapters`")

        // Rename new table to original name
        database.execSQL("ALTER TABLE `chapters_new` RENAME TO `chapters`")
    }
}
