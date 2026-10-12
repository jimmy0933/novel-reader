package com.novelreader.app.data.repository

import android.content.Context
import androidx.work.*
import com.novelreader.app.data.download.NovelDownloadWorker
import com.novelreader.app.data.local.AppDatabase
import com.novelreader.app.data.local.ChapterEntity
import com.novelreader.app.data.local.NovelEntity
import com.novelreader.app.data.parser.UniversalNovelParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class NovelRepository(private val context: Context) {
    private val db = AppDatabase.getDatabase(context)
    private val novelDao = db.novelDao()
    private val chapterDao = db.chapterDao()
    private val parser = UniversalNovelParser()
    private val workManager = WorkManager.getInstance(context)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun getAllNovels(): Flow<List<NovelEntity>> = novelDao.getAllNovelsFlow()

    fun getNovelFlow(novelId: String): Flow<NovelEntity?> = novelDao.getNovelFlow(novelId)

    fun getChaptersFlow(novelId: String): Flow<List<ChapterEntity>> = chapterDao.getChaptersFlow(novelId)

    suspend fun getChapter(novelId: String, index: Int): ChapterEntity? = chapterDao.getChapterByIndex(novelId, index)

    suspend fun updateReadingProgress(novelId: String, chapterId: String, chapterTitle: String, chapterIndex: Int) {
        novelDao.updateReadingProgress(novelId, chapterId, chapterTitle, chapterIndex)
    }

    /**
     * 下載單一章節
     */
    suspend fun downloadSingleChapter(novelId: String, chapterIndex: Int): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val chapter = chapterDao.getChapterByIndex(novelId, chapterIndex)
                ?: return@withContext Result.failure(IllegalArgumentException("找不到該章節"))

            val cookie = try {
                android.webkit.CookieManager.getInstance().getCookie(chapter.url)
            } catch (e: Exception) {
                null
            }

            val requestBuilder = Request.Builder()
                .url(chapter.url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-TW,zh;q=0.9,en-US;q=0.8,en;q=0.7")

            if (!cookie.isNullOrBlank()) {
                requestBuilder.header("Cookie", cookie)
            }

            val response = httpClient.newCall(requestBuilder.build()).execute()
            val html = response.body?.string().orEmpty()

            if (html.isNotBlank() && !html.contains("Enable JavaScript and cookies to continue")) {
                val content = parser.parseChapterContent(html)
                chapterDao.saveChapterContent(novelId, chapterIndex, content)
                novelDao.refreshDownloadedCount(novelId)
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException("無法讀取該章節內容"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 同步連載小說的最新章節（保留已下載內容，僅增量添加新發布章節）
     */
    suspend fun syncLatestChapters(novelId: String): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val novel = novelDao.getNovelById(novelId) ?: return@withContext Result.failure(IllegalStateException("找不到此作品"))
            val request = Request.Builder()
                .url(novel.sourceUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
                .build()
            val response = httpClient.newCall(request).execute()
            val html = response.body?.string() ?: return@withContext Result.failure(IllegalStateException("無法連線讀取最新目錄"))

            val detail = parser.parseNovelDetail(html, novel.sourceUrl)
            if (detail.chapters.isEmpty()) {
                return@withContext Result.failure(IllegalStateException("解析最新章節列表失敗"))
            }

            // 取得本地已存在的章節網址集合
            val existingChapters = chapterDao.getChapters(novelId)
            val existingUrls = existingChapters.map { it.url }.toSet()

            // 篩選出本地尚未擁有的新章節
            val newChapters = detail.chapters.filter { it.url !in existingUrls }

            if (newChapters.isNotEmpty()) {
                val newEntities = newChapters.map {
                    ChapterEntity(
                        novelId = novelId,
                        chapterIndex = it.index,
                        chapterId = it.url,
                        title = it.title,
                        url = it.url,
                        isDownloaded = false
                    )
                }
                chapterDao.insertChapters(newEntities)
                novelDao.updateTotalChapters(novelId, existingChapters.size + newChapters.size)
            }

            Result.success(newChapters.size)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteNovel(novel: NovelEntity) {
        cancelDownload(novel.id)
        chapterDao.deleteChaptersByNovelId(novel.id)
        novelDao.deleteNovel(novel)
    }

    /**
     * 從網址直接抓取或從 WebView 傳入 HTML 解析並加入書架
     */
    suspend fun importNovelFromUrl(url: String, htmlContent: String? = null): Result<NovelEntity> = withContext(Dispatchers.IO) {
        try {
            val html = if (!htmlContent.isNullOrBlank()) {
                htmlContent
            } else {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
                    .build()
                val response = httpClient.newCall(request).execute()
                response.body?.string() ?: throw IllegalStateException("無法讀取網頁內容")
            }

            val detail = parser.parseNovelDetail(html, url)
            if (detail.chapters.isEmpty()) {
                return@withContext Result.failure(IllegalStateException("找不到章節清單，請確認網址或透過內建瀏覽器驗證後匯入"))
            }

            val novelId = hashUrl(url)
            val novel = NovelEntity(
                id = novelId,
                title = detail.title.ifBlank { "未命名作品" },
                author = detail.author,
                coverUrl = detail.coverUrl,
                intro = detail.intro,
                sourceUrl = url,
                totalChapters = detail.chapters.size,
                downloadedChapters = 0
            )

            // 若之前曾加入過該小說，重新加入時清除舊有章節狀態，全新處理
            chapterDao.deleteChaptersByNovelId(novelId)
            novelDao.insertNovel(novel)

            val chapterEntities = detail.chapters.map {
                ChapterEntity(
                    novelId = novelId,
                    chapterIndex = it.index,
                    chapterId = it.url,
                    title = it.title,
                    url = it.url,
                    isDownloaded = false
                )
            }
            chapterDao.insertChapters(chapterEntities)

            Result.success(novel)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 發動背景整部下載
     */
    fun startDownloadAllChapters(novelId: String) {
        val workRequest = OneTimeWorkRequestBuilder<NovelDownloadWorker>()
            .setInputData(workDataOf("NOVEL_ID" to novelId))
            .addTag("DOWNLOAD_$novelId")
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        workManager.enqueueUniqueWork(
            "DOWNLOAD_$novelId",
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }

    fun cancelDownload(novelId: String) {
        workManager.cancelUniqueWork("DOWNLOAD_$novelId")
    }

    fun getDownloadWorkInfo(novelId: String): Flow<List<WorkInfo>> {
        return workManager.getWorkInfosForUniqueWorkFlow("DOWNLOAD_$novelId")
    }

    private fun hashUrl(url: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digested = md.digest(url.toByteArray())
        return digested.joinToString("") { "%02x".format(it) }
    }
}
