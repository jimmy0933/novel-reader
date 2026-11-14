package com.novelreader.app

import com.novelreader.app.utils.ChineseConverter
import org.junit.Assert.assertEquals
import org.junit.Test

class ChineseConverterTest {
    @Test
    fun convertsBasicCharacters() {
        assertEquals("歡迎", ChineseConverter.toTraditional("欢迎"))
        assertEquals("溫簡言是個欺詐師", ChineseConverter.toTraditional("温简言是个欺诈师"))
    }

    @Test
    fun resolvesAmbiguousCharactersByPhrase() {
        assertEquals("頭髮", ChineseConverter.toTraditional("头发"))
        assertEquals("發現", ChineseConverter.toTraditional("发现"))
        assertEquals("皇后", ChineseConverter.toTraditional("皇后"))
    }

    @Test
    fun keepsTraditionalAndPunctuation() {
        assertEquals("「你好」，世界！ABC 123", ChineseConverter.toTraditional("「你好」，世界！ABC 123"))
    }
}
