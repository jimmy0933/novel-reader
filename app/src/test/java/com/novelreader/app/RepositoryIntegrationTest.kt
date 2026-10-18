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

@RunWith(RobolectricTestRunner::class)
class RepositoryIntegrationTest {

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
    fun testDownloadSingleChapter_MockSuccessAndFailure() = runBlocking {
        val db = AppDatabase.getDatabase(context)
        val novel = NovelEntity(
            id = "test_novel_integration",
            title = "Test Novel",
            author = "Author",
            coverUrl = "",
            intro = "",
            sourceUrl = "http://localhost/test",
            totalChapters = 1,
            downloadedChapters = 0,
            lastReadChapterIndex = 0
        )

        val url = mockWebServer.url("/test/0").toString()

        withContext(Dispatchers.IO) {
            db.novelDao().insertNovel(novel)

            val chapter = ChapterEntity(
                novelId = "test_novel_integration",
                chapterIndex = 0,
                chapterId = "ch_0",
                title = "Chapter 0",
                url = url,
                content = null,
                isDownloaded = false
            )
            db.chapterDao().insertChapters(listOf(chapter))
        }

        // Mock 404 response
        mockWebServer.enqueue(MockResponse().setResponseCode(404))
        var result = repository.downloadSingleChapter("test_novel_integration", 0)
        assertTrue(result.isFailure)

        // Mock Success response with actual HTML
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("<html><body><div id='content'><p>Hello World</p></div></body></html>"))
        result = repository.downloadSingleChapter("test_novel_integration", 0)
        assertTrue(result.isSuccess)

        // Verify saved
        val saved = db.chapterDao().getChapterByIndex("test_novel_integration", 0)
        assertTrue(saved?.isDownloaded == true)
        assertEquals("Hello World", saved?.content)
    }
}
