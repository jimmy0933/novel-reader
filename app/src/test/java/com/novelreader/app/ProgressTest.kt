package com.novelreader.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.novelreader.app.data.local.AppDatabase
import com.novelreader.app.data.local.ChapterEntity
import com.novelreader.app.data.local.NovelEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProgressTest {
    private lateinit var db: AppDatabase
    private val novelId = "test_novel_123"

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun testUpdateReadingProgress_CorrectlyUpdatesDatabase() = runBlocking {
        val novelDao = db.novelDao()
        val chapterDao = db.chapterDao()

        // 1. 初始化測試小說與章節
        val novel = NovelEntity(
            id = novelId,
            title = "Test Novel",
            author = "Author",
            coverUrl = "",
            intro = "",
            sourceUrl = "http://test.com",
            totalChapters = 10,
            downloadedChapters = 0,
            lastReadChapterIndex = 0
        )
        novelDao.insertNovel(novel)

        val chapter10 = ChapterEntity(novelId, 10, "ch10", "Chapter 10", "url10", "content", true)
        val chapter20 = ChapterEntity(novelId, 20, "ch20", "Chapter 20", "url20", "content", true)
        val chapter15 = ChapterEntity(novelId, 15, "ch15", "Chapter 15", "url15", "content", true)

        chapterDao.insertChapters(listOf(chapter10, chapter15, chapter20))

        // 2. 模擬連續閱讀到第 20 章
        novelDao.updateReadingProgress(novelId, chapter10.chapterId, chapter10.title, chapter10.chapterIndex)
        var updatedNovel = novelDao.getNovelById(novelId)
        assertEquals(10, updatedNovel?.lastReadChapterIndex)

        novelDao.updateReadingProgress(novelId, chapter20.chapterId, chapter20.title, chapter20.chapterIndex)
        updatedNovel = novelDao.getNovelById(novelId)
        assertEquals(20, updatedNovel?.lastReadChapterIndex)

        // 3. 模擬從第 20 章主動回看第 15 章
        // 要求："主動從第 20 章回看第 15 章，重新進入應恢復第 15 章...不能用最大章節編號阻止倒退"
        novelDao.updateReadingProgress(novelId, chapter15.chapterId, chapter15.title, chapter15.chapterIndex)
        updatedNovel = novelDao.getNovelById(novelId)
        assertEquals(15, updatedNovel?.lastReadChapterIndex) // 必須是 15，最後一次有效操作為主
        assertEquals("Chapter 15", updatedNovel?.lastReadChapterTitle)
    }
}
