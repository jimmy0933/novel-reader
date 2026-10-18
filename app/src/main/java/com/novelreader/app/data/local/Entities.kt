package com.novelreader.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "novels")
data class NovelEntity(
    @PrimaryKey val id: String, // 可以是小說網址的 hash 或網址本身
    val title: String,
    val author: String,
    val coverUrl: String,
    val intro: String,
    val sourceUrl: String,
    val lastReadChapterId: String? = null,
    val lastReadChapterTitle: String? = null,
    val lastReadChapterIndex: Int = 0,
    val lastReadTime: Long = System.currentTimeMillis(),
    val totalChapters: Int = 0,
    val downloadedChapters: Int = 0,
    val isFavorite: Boolean = true
)

@Entity(tableName = "chapters", primaryKeys = ["novelId", "chapterId"])
data class ChapterEntity(
    val novelId: String,
    val chapterIndex: Int,
    val chapterId: String, // 該章節在網站上的識別或連結
    val title: String,
    val url: String,
    val content: String? = null, // 離線儲存的章節內文
    val isDownloaded: Boolean = false
)
