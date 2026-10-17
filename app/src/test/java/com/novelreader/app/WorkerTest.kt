package com.novelreader.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker.Result
import androidx.work.testing.TestListenableWorkerBuilder
import com.novelreader.app.data.download.NovelDownloadWorker
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import androidx.work.workDataOf
import java.util.concurrent.Executor
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
class WorkerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testWorkerWithoutNovelIdFails() {
        val worker = TestListenableWorkerBuilder<NovelDownloadWorker>(
            context = context
        ).build()

        kotlinx.coroutines.runBlocking {
            val result = worker.doWork()
            assertEquals(Result.failure(), result)
        }
    }
}
