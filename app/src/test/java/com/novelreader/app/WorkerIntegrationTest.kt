package com.novelreader.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker.Result
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.novelreader.app.data.download.NovelDownloadWorker
import com.novelreader.app.data.local.AppDatabase
import com.novelreader.app.data.local.ChapterEntity
import com.novelreader.app.data.local.NovelEntity
import com.novelreader.app.data.repository.NovelRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = NovelApplication::class)
class WorkerIntegrationTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var mockWebServer: MockWebServer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        db = AppDatabase.getDatabase(context)

        mockWebServer = MockWebServer()
        mockWebServer.start()

        runBlocking {
            withContext(Dispatchers.IO) {
                db.clearAllTables()
            }
        }
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
        runBlocking {
            withContext(Dispatchers.IO) {
                db.clearAllTables()
            }
        }
    }

    @Test
    fun testWorkerRunsAndReportsSuccess() = runBlocking {
        val novelId = "test_novel_worker"
        val novel = NovelEntity(
            id = novelId,
            title = "Worker Test Novel",
            author = "Author",
            coverUrl = "",
            intro = "",
            sourceUrl = "http://localhost/test",
            totalChapters = 2,
            downloadedChapters = 0,
            lastReadChapterIndex = 0
        )

        val url1 = mockWebServer.url("/test/1").toString()
        val url2 = mockWebServer.url("/test/2").toString()

        withContext(Dispatchers.IO) {
            db.novelDao().insertNovel(novel)

            val chapter1 = ChapterEntity(
                novelId = novelId,
                chapterIndex = 0,
                chapterId = "ch_0",
                title = "Chapter 0",
                url = url1,
                content = null,
                isDownloaded = false
            )
            val chapter2 = ChapterEntity(
                novelId = novelId,
                chapterIndex = 1,
                chapterId = "ch_1",
                title = "Chapter 1",
                url = url2,
                content = null,
                isDownloaded = false
            )
            db.chapterDao().insertChapters(listOf(chapter1, chapter2))
        }

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("<html><body><div id='content'><p>Chap1</p></div></body></html>"))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("<html><body><div id='content'><p>Chap2</p></div></body></html>"))

        val worker = TestListenableWorkerBuilder<NovelDownloadWorker>(
            context = context
        ).setInputData(workDataOf("NOVEL_ID" to novelId)).build()

        val result = worker.doWork()

        assertTrue(result is Result.Success)
        val data = (result as Result.Success).outputData
        assertEquals(2, data.getInt("SUCCESS_COUNT", 0))
        assertEquals(0, data.getInt("FAILED_COUNT", -1))
    }
}
