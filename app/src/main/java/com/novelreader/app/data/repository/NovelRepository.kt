package com.novelreader.app.data.repository

import android.content.Context
import androidx.work.*
import androidx.work.OutOfQuotaPolicy
import androidx.work.BackoffPolicy
import com.novelreader.app.data.download.NovelDownloadWorker
import com.novelreader.app.data.local.AppDatabase
import com.novelreader.app.data.local.ChapterEntity
import com.novelreader.app.data.local.NovelEntity
import com.novelreader.app.data.parser.UniversalNovelParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
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

    suspend fun getNovelById(novelId: String): NovelEntity? = novelDao.getNovelById(novelId)

    suspend fun getChapter(novelId: String, index: Int): ChapterEntity? = chapterDao.getChapterByIndex(novelId, index)

    suspend fun updateReadingProgress(novelId: String, chapterId: String, chapterTitle: String, chapterIndex: Int) {
        novelDao.updateReadingProgress(novelId, chapterId, chapterTitle, chapterIndex)
    }

    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeCalls = java.util.concurrent.ConcurrentHashMap<Pair<String, Int>, okhttp3.Call>()
    private val inFlightDownloads = java.util.concurrent.ConcurrentHashMap<Pair<String, Int>, Deferred<Result<Unit>>>()

    // Tracks the number of active waiters (e.g., Reader + Worker)
    private val inFlightWaiters = java.util.concurrent.ConcurrentHashMap<Pair<String, Int>, Int>()

    /**
     * 下載單一章節（提供即時閱讀、預載與背景整部下載共用，同一章節避免重複請求）
     */
    suspend fun downloadSingleChapter(novelId: String, chapterIndex: Int): Result<Unit> = withContext(Dispatchers.IO) {
        val chapter = chapterDao.getChapterByIndex(novelId, chapterIndex)
            ?: return@withContext Result.failure(IllegalArgumentException("找不到該章節"))

        if (chapter.isDownloaded && !chapter.content.isNullOrBlank()) {
            return@withContext Result.success(Unit)
        }

        val key = Pair(novelId, chapterIndex)

        val deferred = synchronized(inFlightDownloads) {
            inFlightWaiters[key] = (inFlightWaiters[key] ?: 0) + 1
            inFlightDownloads.getOrPut(key) {
                repositoryScope.async {
                    try {
                        fetchAndSaveChapter(novelId, chapter)
                    } finally {
                        inFlightDownloads.remove(key)
                        inFlightWaiters.remove(key)
                        activeCalls.remove(key)?.cancel()
                    }
                }
            }
        }

        try {
            deferred.await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            val waiters = synchronized(inFlightDownloads) {
                val count = (inFlightWaiters[key] ?: 1) - 1
                if (count <= 0) {
                    inFlightWaiters.remove(key)
                } else {
                    inFlightWaiters[key] = count
                }
                count
            }
            if (waiters <= 0) {
                deferred.cancel()
                activeCalls.remove(key)?.cancel()
            }
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun fetchAndSaveChapter(novelId: String, chapter: ChapterEntity): Result<Unit> {
        var lastException: Throwable? = null
        val maxAttempts = 3

        for (attempt in 1..maxAttempts) {
            try {
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

                val call = httpClient.newCall(requestBuilder.build())
                activeCalls[Pair(novelId, chapter.chapterIndex)] = call
                call.execute().use { response ->
                    val statusCode = response.code

                    if (!response.isSuccessful) {
                        val msg = when (statusCode) {
                            403 -> "HTTP 403: 存取被拒絕（可能有機器人防護）"
                            404 -> "HTTP 404: 找不到該章節網頁"
                            else -> "HTTP 狀態碼 $statusCode"
                        }
                        return Result.failure(IllegalStateException(msg))
                    }

                    val html = response.body?.string().orEmpty()

                    if (html.isBlank()) {
                        return Result.failure(IllegalStateException("網頁內容空白"))
                    }

                    if (html.contains("Enable JavaScript and cookies to continue") ||
                        html.contains("cf-challenge") ||
                        html.contains("captcha") ||
                        html.contains("請開啟 JavaScript")
                    ) {
                        return Result.failure(IllegalStateException("觸發防護驗證頁，無法直接擷取"))
                    }

                    val content = parser.parseChapterContent(html)
                    if (content.isBlank()) {
                        return Result.failure(IllegalStateException("章節正文解析失敗或內容空白"))
                    }

                    // 驗證成功，存入資料庫
                    chapterDao.saveChapterContent(novelId, chapter.chapterIndex, content)
                    novelDao.refreshDownloadedCount(novelId)
                    return Result.success(Unit)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    throw e // Ensure true cancellation is propagated
                }
                // Note: SocketTimeoutException is a subclass of InterruptedIOException
                // But we don't want to cancel the coroutine for just a network timeout
                lastException = e
                if (attempt < maxAttempts) {
                    delay(500L * attempt)
                }
            }
        }
        return Result.failure(lastException ?: IllegalStateException("下載該章節失敗"))
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

            val existingChapters = chapterDao.getChapters(novelId)
            val existingChapterMap = existingChapters.associateBy { it.url }

            val newChapters = detail.chapters.filter { it.url !in existingChapterMap }
            val totalNew = newChapters.size

            // Rewrite all chapters to match the website's correct order (detail.chapters)
            // Existing chapters will keep their downloaded status and content
            // We use Room's withTransaction (or similar) through DB if we want, or just a simple replace here
            // Because the primary key is now (novelId, chapterId), we can update or insert safely without collision on index

            val updatedEntities = detail.chapters.map { parsedChapter ->
                val existing = existingChapterMap[parsedChapter.url]
                if (existing != null) {
                    // Update index if it changed
                    existing.copy(chapterIndex = parsedChapter.index, title = parsedChapter.title)
                } else {
                    ChapterEntity(
                        novelId = novelId,
                        chapterIndex = parsedChapter.index,
                        chapterId = parsedChapter.url,
                        title = parsedChapter.title,
                        url = parsedChapter.url,
                        isDownloaded = false
                    )
                }
            }

            // Doing an upsert for all entities. Because PK is (novelId, chapterId), insert on conflict replace or update works.
            // However, Room Dao does not have an upsert out of the box unless we do insert OR IGNORE then UPDATE.
            // We can just use the provided update and insert methods

            val toInsert = updatedEntities.filter { it.url !in existingChapterMap }
            val toUpdate = updatedEntities.filter { it.url in existingChapterMap }

            // Apply DB changes
            if (toInsert.isNotEmpty()) {
                chapterDao.insertChapters(toInsert)
            }
            if (toUpdate.isNotEmpty()) {
                chapterDao.updateChapters(toUpdate)
            }

            // Make sure total chapters matches exactly what we have in DB
            val actualTotal = chapterDao.getChapters(novelId).size
            novelDao.updateTotalChapters(novelId, actualTotal)

            // Update reading progress index if the current chapter moved
            // (The novel's lastReadChapterIndex should be synced if lastReadChapterId exists)
            val currentNovel = novelDao.getNovelById(novelId)
            if (currentNovel != null && currentNovel.lastReadChapterId != null) {
                val matchingChap = updatedEntities.find { it.chapterId == currentNovel.lastReadChapterId }
                if (matchingChap != null && matchingChap.chapterIndex != currentNovel.lastReadChapterIndex) {
                    novelDao.updateReadingProgress(novelId, currentNovel.lastReadChapterId, matchingChap.title, matchingChap.chapterIndex, currentNovel.lastReadTime)
                }
            }

            Result.success(totalNew)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteNovel(novel: NovelEntity) {
        withContext(Dispatchers.IO) {
            cancelDownload(novel.id)

            // Collect keys that belong to this novel
            val keysToCancel = synchronized(inFlightDownloads) {
                inFlightDownloads.keys.filter { it.first == novel.id }
            }

            // Cancel and await active downloads for this novel
            for (key in keysToCancel) {
                val deferred = inFlightDownloads[key]
                if (deferred != null) {
                    deferred.cancel()
                    activeCalls.remove(key)?.cancel()
                    try {
                        deferred.await()
                    } catch (e: Exception) {
                        // ignore CancellationException and others
                    }
                }
            }

            chapterDao.deleteChaptersByNovelId(novel.id)
            novelDao.deleteNovel(novel)
        }
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
            val existingNovel = novelDao.getNovelById(novelId)
            val finalNovel: NovelEntity

            if (existingNovel != null) {
                // 如果已經存在，則更新資訊，並觸發同步合併章節，不要清除進度
                finalNovel = existingNovel.copy(
                    title = detail.title.ifBlank { existingNovel.title },
                    author = detail.author.ifBlank { existingNovel.author },
                    coverUrl = detail.coverUrl.ifBlank { existingNovel.coverUrl },
                    intro = detail.intro.ifBlank { existingNovel.intro }
                )
                novelDao.updateNovel(finalNovel)

                // 共用 sync 的章節更新邏輯（不需要發送網路請求）
                val existingChapters = chapterDao.getChapters(novelId)
                val existingChapterMap = existingChapters.associateBy { it.url }
                val updatedEntities = detail.chapters.map { parsedChapter ->
                    val existing = existingChapterMap[parsedChapter.url]
                    if (existing != null) {
                        existing.copy(chapterIndex = parsedChapter.index, title = parsedChapter.title)
                    } else {
                        ChapterEntity(
                            novelId = novelId,
                            chapterIndex = parsedChapter.index,
                            chapterId = parsedChapter.url,
                            title = parsedChapter.title,
                            url = parsedChapter.url,
                            isDownloaded = false
                        )
                    }
                }

                val toInsert = updatedEntities.filter { it.url !in existingChapterMap }
                val toUpdate = updatedEntities.filter { it.url in existingChapterMap }

                if (toInsert.isNotEmpty()) {
                    chapterDao.insertChapters(toInsert)
                }
                if (toUpdate.isNotEmpty()) {
                    chapterDao.updateChapters(toUpdate)
                }

                val actualTotal = chapterDao.getChapters(novelId).size
                novelDao.updateTotalChapters(novelId, actualTotal)

                // 同步閱讀進度
                if (finalNovel.lastReadChapterId != null) {
                    val matchingChap = updatedEntities.find { it.chapterId == finalNovel.lastReadChapterId }
                    if (matchingChap != null && matchingChap.chapterIndex != finalNovel.lastReadChapterIndex) {
                        novelDao.updateReadingProgress(novelId, finalNovel.lastReadChapterId, matchingChap.title, matchingChap.chapterIndex, finalNovel.lastReadTime)
                    }
                }
                novelDao.refreshDownloadedCount(novelId)

            } else {
                // 完全新加入
                finalNovel = NovelEntity(
                    id = novelId,
                    title = detail.title.ifBlank { "未命名作品" },
                    author = detail.author,
                    coverUrl = detail.coverUrl,
                    intro = detail.intro,
                    sourceUrl = url,
                    totalChapters = detail.chapters.size,
                    downloadedChapters = 0
                )
                novelDao.insertNovel(finalNovel)
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
            }

            Result.success(finalNovel)
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
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(
                BackoffPolicy.LINEAR,
                10,
                java.util.concurrent.TimeUnit.SECONDS
            )
            .build()

        // Use KEEP so we don't start duplicate jobs for the same novel.
        workManager.enqueueUniqueWork(
            "DOWNLOAD_$novelId",
            ExistingWorkPolicy.KEEP,
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
