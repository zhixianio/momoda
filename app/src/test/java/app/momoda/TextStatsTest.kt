package app.momoda

import org.junit.Assert.assertEquals
import org.junit.Test

class TextStatsTest {

    @Test fun countsEachHanCharacter() {
        assertEquals(4, TextStats.wordCount("墨水屏幕"))
    }

    @Test fun ignoresPunctuationAndWhitespace() {
        assertEquals(4, TextStats.wordCount("你好，世界！\n\n  "))
    }

    @Test fun countsLatinWordsAsOne() {
        assertEquals(3, TextStats.wordCount("hello e-ink world"))
    }

    @Test fun mixedChineseAndEnglish() {
        // 用 + Kindle + 写 + 作 + 2026
        assertEquals(5, TextStats.wordCount("用Kindle写作，2026。"))
    }

    @Test fun markdownMarkersAreNotWords() {
        assertEquals(3, TextStats.wordCount("# 标题\n\n**粗**"))
    }

    @Test fun empty() {
        assertEquals(0, TextStats.wordCount(""))
    }
}
