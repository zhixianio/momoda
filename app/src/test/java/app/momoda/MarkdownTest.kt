package app.momoda

import app.momoda.Markdown.Kind.BOLD
import app.momoda.Markdown.Kind.HEADING1
import app.momoda.Markdown.Kind.HEADING2
import app.momoda.Markdown.Kind.ITALIC
import app.momoda.Markdown.Kind.LIST_ITEM
import app.momoda.Markdown.Kind.MARKER
import app.momoda.Markdown.Kind.QUOTE
import app.momoda.Markdown.Kind.RULE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {

    /** 把样式渲染成可读的字符串，例如 `MARKER[# ] HEADING1[标题]`。 */
    private fun show(line: String) =
        Markdown.parseLine(line).joinToString(" ") { "${it.kind}[${line.substring(it.start, it.end)}]" }

    @Test fun heading1() = assertEquals("MARKER[# ] HEADING1[春天]", show("# 春天"))

    @Test fun heading2() = assertEquals("MARKER[## ] HEADING2[标题二]", show("## 标题二"))

    @Test fun emptyHeadingIsJustMarker() = assertEquals("MARKER[# ]", show("# "))

    @Test fun hashWithoutSpaceIsNotHeading() = assertEquals("", show("#标签"))

    @Test fun boldInChinese() = assertEquals("MARKER[**] BOLD[重要] MARKER[**]", show("**重要**的事"))

    @Test fun italic() = assertEquals("MARKER[*] ITALIC[轻轻] MARKER[*]", show("说得*轻轻*的"))

    @Test fun boldAndItalicTogether() {
        val s = show("**粗** 和 *斜*")
        assertTrue(s, s.contains("BOLD[粗]") && s.contains("ITALIC[斜]"))
    }

    @Test fun lonelyAsteriskIsNotItalic() = assertEquals("", show("3 * 4 = 12"))

    @Test fun unclosedBoldIsPlain() = assertEquals("", show("**没有闭合"))

    @Test fun bulletList() = assertEquals("MARKER[- ] LIST_ITEM[- ]", show("- 苹果"))

    @Test fun numberedList() = assertEquals("MARKER[12. ] LIST_ITEM[12. ]", show("12. 第十二"))

    @Test fun indentedListKeepsIndentInHang() {
        val styles = Markdown.parseLine("  * 子项")
        assertEquals(listOf(MARKER, LIST_ITEM), styles.map { it.kind })
        assertEquals(4, styles.first { it.kind == LIST_ITEM }.end)
    }

    @Test fun quote() = assertEquals("MARKER[> ] QUOTE[> 引文]", show("> 引文"))

    @Test fun quoteWithList() {
        val kinds = Markdown.parseLine("> - 引用里的列表").map { it.kind }
        assertEquals(listOf(MARKER, QUOTE, MARKER, LIST_ITEM), kinds)
    }

    @Test fun listItemWithBold() {
        val kinds = Markdown.parseLine("- **重点**").map { it.kind }
        assertEquals(listOf(MARKER, LIST_ITEM, MARKER, BOLD, MARKER), kinds)
    }

    @Test fun horizontalRules() {
        for (r in listOf("---", "***", "___", "- - -", " ---- ")) {
            assertEquals(r, listOf(MARKER, RULE), Markdown.parseLine(r).map { it.kind })
        }
    }

    @Test fun twoDashesIsNotRule() = assertEquals("", show("--"))

    @Test fun headingWithBold() {
        val kinds = Markdown.parseLine("## **强调**标题").map { it.kind }
        assertEquals(listOf(MARKER, HEADING2, MARKER, BOLD, MARKER), kinds)
    }

    @Test fun plainText() = assertEquals("", show("普通的一句话。"))

    @Test fun headingContentRange() {
        val h = Markdown.parseLine("# 标题").first { it.kind == HEADING1 }
        assertEquals(2 to 4, h.start to h.end)
    }

    @Test fun italicUnderscoreInsideWordIgnored() = assertEquals("", show("snake_case_name"))

    @Test fun italicWithUnderscore() {
        val kinds = Markdown.parseLine("一个 _强调_ 词").map { it.kind }
        assertEquals(listOf(MARKER, ITALIC, MARKER), kinds)
    }
}
