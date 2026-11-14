package com.novelreader.app.utils

/**
 * 簡體轉繁體（台灣用字）。字表與詞組衍生自 OpenCC 的 s2tw 設定：
 * - s2t_chars.txt：每行「簡體字 + 繁體字」
 * - s2t_phrases.txt：每行「簡體詞<TAB>繁體詞」，用於一對多字與台灣用詞
 * 轉換採最長詞組優先，沒有詞組命中再逐字轉換。
 */
object ChineseConverter {

    private class Tables(val chars: Map<Char, Char>, val phrases: Map<String, String>, val maxPhraseLen: Int)

    private val tables: Tables by lazy { load() }

    private fun readLines(name: String): List<String> {
        val stream = ChineseConverter::class.java.classLoader?.getResourceAsStream(name) ?: return emptyList()
        return stream.bufferedReader(Charsets.UTF_8).use { it.readLines() }
    }

    private fun load(): Tables {
        val chars = HashMap<Char, Char>(8192)
        for (line in readLines("s2t_chars.txt")) {
            if (line.length >= 2) chars[line[0]] = line[1]
        }
        val phrases = HashMap<String, String>(16384)
        var maxLen = 1
        for (line in readLines("s2t_phrases.txt")) {
            val tab = line.indexOf('\t')
            if (tab > 0) {
                val key = line.substring(0, tab)
                phrases[key] = line.substring(tab + 1)
                if (key.length > maxLen) maxLen = key.length
            }
        }
        return Tables(chars, phrases, maxLen)
    }

    fun toTraditional(text: String): String {
        if (text.isEmpty()) return text
        val t = tables
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            var matched = false
            val maxL = minOf(t.maxPhraseLen, text.length - i)
            var len = maxL
            while (len >= 2) {
                val hit = t.phrases[text.substring(i, i + len)]
                if (hit != null) {
                    sb.append(hit)
                    i += len
                    matched = true
                    break
                }
                len--
            }
            if (!matched) {
                val c = text[i]
                sb.append(t.chars[c] ?: c)
                i++
            }
        }
        return sb.toString()
    }
}
