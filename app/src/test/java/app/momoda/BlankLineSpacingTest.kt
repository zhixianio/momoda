package app.momoda

import org.junit.Assert.assertEquals
import org.junit.Test

class BlankLineSpacingTest {
    /** 每一行（起点，终点）里哪些是段距，按行号列出。 */
    private fun gaps(text: String): List<Int> {
        val out = mutableListOf<Int>()
        var start = 0
        var line = 0
        while (start < text.length) {
            val nl = text.indexOf('\n', start)
            val end = if (nl < 0) text.length else nl + 1
            if (EditorView.BlankLineSpacing.isParagraphGap(text, start, end)) out += line
            start = end
            line++
        }
        return out
    }

    @Test fun singleBlankLineIsGap() = assertEquals(listOf(1), gaps("第一段\n\n第二段"))

    @Test fun extraBlankLinesAreFullHeight() = assertEquals(listOf(1), gaps("第一段\n\n\n\n第二段"))

    @Test fun eachParagraphHasOneGap() = assertEquals(listOf(1, 3), gaps("一\n\n二\n\n三"))

    @Test fun leadingBlankLinesAreFullHeight() = assertEquals(emptyList<Int>(), gaps("\n\n\n正文"))

    @Test fun lastLineWithoutNewlineIsNotGap() = assertEquals(emptyList<Int>(), gaps("正文\n"))
}
