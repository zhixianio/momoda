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

    @Test fun withTitleKeepsHeadingLevel() = assertEquals("## 新标题\n\n正文", Titles.withTitle("## 旧\n\n正文", "新标题"))

    @Test fun withTitleOnPlainFirstLine() = assertEquals("# 新\n正文", Titles.withTitle("旧的第一行\n正文", "新"))

    @Test fun withTitleOnEmptyDoc() = assertEquals("# 新", Titles.withTitle("", "新"))

    @Test fun excerptSkipsTitleAndMarkers() =
        assertEquals("第一段 苹果 引用", Titles.excerptOf("# 标题\n\n第一段\n\n- **苹果**\n\n> 引用"))

    @Test fun excerptIsCut() = assertEquals("字".repeat(5) + "…", Titles.excerptOf("# t\n" + "字".repeat(9), 5))

    @Test fun excerptKeepsLiteralAsterisksAndUnderscores() =
        assertEquals("3 * 4 snake_case 粗", Titles.excerptOf("# t\n3 * 4 snake_case **粗**"))
}
