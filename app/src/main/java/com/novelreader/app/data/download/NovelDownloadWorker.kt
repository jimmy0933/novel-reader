package com.novelreader.app.data.download

import android.content.Context
import androidx.work.*
import com.novelreader.app.data.local.AppDatabase
import com.novelreader.app.data.parser.UniversalNovelParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class NovelDownloadWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private val db = AppDatabase.getDatabase(context)
    private val parser = UniversalNovelParser()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val novelId = inputData.getString("NOVEL_ID") ?: return@withContext Result.failure()

        val pendingChapters = db.chapterDao().getPendingDownloadChapters(novelId)
        val total = pendingChapters.size

        if (total == 0) {
            db.novelDao().refreshDownloadedCount(novelId)
            return@withContext Result.success()
        }

        var successCount = 0

        for ((index, chapter) in pendingChapters.withIndex()) {
            if (isStopped) {
                // 使用者暫停或系統取消下載
                return@withContext Result.retry()
            }

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

                val response = client.newCall(requestBuilder.build()).execute()
                val html = response.body?.string().orEmpty()

                if (html.isNotBlank() && !html.contains("Enable JavaScript and cookies to continue")) {
                    val content = parser.parseChapterContent(html)
                    db.chapterDao().saveChapterContent(novelId, chapter.chapterIndex, content)
                    db.novelDao().refreshDownloadedCount(novelId)
                    successCount++
                }

                // 更新 WorkManager 進度通知
                setProgress(
                    workDataOf(
                        "PROGRESS" to (index + 1),
                        "TOTAL" to total,
                        "CURRENT_CHAPTER" to chapter.title
                    )
                )

                // 溫和爬取間隔，避免造成網站防禦被封鎖
                delay(300)
            } catch (e: Exception) {
                // 單一章節下載失敗時記錄並繼續嘗試下一章
                e.printStackTrace()
            }
        }

        db.novelDao().refreshDownloadedCount(novelId)
        Result.success()
    }
}
