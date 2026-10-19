package app.momoda

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TitlesTest {

    @Test fun headingLineIsTitle() = assertEquals("春天的随笔", Titles.titleOf("# 春天的随笔\n\n正文"))

    @Test fun plainFirstLineIsTitle() = assertEquals("今天下雨", Titles.titleOf("今天下雨\n\n其他"))

    @Test fun skipsLeadingBlankLines() = assertEquals("标题", Titles.titleOf("\n\n## 标题"))

    @Test fun stripsEmphasis() = assertEquals("重要的事", Titles.titleOf("# **重要**的事"))

    @Test fun emptyHeadingIsUntitled() = assertNull(Titles.titleOf("# \n"))

    @Test fun emptyDocIsUntitled() = assertNull(Titles.titleOf(""))

    @Test fun illegalCharsBecomeSpaces() = assertEquals("a b c", Titles.baseNameFor("a/b:c", "x"))

    @Test fun fallbackWhenNothingLeft() = assertEquals("2026-10-20", Titles.baseNameFor("///", "2026-10-20"))

    @Test fun nullTitleUsesFallback() = assertEquals("2026-10-20", Titles.baseNameFor(null, "2026-10-20"))

    @Test fun longTitleIsCut() = assertEquals(60, Titles.baseNameFor("字".repeat(100), "x").length)

    @Test fun uniqueAddsSuffix() {
        val taken = setOf("随笔.md", "随笔 2.md")
        assertEquals("随笔 3.md", Titles.uniqueFileName("随笔") { it in taken })
        assertEquals("新.md", Titles.uniqueFileName("新") { it in taken })
    }
}
