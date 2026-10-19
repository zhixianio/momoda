package app.momoda

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FuzzyMatcherTest {

    private val py = mapOf(
        '新' to "xin", '建' to "jian", '文' to "wen", '档' to "dang",
        '打' to "da", '开' to "kai", '模' to "mo", '式' to "shi", '字' to "zi", '号' to "hao",
    )
    private val alt = mapOf('长' to listOf("zhang", "chang"), '重' to listOf("zhong", "chong"), '要' to listOf("yao"))
    private val m = FuzzyMatcher { alt[it] ?: py[it]?.let(::listOf) }

    @Test fun initials() = assertEquals(2, m.score("xj", "新建文档"))

    @Test fun fullPinyinPrefix() = assertEquals(4, m.score("xinj", "新建文档"))

    @Test fun englishWord() = assertEquals(0, m.score("zen", "Zen 模式"))

    @Test fun chineseText() = assertEquals(1, m.score("文档", "新建文档"))

    @Test fun subsequenceOfInitials() = assertEquals(5, m.score("xd", "新建文档"))

    @Test fun noMatch() = assertNull(m.score("qq", "新建文档"))

    @Test fun filterSortsByScoreThenOriginalOrder() {
        val items = listOf("打开文档", "新建文档", "字号放大")
        // 同分时保持原顺序
        assertEquals(listOf("打开文档", "新建文档"), m.filter("wd", items) { it })
        assertEquals(listOf("新建文档"), m.filter("xj", items) { it })
        assertEquals(items, m.filter("", items) { it })
    }

    @Test fun polyphonicInitialsEitherReading() {
        assertEquals(2, m.score("cw", "长文"))
        assertEquals(2, m.score("zw", "长文"))
    }

    @Test fun polyphonicFullPinyin() = assertEquals(4, m.score("changw", "长文"))

    @Test fun polyphonicSecondChar() = assertEquals(3, m.score("cy", "很重要"))
}
