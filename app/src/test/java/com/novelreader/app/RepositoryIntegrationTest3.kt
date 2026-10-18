package com.novelreader.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.novelreader.app.data.local.AppDatabase
import com.novelreader.app.data.local.ChapterEntity
import com.novelreader.app.data.local.NovelEntity
import com.novelreader.app.data.repository.NovelRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)

@Config(application = NovelApplication::class)
class RepositoryIntegrationTest3 {

    private lateinit var repository: NovelRepository
    private lateinit var context: Context
    private lateinit var mockWebServer: MockWebServer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        repository = NovelRepository(context)

        mockWebServer = MockWebServer()
        mockWebServer.start()

        runBlocking {
            withContext(Dispatchers.IO) {
                AppDatabase.getDatabase(context).clearAllTables()
            }
        }
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
        runBlocking {
            withContext(Dispatchers.IO) {
                AppDatabase.getDatabase(context).clearAllTables()
            }
        }
    }

    @Test
    fun testSyncChapters_InsertMiddle() = runBlocking {
        val db = AppDatabase.getDatabase(context)
        val novelId = "test_novel_sync"
        val novel = NovelEntity(
            id = novelId,
            title = "Test Novel",
            author = "Author",
            coverUrl = "",
            intro = "",
            sourceUrl = mockWebServer.url("/novel").toString(),
            totalChapters = 2,
            downloadedChapters = 0,
            lastReadChapterIndex = 0
        )

        withContext(Dispatchers.IO) {
            db.novelDao().insertNovel(novel)

            // Existing chapters 0 and 1
            val chapter1 = ChapterEntity(
                novelId = novelId,
                chapterIndex = 0,
                chapterId = "http://test/0",
                title = "Chapter 0",
                url = "http://test/0",
                content = null,
                isDownloaded = false
            )
            val chapter2 = ChapterEntity(
                novelId = novelId,
                chapterIndex = 1,
                chapterId = "http://test/2", // Note url is test/2
                title = "Chapter 2",
                url = "http://test/2",
                content = null,
                isDownloaded = false
            )
            db.chapterDao().insertChapters(listOf(chapter1, chapter2))
        }

        // Mock response containing chap 0, chap 1, chap 2. Chap 1 is new.
        val html = """
            <html><body>
                <ul id="chapter-list">
                    <li><a href="http://test/0">Chapter 0</a></li>
                    <li><a href="http://test/1">Chapter 1</a></li>
                    <li><a href="http://test/2">Chapter 2</a></li>
                </ul>
            </body></html>
        """.trimIndent()
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(html))

        val result = repository.syncLatestChapters(novelId)
        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrNull())

        val dbChapters = db.chapterDao().getChapters(novelId).sortedBy { it.chapterIndex }
        assertEquals(3, dbChapters.size)

        // Ensure new chapter is inserted in the middle with index 1
        val newChap = dbChapters.find { it.url == "http://test/1" }
        assertNotNull(newChap)
        assertEquals(1, newChap?.chapterIndex)

        // Ensure chapter 2 is pushed to index 2
        val chapter2 = dbChapters.find { it.url == "http://test/2" }
        assertNotNull(chapter2)
        assertEquals(2, chapter2?.chapterIndex)
    }
}
