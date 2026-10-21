package app.momoda

import org.junit.Assert.assertEquals
import org.junit.Test

class TypingRulesTest {

    /** 在 | 处插入 [typed]，套用规则，返回结果（| 标出光标）。 */
    private fun type(before: String, typed: String, cornerQuotes: Boolean = true, autoSpace: Boolean = true): String {
        val at = before.indexOf('|')
        val t = StringBuilder(before.replace("|", "")).insert(at, typed)
        val edit = TypingRules.afterInsert(t, at, at + typed.length, cornerQuotes, autoSpace)
            ?: return t.insert(at + typed.length, "|").toString()
        t.replace(edit.start, edit.end, edit.text)
        return t.insert(edit.cursor, "|").toString()
    }

    @Test fun latinAfterHan() = assertEquals("用 M|", type("用|", "M"))

    @Test fun hanAfterLatin() = assertEquals("Mac 写|", type("Mac|", "写"))

    @Test fun digitAfterHan() = assertEquals("第 3|", type("第|", "3"))

    @Test fun insertInMiddleSpacesBothSides() = assertEquals("用 Mac| 写", type("用|写", "Mac"))

    @Test fun noSpaceNextToPunctuation() = assertEquals("用，M|", type("用，|", "M"))

    @Test fun noDoubleSpace() = assertEquals("用 M|", type("用 |", "M"))

    @Test fun latinRunUntouched() = assertEquals("Mac|", type("Ma|", "c"))

    @Test fun noSpaceInInlineCode() = assertEquals("用 `中a|", type("用 `中|", "a"))

    @Test fun noSpaceInCodeBlock() = assertEquals("```\n中a|", type("```\n中|", "a"))

    @Test fun spacingOff() = assertEquals("用M|", type("用|", "M", autoSpace = false))

    @Test fun imeBracketsBecomeCornerQuotes() = assertEquals("他说「|", type("他说|", "【"))

    @Test fun imeClosingBracket() = assertEquals("他说「好」|", type("他说「好|", "】"))

    @Test fun bracesInChineseBecomeDoubleCorner() = assertEquals("「他说『|", type("「他说|", "{"))

    @Test fun closingBraceInChinese() = assertEquals("他说『好』|", type("他说『好|", "}"))

    @Test fun asciiBracketsUntouchedForMarkdownLinks() = assertEquals("见[|", type("见|", "["))

    @Test fun bracesInEnglishUntouched() = assertEquals("map {|", type("map |", "{"))

    @Test fun curlyQuotesFromImeUntouched() = assertEquals("他说“|", type("他说|", "“"))

    @Test fun cornerQuotesOff() = assertEquals("他说【|", type("他说|", "【", cornerQuotes = false))
}
