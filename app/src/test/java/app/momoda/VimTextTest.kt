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

    @Test fun fFindsNextChar() = assertEquals(6, VimText.findChar("one two", 0, 'o', forward = true, till = false))

    @Test fun fWithCount() = assertEquals(5, VimText.findChar("a,b,c,d", 0, ',', forward = true, till = false, n = 3))

    @Test fun fCommaMatchesChineseComma() =
        assertEquals(2, VimText.findChar("我说，你听，", 0, ',', forward = true, till = false))

    @Test fun fPeriodMatchesChinesePeriod() = assertEquals(3, VimText.findChar("第一句。第二句。", 0, '.', forward = true, till = false))

    @Test fun tStopsBefore() = assertEquals(1, VimText.findChar("ab,cd", 0, ',', forward = true, till = true))

    @Test fun tRepeatSkipsAdjacent() = assertEquals(3, VimText.findChar("ab,c,d", 1, ',', forward = true, till = true, repeat = true))

    @Test fun capitalFSearchesBackward() = assertEquals(3, VimText.findChar("a，b，c", 4, ',', forward = false, till = false))

    @Test fun capitalTStopsAfter() = assertEquals(2, VimText.findChar("a，b，c", 4, ',', forward = false, till = true, n = 2))

    @Test fun findStaysInParagraph() = assertEquals(null, VimText.findChar("甲乙\n\n丙，", 0, ',', forward = true, till = false))

    @Test fun backslashFindsDunhao() = assertEquals(2, VimText.findChar("苹果、梨", 0, '\\', forward = true, till = false))

    @Test fun lettersAreCaseSensitive() = assertEquals(null, VimText.findChar("abc", 0, 'B', forward = true, till = false))

    @Test fun dfIncludesTarget() {
        val t = "删到逗号，为止"
        val to = VimText.findChar(t, 0, ',', forward = true, till = false)!!
        val r = VimText.findRange(0, to, forward = true)
        assertEquals("为止", t.substring(0, r.start) + t.substring(r.end))
    }

    @Test fun dFExcludesCursorChar() {
        val t = "甲，乙丙"
        val r = VimText.findRange(3, VimText.findChar(t, 3, ',', forward = false, till = false)!!, forward = false)
        assertEquals("甲丙", t.substring(0, r.start) + t.substring(r.end))
    }

    @Test fun toggleCaseLeavesHanAlone() = assertEquals("hELLO 世界 123", VimText.toggleCase("Hello 世界 123"))
}
