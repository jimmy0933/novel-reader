package com.novelreader.app

import com.novelreader.app.data.parser.UniversalNovelParser
import org.junit.Assert.*
import org.junit.Test

class UniversalNovelParserTest {

    private val parser = UniversalNovelParser()

    @Test
    fun testParseNovelDetail() {
        val sampleHtml = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta property="og:title" content="《神奇小說》" />
                <meta property="og:novel:author" content="作者：張三" />
                <meta property="og:description" content="這是一本非常精彩的小說簡介" />
            </head>
            <body>
                <ul id="chapter-list">
                    <li><a href="https://example.com/chapter1">第一章 冒險開始</a></li>
                    <li><a href="https://example.com/chapter2">第二章 神秘城堡</a></li>
                </ul>
            </body>
            </html>
        """.trimIndent()

        val detail = parser.parseNovelDetail(sampleHtml, "https://example.com")

        assertEquals("神奇小說", detail.title)
        assertEquals("張三", detail.author)
        assertEquals("這是一本非常精彩的小說簡介", detail.intro)
        assertEquals(2, detail.chapters.size)
        assertEquals("第一章 冒險開始", detail.chapters[0].title)
        assertEquals("https://example.com/chapter1", detail.chapters[0].url)
        assertEquals("第二章 神秘城堡", detail.chapters[1].title)
    }

    @Test
    fun testParseChapterContent() {
        val sampleHtml = """
            <!DOCTYPE html>
            <html>
            <body>
                <div id="content">
                    <script>var ad = 1;</script>
                    <p>這是第一段話。</p>
                    <p>這是第二段話。</p>
                </div>
            </body>
            </html>
        """.trimIndent()

        val content = parser.parseChapterContent(sampleHtml)

        assertTrue(content.contains("這是第一段話。"))
        assertTrue(content.contains("這是第二段話。"))
        assertFalse(content.contains("var ad = 1;"))
    }
}
