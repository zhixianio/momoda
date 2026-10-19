package app.momoda

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EditRulesTest {

    /** 模拟“在 | 处插入一个换行”，再应用规则，返回带 | 光标的结果。 */
    private fun enter(before: String, soft: Boolean = false): String {
        val pos = before.indexOf('|')
        val text = StringBuilder(before.replace("|", "")).insert(pos, "\n")
        val edit = EditRules.afterNewline(text, pos, soft) ?: return text.insert(pos + 1, "|").toString()
        text.replace(edit.start, edit.end, edit.text)
        return text.insert(edit.cursor, "|").toString()
    }

    /** 模拟退格：删掉 | 前面那个字符（必须是换行）。 */
    private fun backspace(before: String): String {
        val pos = before.indexOf('|') - 1
        val text = StringBuilder(before.replace("|", ""))
        check(text[pos] == '\n')
        text.deleteCharAt(pos)
        val edit = EditRules.afterNewlineDeleted(text, pos) ?: return text.insert(pos, "|").toString()
        text.replace(edit.start, edit.end, edit.text)
        return text.insert(edit.cursor, "|").toString()
    }

    /** 模拟向前删除：删掉 | 后面那个字符（必须是换行）。 */
    private fun forwardDelete(before: String): String {
        val pos = before.indexOf('|')
        val text = StringBuilder(before.replace("|", ""))
        check(text[pos] == '\n')
        text.deleteCharAt(pos)
        val edit = EditRules.afterNewlineDeleted(text, pos) ?: return text.insert(pos, "|").toString()
        text.replace(edit.start, edit.end, edit.text)
        return text.insert(edit.cursor, "|").toString()
    }

    @Test fun enterMakesParagraphBreak() = assertEquals("第一段\n\n|", enter("第一段|"))

    @Test fun enterInMiddleSplitsParagraph() = assertEquals("前半\n\n|后半", enter("前半|后半"))

    @Test fun shiftEnterIsSingleNewline() = assertEquals("诗句一\n|", enter("诗句一|", soft = true))

    @Test fun enterOnBlankLineDoesNotDoubleAgain() = assertEquals("段\n\n\n|", enter("段\n\n|"))

    @Test fun headingEnterStartsParagraph() = assertEquals("# 标题\n\n|", enter("# 标题|"))

    @Test fun bulletContinues() = assertEquals("- 苹果\n- |", enter("- 苹果|"))

    @Test fun indentedBulletKeepsIndent() = assertEquals("  * 子项\n  * |", enter("  * 子项|"))

    @Test fun numberedListIncrements() = assertEquals("1. 一\n2. |", enter("1. 一|"))

    @Test fun quoteContinues() = assertEquals("> 引文\n> |", enter("> 引文|"))

    @Test fun enterOnEmptyBulletExitsList() = assertEquals("- 苹果\n\n|", enter("- 苹果\n- |"))

    @Test fun softBreakInsideListStillContinues() = assertEquals("- 甲\n- |", enter("- 甲|", soft = true))

    @Test fun backspaceMergesParagraphs() = assertEquals("第一段|第二段", backspace("第一段\n\n|第二段"))

    @Test fun backspaceOnSoftBreakJustJoins() = assertEquals("甲|乙", backspace("甲\n|乙"))

    @Test fun backspaceAmongManyBlankLinesRemovesOne() = assertEquals("甲\n\n|乙", backspace("甲\n\n\n|乙"))

    @Test fun forwardDeleteMergesParagraphs() = assertEquals("第一段|第二段", forwardDelete("第一段|\n\n第二段"))

    @Test fun noRuleAtDocumentStart() = assertNull(EditRules.afterNewlineDeleted("\n正文", 0))
}
