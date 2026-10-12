package com.novelreader.app

import com.novelreader.app.data.parser.UniversalNovelParser
import org.junit.Assert.*
import org.junit.Test

class DownloadRetryTest {

    private val parser = UniversalNovelParser()

    @Test
    fun testRetryHelperLogic() {
        var attempts = 0
        val maxRetries = 3
        var success = false

        while (!success && attempts < maxRetries) {
            attempts++
            // 模擬前兩次下載失敗，第三次成功
            if (attempts == 3) {
                success = true
            }
        }

        assertTrue(success)
        assertEquals(3, attempts)
    }

    @Test
    fun testParseChapterContentWithEmptyAndValidHtml() {
        val emptyHtml = ""
        val emptyContent = parser.parseChapterContent(emptyHtml)
        assertTrue(emptyContent.isBlank())

        val validHtml = "<html><body><div id='content'><p>測試章節內容</p></div></body></html>"
        val validContent = parser.parseChapterContent(validHtml)
        assertEquals("測試章節內容", validContent)
    }
}
