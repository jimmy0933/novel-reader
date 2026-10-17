package com.novelreader.app.utils

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagnosticLogger {
    fun log(context: Context, workId: String, novelId: String, status: String, reason: String, failedChapters: List<Int>, retryCount: Int) {
        try {
            val file = File(context.filesDir, "download_diagnostics.log")
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val failedChaptersStr = if (failedChapters.isEmpty()) "None" else failedChapters.take(10).joinToString() + if(failedChapters.size > 10) "..." else ""
            val logLine = "[$time] Work: $workId, Novel: $novelId, Status: $status, Reason: $reason, Retries: $retryCount, Failed Chapters: $failedChaptersStr\n"
            file.appendText(logLine)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun readLogs(context: Context): String {
        return try {
            val file = File(context.filesDir, "download_diagnostics.log")
            if (file.exists()) file.readText() else "無診斷記錄。"
        } catch (e: Exception) {
            "讀取記錄失敗: ${e.message}"
        }
    }

    fun clearLogs(context: Context) {
        try {
            val file = File(context.filesDir, "download_diagnostics.log")
            if (file.exists()) file.delete()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
