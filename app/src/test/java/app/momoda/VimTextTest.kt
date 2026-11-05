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

    private fun cut(t: String, r: VimText.Range?) = r?.let { t.substring(0, it.start) + t.substring(it.end) }

    // ( )
    @Test fun nextSentenceInParagraph() = assertEquals(4, VimText.nextSentenceStart("第一句。第二句！", 0))

    @Test fun sentenceKeepsClosingQuote() = assertEquals(7, VimText.nextSentenceStart("他说：“好。”然后走了。", 0))

    @Test fun nextSentenceCrossesParagraph() = assertEquals(5, VimText.nextSentenceStart("甲句。\n\n乙句。", 0))

    @Test fun englishSentencesNeedSpace() = assertEquals(12, VimText.nextSentenceStart("Pi is 3.14. Next one.", 0))

    @Test fun prevSentenceGoesToStartThenBack() {
        val t = "第一句。第二句。"
        assertEquals(4, VimText.prevSentenceStart(t, 6))
        assertEquals(0, VimText.prevSentenceStart(t, 4))
    }

    @Test fun prevSentenceCrossesParagraph() = assertEquals(3, VimText.prevSentenceStart("甲句。乙句。\n\n丙。", 8))

    @Test fun lastSentenceGoesToEnd() = assertEquals(8, VimText.nextSentenceStart("第一句。第二句。", 5))

    @Test fun cisRemovesSentence() = assertEquals("第一句。第三句。", cut("第一句。第二句。第三句。", VimText.sentenceObject("第一句。第二句。第三句。", 5, around = false)))

    @Test fun asTakesTrailingSpace() = assertEquals("One. Three.", cut("One. Two. Three.", VimText.sentenceObject("One. Two. Three.", 6, around = true)))

    // { }
    @Test fun nextParagraph() = assertEquals(4, VimText.nextParagraphStart("甲段\n\n乙段", 1))

    @Test fun listIsOneParagraph() = assertEquals(13, VimText.nextParagraphStart("- 一\n- 二\n- 三\n\n段", 0))

    @Test fun lastParagraphGoesToEnd() = assertEquals(6, VimText.nextParagraphStart("甲\n\n乙丙丁", 3))

    @Test fun prevParagraph() {
        val t = "甲段\n\n乙段"
        assertEquals(4, VimText.prevParagraphStart(t, 5))
        assertEquals(0, VimText.prevParagraphStart(t, 4))
    }

    @Test fun dapRemovesParagraphAndSeparator() =
        assertEquals("甲\n\n丙", cut("甲\n\n- 乙1\n- 乙2\n\n丙", VimText.paragraphObject("甲\n\n- 乙1\n- 乙2\n\n丙", 5, around = true)))

    @Test fun ipIsJustText() = assertEquals(VimText.Range(3, 12), VimText.paragraphObject("甲\n\n- 乙1\n- 乙2\n\n丙", 5, around = false))

    // 括号、引号
    @Test fun ciQuoteMatchesChineseQuotes() = assertEquals("他说“”。", cut("他说“你好”。", VimText.pairObject("他说“你好”。", 4, '"', around = false)))

    @Test fun diBracketMatchesCornerQuotes() = assertEquals("「」", cut("「书名」", VimText.pairObject("「书名」", 2, '[', around = false)))

    @Test fun daParenWithChineseParens() = assertEquals("备注。", cut("备注（括号里）。", VimText.pairObject("备注（括号里）。", 4, '(', around = true)))

    @Test fun innermostPairWins() = assertEquals("(a [] c)", cut("(a [b] c)", VimText.pairObject("(a [b] c)", 4, '[', around = false)))

    @Test fun nestedSameKind() = assertEquals("（外（）外）", cut("（外（里）外）", VimText.pairObject("（外（里）外）", 3, '(', around = false)))

    @Test fun asciiQuotes() = assertEquals("say \"\" ok", cut("say \"hi\" ok", VimText.pairObject("say \"hi\" ok", 6, '"', around = false)))

    @Test fun noPairReturnsNull() = assertEquals(null, VimText.pairObject("没有括号", 1, '(', around = false))

    // J
    private fun join(t: String, at: Int) = VimText.joinNext(t, at)?.let { t.substring(0, it.start) + it.text + t.substring(it.end) }

    @Test fun joinChineseParagraphs() = assertEquals("甲段乙段", join("甲段\n\n乙段", 0))

    @Test fun joinEnglishAddsSpace() = assertEquals("one two", join("one\n\n  two", 0))

    @Test fun joinLastLineDoesNothing() = assertEquals(null, join("只有一段", 0))

    @Test fun replaceChinesePunctuationStaysChinese() {
        assertEquals('。', VimText.replacementFor('，', '.'))
        assertEquals(',', VimText.replacementFor('a', ','))
        assertEquals('x', VimText.replacementFor('，', 'x'))
    }

    @Test fun chineseCommaTargetAlsoMatchesAscii() =
        assertEquals(2, VimText.findChar("ab,c，d", 0, '，', forward = true, till = false))

    @Test fun nearestCommaWinsWhicheverKind() =
        assertEquals(11, VimText.findChar("我们用了 Gboard，效果不错, ok", 0, ',', forward = true, till = false))

    private val py = mapOf('我' to listOf("wo"), '是' to listOf("shi"), '说' to listOf("shuo", "shui"), '长' to listOf("chang", "zhang"),
        '中' to listOf("zhong"), '出' to listOf("chu"), '爱' to listOf("ai"))

    @Test fun letterFindsHanByInitial() = assertEquals(2, VimText.findChar("我爱是", 0, 's', forward = true, till = false) { py[it] })

    @Test fun letterStillFindsItself() = assertEquals(1, VimText.findChar("xs是", 0, 's', forward = true, till = false) { py[it] })

    @Test fun polyphoneMatchesAnyReading() {
        assertEquals(1, VimText.findChar("我长", 0, 'c', forward = true, till = false) { py[it] })
        assertEquals(1, VimText.findChar("我长", 0, 'z', forward = true, till = false) { py[it] })
    }

    @Test fun shuangpinKeysForZhChSh() {
        assertEquals(2, VimText.findChar("我爱说", 0, 'u', forward = true, till = false) { py[it] })
        assertEquals(1, VimText.findChar("我出", 0, 'i', forward = true, till = false) { py[it] })
        assertEquals(1, VimText.findChar("我中", 0, 'v', forward = true, till = false) { py[it] })
    }

    @Test fun zeroInitialSyllable() = assertEquals(1, VimText.findChar("我爱", 0, 'a', forward = true, till = false) { py[it] })

    @Test fun upperCaseDoesNotMatchHan() = assertEquals(null, VimText.findChar("我是", 0, 'S', forward = true, till = false) { py[it] })

    @Test fun withoutReadingsHanIsNotMatched() = assertEquals(null, VimText.findChar("我是", 0, 's', forward = true, till = false))
}
