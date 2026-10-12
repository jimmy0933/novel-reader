package com.novelreader.app.data.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
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
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private val db = AppDatabase.getDatabase(context)
    private val parser = UniversalNovelParser()
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    companion object {
        const val CHANNEL_ID = "download_channel"
        const val CHANNEL_NAME = "小說下載服務"
        const val NOTIFICATION_ID_OFFSET = 1000
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val novelId = inputData.getString("NOVEL_ID") ?: ""
        val notificationId = NOTIFICATION_ID_OFFSET + novelId.hashCode()
        val notification = createNotification("準備離線下載...", 0, 0)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun createNotification(contentText: String, progress: Int, total: Int) =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("小說離線下載中")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply {
                if (total > 0) {
                    setProgress(total, progress, false)
                } else {
                    setProgress(0, 0, true)
                }
            }
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (notificationManager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
                )
                notificationManager.createNotificationChannel(channel)
            }
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val novelId = inputData.getString("NOVEL_ID") ?: return@withContext Result.failure()

        createNotificationChannel()

        val novel = db.novelDao().getNovelById(novelId)
        val novelTitle = novel?.title ?: "作品"

        // 啟動前設為 Foreground Service，確保背景下載不會被系統休眠砍掉
        try {
            setForeground(getForegroundInfo())
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val pendingChapters = db.chapterDao().getPendingDownloadChapters(novelId)
        val total = pendingChapters.size

        if (total == 0) {
            db.novelDao().refreshDownloadedCount(novelId)
            return@withContext Result.success()
        }

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notificationId = NOTIFICATION_ID_OFFSET + novelId.hashCode()

        for ((index, chapter) in pendingChapters.withIndex()) {
            if (isStopped) {
                // 使用者暫停或系統取消下載
                return@withContext Result.retry()
            }

            val currentStep = index + 1
            val progressText = "[$currentStep/$total] $novelTitle - ${chapter.title}"

            // 更新通知與 WorkManager 進度
            notificationManager.notify(
                notificationId,
                createNotification(progressText, currentStep, total)
            )

            setProgress(
                workDataOf(
                    "PROGRESS" to currentStep,
                    "TOTAL" to total,
                    "CURRENT_CHAPTER" to chapter.title
                )
            )

            // 針對單一章節增加重試機制 (最多重試 3 次)
            var downloadSuccess = false
            var retryCount = 0
            val maxRetries = 3

            while (!downloadSuccess && retryCount < maxRetries && !isStopped) {
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
                        if (content.isNotBlank()) {
                            db.chapterDao().saveChapterContent(novelId, chapter.chapterIndex, content)
                            db.novelDao().refreshDownloadedCount(novelId)
                            downloadSuccess = true
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                if (!downloadSuccess) {
                    retryCount++
                    if (retryCount < maxRetries) {
                        // 重試前稍作等待 (1秒 ~ 2秒)
                        delay(1000L * retryCount)
                    }
                }
            }

            // 章節間的溫和爬取間隔
            delay(300)
        }

        db.novelDao().refreshDownloadedCount(novelId)

        // 下載結束，可移除完成或持續通知
        val completedNotification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("下載完成")
            .setContentText("《$novelTitle》已成功離線整部小說")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(notificationId, completedNotification)

        Result.success()
    }
}
