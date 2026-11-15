package com.novelreader.app

import com.novelreader.app.data.parser.UniversalNovelParser
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class XszjParserTest {

    private val parser = UniversalNovelParser()

    private fun sample(name: String): String {
        val f = listOf(File("../samples/$name"), File("samples/$name")).first { it.exists() }
        return f.readText()
    }

    @Test
    fun bookUrlNormalization() {
        assertEquals("https://xszj.org/b/103995", parser.xszjBookUrl("https://m.xszj.org/b/103995"))
        assertEquals("https://xszj.org/b/103995", parser.xszjBookUrl("https://xszj.org/b/103995/c/1407677"))
        assertEquals("https://xszj.org/b/103995", parser.xszjBookUrl("https://xszj.org/b/103995/cs/2"))
        assertNull(parser.xszjBookUrl("https://example.com/b/1"))
    }

    @Test
    fun parseBookDetail() {
        val d = parser.parseNovelDetail(sample("xszj_book.html"), "https://xszj.org/b/103995")
        assertEquals("欢迎进入梦魇直播间", d.title)
        assertEquals("桑沃", d.author)
        assertTrue(d.intro.startsWith("温简言是个欺诈师"))
        assertTrue(d.chapters.size > 50)
        assertEquals("第1章 德才中学", d.chapters[0].title)
        assertEquals("https://xszj.org/b/103995/c/1407677", d.chapters[0].url)
        assertEquals(d.chapters.size, d.chapters.map { it.url }.toSet().size)
        // 範例書頁標示「连载」→ 連載中
        assertEquals(false, d.isFinished)
    }

    @Test
    fun finishedStatusDetection() {
        fun html(status: String) = """<html><head><meta property="og:novel:status" content="$status"/></head>
            <body><ul id="chapter-list"><li><a href="https://e.com/c1">第1章 a</a></li></ul></body></html>"""
        assertEquals(true, parser.parseNovelDetail(html("完結"), "https://e.com").isFinished)
        assertEquals(true, parser.parseNovelDetail(html("完结"), "https://e.com").isFinished)
        assertEquals(false, parser.parseNovelDetail(html("連載中"), "https://e.com").isFinished)
        assertNull(parser.parseNovelDetail("<html><body></body></html>", "https://e.com").isFinished)
    }

    @Test
    fun parseTocPages() {
        val html = sample("xszj_toc1.html")
        val tocUrl = "https://xszj.org/b/103995/cs/1"
        val urls = parser.parseXszjTocPageUrls(html, tocUrl)
        assertEquals(7, urls.size)
        assertEquals("https://xszj.org/b/103995/cs/2", urls[1])
        val chapters = parser.parseXszjTocPage(html, tocUrl)
        assertEquals(200, chapters.size)
        assertEquals("第1章 德才中学", chapters[0].title)
    }

    @Test
    fun parseChapterContent() {
        val text = parser.parseChapterContent(sample("xszj_chapter.html"))
        assertTrue(text.startsWith("第一章"))
        assertTrue(text.contains("一声巨响"))
        assertTrue(text.contains("老倒霉蛋了"))
        assertFalse(text.contains("function"))
    }

    @Test
    fun pagedChapterNextPage() {
        val first = "https://xszj.org/b/480384/c/57083"
        assertEquals(
            "https://xszj.org/b/480384/c/57083?page=2",
            parser.parseXszjNextPageUrl(sample("xszj_chapter_paged1.html"), first)
        )
        // 最後一頁的「下一章」不屬於本章，不可再往下抓
        assertNull(parser.parseXszjNextPageUrl(sample("xszj_chapter_paged6.html"), "$first?page=6"))
        // 單頁章節沒有分頁
        assertNull(parser.parseXszjNextPageUrl(sample("xszj_chapter.html"), "https://xszj.org/b/103995/c/1407677"))
    }
}
