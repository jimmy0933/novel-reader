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
import com.novelreader.app.utils.DiagnosticLogger

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

        // Get PowerManager WakeLock
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "NovelReader:DownloadWakeLock"
        )

        // 鎖屏時保持 Wi-Fi 不休眠，避免螢幕關閉後下載停擺
        @Suppress("DEPRECATION")
        val wifiLock = try {
            (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager)
                ?.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "NovelReader:DownloadWifiLock")
        } catch (e: Exception) {
            null
        }

        try {
            wakeLock?.acquire(6 * 60 * 60 * 1000L /* 6 hour timeout */)
            try {
                wifiLock?.setReferenceCounted(false)
                wifiLock?.acquire()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            createNotificationChannel()

            val novel = db.novelDao().getNovelById(novelId)
            val novelTitle = novel?.title ?: "作品"

            try {
                setForeground(getForegroundInfo())
            } catch (e: Exception) {
                e.printStackTrace()
                // Do not eat exception entirely, if we can't set foreground, OS might kill us,
                // but let's record and throw if needed, or backoff
                if (e is android.app.ForegroundServiceStartNotAllowedException || e is IllegalStateException) {
                    return@withContext Result.retry()
                }
            }

            // Retry until no pending chapters
            var hasMorePending = true
            var overallSuccess = 0
            var overallFailed = 0
            val failedChaptersIndices = mutableListOf<Int>()
            var retryCount = runAttemptCount
            var noProgressPasses = 0
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notificationId = NOTIFICATION_ID_OFFSET + novelId.hashCode()

            val app = context.applicationContext as? com.novelreader.app.NovelApplication
            val repository = app?.repository

            while (hasMorePending) {
                if (isStopped) {
                    return@withContext Result.retry()
                }

                val pendingChapters = db.chapterDao().getPendingDownloadChapters(novelId)
                val total = db.chapterDao().getChapters(novelId).size
                val pendingCount = pendingChapters.size

                if (pendingCount == 0) {
                    hasMorePending = false
                    break
                }

                var passSuccess = 0
                for ((index, chapter) in pendingChapters.withIndex()) {
                    if (isStopped) {
                        return@withContext Result.retry()
                    }

                    val currentStep = total - pendingCount + index + 1
                    val progressText = "[$currentStep/$total] $novelTitle - ${chapter.title}"

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
                                "SUCCESS_COUNT" to overallSuccess,
                                "FAILED_COUNT" to overallFailed
                            )
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    val downloadResult = if (repository != null) {
                        repository.downloadSingleChapter(novelId, chapter.chapterIndex)
                    } else {
                        kotlin.Result.failure(IllegalStateException("NovelRepository 未初始化"))
                    }

                    if (downloadResult.isSuccess) {
                        overallSuccess++
                        passSuccess++
                    } else {
                        val ex = downloadResult.exceptionOrNull()
                        if (ex is kotlinx.coroutines.CancellationException) {
                            // If the download single chapter was cancelled, check if the worker is stopped
                            if (isStopped) {
                                return@withContext Result.retry()
                            }
                        }
                        overallFailed++
                        failedChaptersIndices.add(chapter.chapterIndex)
                    }

                    // 隨機間隔避免被網站判定為機器人而限速或斷線
                    delay((300L..900L).random())
                }

                // 一輪結束仍有失敗章節：只要這一輪有進展就立刻再跑一輪；
                // 連續多輪完全沒進展（斷網、被限速等）才交給 WorkManager 退避重試，避免下載到一半自己停掉
                if (isStopped) {
                    return@withContext Result.retry()
                }
                if (passSuccess > 0) {
                    noProgressPasses = 0
                    if (db.chapterDao().getPendingDownloadChapters(novelId).isNotEmpty()) delay(2_000L)
                } else {
                    noProgressPasses++
                    if (noProgressPasses >= 5) break
                    delay(10_000L * noProgressPasses)
                }
            }

            // 以實際剩餘未下載章節為準（先前失敗、之後重試成功的不重複計算）
            val stillPending = db.chapterDao().getPendingDownloadChapters(novelId)
            overallFailed = stillPending.size
            failedChaptersIndices.clear()
            failedChaptersIndices.addAll(stillPending.map { it.chapterIndex })

            db.novelDao().refreshDownloadedCount(novelId)

            val willRetry = overallFailed > 0 && !isStopped && runAttemptCount < 20
            val willStopRetry = isStopped
            val status = if (isStopped) "STOPPED" else if (willRetry) "WAITING_RETRY" else if (overallFailed == 0) "SUCCESS" else "PARTIAL_SUCCESS"
            val msg = if (isStopped) "Worker was stopped by OS/User" else if (willRetry) "Waiting for retry..." else "Completed pass"

            DiagnosticLogger.log(
                context,
                id.toString(),
                novelId,
                status,
                msg,
                failedChaptersIndices,
                retryCount
            )

            try {
                if (!willRetry && !isStopped) {
                    val completionText = if (overallFailed == 0) {
                        "《$novelTitle》離線下載完成"
                    } else {
                        "《$novelTitle》最終下載結果：成功 $overallSuccess 章，失敗 $overallFailed 章 (可再次點擊下載重試)"
                    }

                    val completedNotification = NotificationCompat.Builder(context, CHANNEL_ID)
                        .setContentTitle(if (overallFailed == 0) "下載完成" else "下載部分完成")
                        .setContentText(completionText)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(completionText))
                        .setSmallIcon(if (overallFailed == 0) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
                        .setAutoCancel(true)
                        .build()

                    notificationManager.notify(notificationId, completedNotification)
                } else if (willRetry) {
                    val retryText = "《$novelTitle》遇到網路問題，等待自動重試 (第 ${runAttemptCount + 1} 次)..."
                    val retryNotification = NotificationCompat.Builder(context, CHANNEL_ID)
                        .setContentTitle("等待重試中")
                        .setContentText(retryText)
                        .setSmallIcon(android.R.drawable.stat_sys_download)
                        .setAutoCancel(true)
                        .build()
                    notificationManager.notify(notificationId, retryNotification)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            return@withContext if (willRetry || willStopRetry) {
                Result.retry()
            } else if (overallFailed > 0) {
                // We reached max retries but still have failures. Leave pending, report failure.
                Result.failure(
                    workDataOf(
                        "SUCCESS_COUNT" to overallSuccess,
                        "FAILED_COUNT" to overallFailed
                    )
                )
            } else {
                Result.success(
                    workDataOf(
                        "SUCCESS_COUNT" to overallSuccess,
                        "FAILED_COUNT" to overallFailed
                    )
                )
            }
        } finally {
            try {
                if (wifiLock?.isHeld == true) wifiLock.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            if (wakeLock?.isHeld == true) {
                try {
                    wakeLock.release()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }}
