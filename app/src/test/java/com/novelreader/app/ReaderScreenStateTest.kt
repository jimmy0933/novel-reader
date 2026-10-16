package com.novelreader.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.novelreader.app.data.local.AppDatabase
import com.novelreader.app.data.local.NovelEntity
import com.novelreader.app.data.repository.NovelRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReaderScreenStateTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: NovelRepository
    private val novelId = "test_novel_123"

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        // Here we test repository's behavior on getNovelById indirectly
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun testRepositoryGetsLatestNovelState() = runBlocking {
        val novelDao = db.novelDao()

        val novel = NovelEntity(
            id = novelId,
            title = "Test",
            author = "Auth",
            coverUrl = "",
            intro = "",
            sourceUrl = "http://test",
            lastReadChapterIndex = 42
        )
        novelDao.insertNovel(novel)

        val fetchedNovel = novelDao.getNovelById(novelId)
        assertEquals(42, fetchedNovel?.lastReadChapterIndex)

        // Update reading progress
        novelDao.updateReadingProgress(novelId, "ch45", "Chapter 45", 45)

        val fetchedNovelAfterUpdate = novelDao.getNovelById(novelId)
        assertEquals(45, fetchedNovelAfterUpdate?.lastReadChapterIndex)
    }
}
