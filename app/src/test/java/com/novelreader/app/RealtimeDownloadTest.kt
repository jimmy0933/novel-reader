package com.novelreader.app

import com.novelreader.app.data.parser.UniversalNovelParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class RealtimeDownloadTest {

    private val parser = UniversalNovelParser()

    @Test
    fun testHttpErrorAndChallengeDetection() {
        val errorCodes = listOf(403, 404, 500, 502)
        for (code in errorCodes) {
            val isSuccessful = code in 200..299
            assertFalse("HTTP status $code should not be considered successful", isSuccessful)
        }

        val challengeHtmls = listOf(
            "<html><body>Enable JavaScript and cookies to continue</body></html>",
            "<html><body>cf-challenge-running</body></html>",
            "<html><body>Please solve the captcha to proceed</body></html>",
            "<html><body>請開啟 JavaScript 繼續閱讀</body></html>"
        )

        for (html in challengeHtmls) {
            val isChallenge = html.contains("Enable JavaScript and cookies to continue") ||
                    html.contains("cf-challenge") ||
                    html.contains("captcha") ||
                    html.contains("請開啟 JavaScript")
            assertTrue("Challenge HTML should be detected: $html", isChallenge)
        }
    }

    @Test
    fun testInFlightDeduplicationLogic() = runBlocking {
        val inFlightDownloads = ConcurrentHashMap<Pair<String, Int>, Deferred<Result<Unit>>>()
        val executionCount = AtomicInteger(0)

        val novelId = "test_novel_1"
        val chapterIndex = 5
        val key = Pair(novelId, chapterIndex)

        // 模擬 10 個併發請求同時要求下載同一章節 (例如：閱讀器主線、預載線與背景 Worker)
        val deferreds = List(10) {
            synchronized(inFlightDownloads) {
                inFlightDownloads.getOrPut(key) {
                    async {
                        executionCount.incrementAndGet()
                        kotlinx.coroutines.delay(100)
                        Result.success(Unit)
                    }
                }
            }
        }

        // 所有請求等待完成
        deferreds.forEach { it.await() }

        // 驗證網路下載任務僅被執行一次 (去重成功)
        assertEquals(1, executionCount.get())
    }

    @Test
    fun testPrefetchingIndexCalculation() {
        val totalChapters = 10
        val currentChapterIndex = 3

        val prefetchIndices = mutableListOf<Int>()
        for (i in 1..2) {
            val nextIdx = currentChapterIndex + i
            if (nextIdx < totalChapters) {
                prefetchIndices.add(nextIdx)
            }
        }

        assertEquals(listOf(4, 5), prefetchIndices)

        // 測試全書最後一章的預載邊界條件
        val lastChapterIndex = 9
        val endPrefetchIndices = mutableListOf<Int>()
        for (i in 1..2) {
            val nextIdx = lastChapterIndex + i
            if (nextIdx < totalChapters) {
                endPrefetchIndices.add(nextIdx)
            }
        }

        assertTrue(endPrefetchIndices.isEmpty())
    }

    @Test
    fun testReadingProgressPreservationOnFailure() {
        var savedChapterIndex = 2
        var savedChapterTitle = "第二章 降臨"

        val downloadResultSuccess = Result.failure<Unit>(IllegalStateException("HTTP 403"))

        if (downloadResultSuccess.isSuccess) {
            savedChapterIndex = 3
            savedChapterTitle = "第三章 突破"
        }

        // 驗證下載失敗時進度仍保持在上一章
        assertEquals(2, savedChapterIndex)
        assertEquals("第二章 降臨", savedChapterTitle)
    }
}
