package com.novelreader.app.data.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
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
        createNotificationChannel()
        val novelId = inputData.getString("NOVEL_ID") ?: ""
        val notificationId = NOTIFICATION_ID_OFFSET + novelId.hashCode()
        val notification = createNotification("準備離線下載...", 0, 0)

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ForegroundInfo(
                    notificationId,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                ForegroundInfo(notificationId, notification)
            }
        } catch (e: Exception) {
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

        // 取得 PowerManager WakeLock 以確保螢幕鎖定 (Lock Screen) 時 CPU 保持運作不中斷下載
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "NovelReader:DownloadWakeLock"
        )

        try {
            wakeLock?.acquire(60 * 60 * 1000L /* 1 hour timeout */)

            createNotificationChannel()

            val novel = db.novelDao().getNovelById(novelId)
            val novelTitle = novel?.title ?: "作品"

            // 嘗試設為 Foreground Service，以防背景被系統砍掉，若受限則捕捉異常繼續下載
            try {
                setForeground(getForegroundInfo())
            } catch (e: Exception) {
                e.printStackTrace()
            }

            val pendingChapters = db.chapterDao().getPendingDownloadChapters(novelId)
            val total = pendingChapters.size

            if (total == 0) {
                db.novelDao().refreshDownloadedCount(novelId)
                return@withContext Result.success(
                    workDataOf(
                        "SUCCESS_COUNT" to 0,
                        "FAILED_COUNT" to 0,
                        "TOTAL" to 0
                    )
                )
            }

            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notificationId = NOTIFICATION_ID_OFFSET + novelId.hashCode()

            var successCount = 0
            var failedCount = 0

            for ((index, chapter) in pendingChapters.withIndex()) {
                if (isStopped) {
                    // 使用者暫停或系統取消下載
                    return@withContext Result.retry()
                }

                val currentStep = index + 1
                val progressText = "[$currentStep/$total] $novelTitle - ${chapter.title}"

                // 更新通知與 WorkManager 進度
                try {
                    notificationManager.notify(
                        notificationId,
                        createNotification(progressText, currentStep, total)
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                try {
                    setProgress(
                        workDataOf(
                            "PROGRESS" to currentStep,
                            "TOTAL" to total,
                            "CURRENT_CHAPTER" to chapter.title,
                            "SUCCESS_COUNT" to successCount,
                            "FAILED_COUNT" to failedCount
                        )
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }

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

                if (downloadSuccess) {
                    successCount++
                } else {
                    failedCount++
                }

                // 章節間的溫和爬取間隔
                delay(300)
            }

            db.novelDao().refreshDownloadedCount(novelId)

            // 下載結束，依據成功與失敗數量顯示正確結果通知
            try {
                val completionText = if (failedCount == 0) {
                    "《$novelTitle》離線下載完成 (共 $total 章)"
                } else {
                    "《$novelTitle》下載完成：成功 $successCount 章，失敗 $failedCount 章 (可再次點擊下載重試)"
                }

                val completedNotification = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setContentTitle(if (failedCount == 0) "下載完成" else "下載部分完成")
                    .setContentText(completionText)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(completionText))
                    .setSmallIcon(if (failedCount == 0) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
                    .setAutoCancel(true)
                    .build()

                notificationManager.notify(notificationId, completedNotification)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            Result.success(
                workDataOf(
                    "SUCCESS_COUNT" to successCount,
                    "FAILED_COUNT" to failedCount,
                    "TOTAL" to total
                )
            )
        } finally {
            if (wakeLock?.isHeld == true) {
                try {
                    wakeLock.release()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
}
