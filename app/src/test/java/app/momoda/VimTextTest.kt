package app.momoda

import org.junit.Assert.assertEquals
import org.junit.Test

class VimTextTest {

    /** 在 | 处执行 [op]，返回删掉范围后的文字（| 标出删除后的位置）。 */
    private fun delete(before: String, range: (String, Int) -> VimText.Range): String {
        val at = before.indexOf('|')
        val t = before.replace("|", "")
        val r = range(t, at)
        return t.substring(0, r.start) + "|" + t.substring(r.end)
    }

    @Test fun dwDeletesWordAndSpace() =
        assertEquals("one |three", delete("one |two three") { t, at -> VimText.wordForward(t, at, 1, change = false) })

    @Test fun cwStopsAtWordEnd() =
        assertEquals("one | three", delete("one |two three") { t, at -> VimText.wordForward(t, at, 1, change = true) })

    @Test fun dwOnLastWordStaysInParagraph() =
        assertEquals("one |\n\nnext", delete("one |two\n\nnext") { t, at -> VimText.wordForward(t, at, 1, change = false) })

    @Test fun d2w() =
        assertEquals("|three four", delete("|one two three four") { t, at -> VimText.wordForward(t, at, 2, change = false) })

    @Test fun c2wStopsAtSecondWordEnd() =
        assertEquals("| three", delete("|one two three") { t, at -> VimText.wordForward(t, at, 2, change = true) })

    @Test fun wordEndFromSpaceGoesToNextWordEnd() = assertEquals(6, VimText.wordEnd("one two", 3))

    @Test fun wordEndFromMiddle() = assertEquals(2, VimText.wordEnd("one two", 0))

    @Test fun diw() = assertEquals("one | three", delete("one t|wo three") { t, at -> VimText.wordObject(t, at, around = false) })

    @Test fun daw() = assertEquals("one |three", delete("one t|wo three") { t, at -> VimText.wordObject(t, at, around = true) })

    @Test fun ddMiddleParagraphLeavesOneSeparator() =
        assertEquals("甲\n\n|丙", delete("甲\n\n|乙\n\n丙") { t, at -> VimText.lines(t, at, 1) })

    @Test fun ddLastParagraphRemovesPrecedingSeparator() =
        assertEquals("甲|", delete("甲\n\n|乙") { t, at -> VimText.lines(t, at, 1) })

    @Test fun ddLastParagraphWithTrailingNewline() =
        assertEquals("甲|", delete("甲\n\n|乙\n") { t, at -> VimText.lines(t, at, 1) })

    @Test fun ddOnlyParagraph() = assertEquals("|", delete("|甲") { t, at -> VimText.lines(t, at, 1) })

    @Test fun d2dSkipsSeparators() =
        assertEquals("|丙", delete("|甲\n\n乙\n\n丙") { t, at -> VimText.lines(t, at, 2) })

    @Test fun ddListItem() = assertEquals("- 甲\n|- 丙", delete("- 甲\n|- 乙\n- 丙") { t, at -> VimText.lines(t, at, 1) })

    @Test fun yyTextIsJustTheParagraph() {
        val t = "甲\n\n乙\n\n丙"
        assertEquals("乙", VimText.linesText(t, VimText.lines(t, 3, 1)))
    }

    @Test fun ccRangeInnerKeepsSeparators() {
        val r = VimText.lines("甲\n\n乙\n\n丙", 3, 1)
        assertEquals(3 to 4, r.innerStart to r.innerEnd)
    }

    @Test fun advanceSkipsBlankLines() {
        val t = "甲\n\n乙\n\n丙"
        assertEquals(6, VimText.advanceLines(t, 0, 2))
        assertEquals(0, VimText.advanceLines(t, 6, -5))
    }

    @Test fun lastLineIgnoresTrailingNewline() = assertEquals(3, VimText.lastLineStart("甲\n\n乙\n"))

    @Test fun findForwardWraps() {
        val t = "Jarvis 来了，jarvis 走了"
        assertEquals(10, VimText.find(t, "jarvis", 0, forward = true))
        assertEquals(0, VimText.find(t, "jarvis", 10, forward = true))
    }

    @Test fun findBackwardWraps() {
        val t = "甲 乙 甲 乙"
        assertEquals(0, VimText.find(t, "甲", 4, forward = false))
        assertEquals(4, VimText.find(t, "甲", 0, forward = false))
    }

    @Test fun findMissing() = assertEquals(-1, VimText.find("甲乙", "丙", 0, forward = true))

    @Test fun gapLineStart() {
        val t = "甲\n\n\n乙"
        assertEquals(listOf(2), t.indices.filter { VimText.isGapLineStart(t, it) })
    }

    @Test fun pasteLinesAfterAddsSeparator() {
        val t = "甲\n\n丙"
        val (at, text, cursor) = VimText.pasteLinesAfter(t, 0, "乙")
        val result = t.substring(0, at) + text + t.substring(at)
        assertEquals("甲\n\n乙\n\n丙", result)
        assertEquals(3, cursor)
    }

    @Test fun pasteLinesBeforeAddsSeparator() {
        val t = "甲\n\n丙"
        val (at, text, cursor) = VimText.pasteLinesBefore(t, 3, "乙")
        assertEquals("甲\n\n乙\n\n丙", t.substring(0, at) + text + t.substring(at))
        assertEquals(3, cursor)
    }

    private fun paste(t: String, at: Int, content: String, after: Boolean): Pair<String, Int> {
        val (i, text, cursor) = if (after) VimText.pasteLinesAfter(t, at, content) else VimText.pasteLinesBefore(t, at, content)
        return (t.substring(0, i) + text + t.substring(i)) to cursor
    }

    @Test fun pasteListItemAfterStaysInList() =
        assertEquals("- 甲\n- 乙\n- 乙\n\n丙" to 8, paste("- 甲\n- 乙\n\n丙", 4, "- 乙", after = true))

    @Test fun pasteListItemBeforeStaysInList() =
        assertEquals("- 乙\n- 甲\n- 乙" to 0, paste("- 甲\n- 乙", 0, "- 乙", after = false))

    @Test fun pasteAfterLastListItemStaysInList() =
        assertEquals("- 甲\n- 乙\n- 甲" to 8, paste("- 甲\n- 乙", 4, "- 甲", after = true))
}
