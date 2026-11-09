package app.momoda

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParagraphGapTest {
    /** 按行号列出哪些行是段距行。 */
    private fun gaps(text: String): List<Int> = lines(text).withIndex()
        .filter { (_, r) -> ParagraphGap.isGapLine(text, r.first, r.last + 1) }.map { it.index }

    /** 按行号列出哪些行的上方加段距。 */
    private fun spaced(text: String): List<Int> = lines(text).withIndex()
        .filter { (_, r) -> ParagraphGap.followsGap(text, r.first) }.map { it.index }

    /** 每一行的 [起点, 终点)，终点含行尾的换行；最后一个换行之后的空行也算一行。 */
    private fun lines(text: String): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var start = 0
        while (true) {
            val nl = text.indexOf('\n', start)
            val end = if (nl < 0) text.length else nl + 1
            out += start until end
            if (nl < 0) break
            start = end
        }
        return out
    }

    @Test fun singleBlankLineIsGap() = assertEquals(listOf(1), gaps("第一段\n\n第二段"))

    @Test fun extraBlankLinesAreFullHeight() = assertEquals(listOf(1), gaps("第一段\n\n\n\n第二段"))

    @Test fun eachParagraphHasOneGap() = assertEquals(listOf(1, 3), gaps("一\n\n二\n\n三"))

    @Test fun leadingBlankLinesAreFullHeight() = assertEquals(emptyList<Int>(), gaps("\n\n\n正文"))

    @Test fun lastLineWithoutNewlineIsNotGap() = assertEquals(emptyList<Int>(), gaps("正文\n"))

    @Test fun gapAtDocumentEndRightAfterEnter() = assertEquals(listOf(1), gaps("段落\n\n"))

    @Test fun spacingGoesAboveNextParagraph() = assertEquals(listOf(2, 4), spaced("一\n\n二\n\n三"))

    @Test fun spacingAboveEmptyLastLineAfterEnter() = assertEquals(listOf(2), spaced("段落\n\n"))

    @Test fun extraBlankLineGetsTheSpacingNotTheTextAfterIt() = assertEquals(listOf(2), spaced("一\n\n\n二"))

    @Test fun softBreakHasNoSpacing() = assertEquals(emptyList<Int>(), spaced("诗句一\n诗句二"))

    @Test fun snapMovesOffGapByDirection() {
        val t = "甲\n\n乙"
        assertTrue(ParagraphGap.isGapStart(t, 2))
        assertEquals(1, ParagraphGap.snap(t, 2, forward = false))
        assertEquals(3, ParagraphGap.snap(t, 2, forward = true))
    }

    @Test fun snapLeavesOtherPositionsAlone() {
        val t = "甲\n\n\n乙"
        for (i in 0..t.length) if (i != 2) assertEquals(i, ParagraphGap.snap(t, i, forward = true))
        assertFalse(ParagraphGap.isGapStart(t, 3))
    }

    @Test fun enterDoublesNewline() = assertEquals(Edit(3, 3, "\n", 4), ParagraphGap.afterEnter("段落\n", 2))

    @Test fun enterOnBlankLineDoesNotDouble() = assertEquals(null, ParagraphGap.afterEnter("段\n\n\n", 3))
}
