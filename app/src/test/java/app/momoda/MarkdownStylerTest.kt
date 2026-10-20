package app.momoda

import android.text.Editable
import android.text.InputFilter
import android.text.Spanned
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownStylerTest {

    private val styler = MarkdownStyler(1f) { it.length * 10f }

    /** 每个引用竖线覆盖的文字，例如 `["> 引用\n"]`。 */
    private fun quoteBars(t: SpanText) =
        t.spans.filter { it.what is QuoteBarSpan }.map { t.toString().substring(it.start, it.end) }

    @Test
    fun quoteBarStretchedOverNextLinesIsReplaced() {
        // 在引用行末回车、再在空的“> ”上回车时，SpannableStringBuilder 会把引用的段落样式拉长到后面几行。
        // 之前只清理完全落在本行内的样式：竖线叠成好几条，还延伸到下面的标题上
        val t = SpanText("> 引用\n\n## 标题")
        t.setSpan(QuoteBarSpan(14, 3), 0, 5, Spanned.SPAN_PARAGRAPH)
        t.setSpan(QuoteBarSpan(14, 3), 0, t.length, Spanned.SPAN_PARAGRAPH)
        t.setSpan(QuoteBarSpan(14, 3), 5, t.length, Spanned.SPAN_PARAGRAPH)

        // 只重排刚打字的标题行
        styler.restyle(t, 6, t.length)

        assertEquals(listOf("> 引用\n"), quoteBars(t))
    }

    @Test
    fun headingStretchedOverNextLineIsRemoved() {
        // 在“## hello world”的 wo 和 rld 之间回车：标题样式跟着跨到了下一行，下一行仍显示成标题
        val t = SpanText("## hello wo\n\nrld again")
        t.setSpan(HeadingSpan(1.2f), 3, t.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        styler.restyle(t, 13, t.length)
        val headings = t.spans.filter { it.what is HeadingSpan }.map { t.toString().substring(it.start, it.end) }
        assertEquals(listOf("hello wo"), headings)
    }

    @Test
    fun neighbouringParagraphStylesAreKept() {
        val t = SpanText("> 一\n> 二\n正文")
        styler.restyleAll(t)
        styler.restyle(t, 6, t.length)
        assertEquals(listOf("> 一\n", "> 二\n"), quoteBars(t).sorted())
    }

    @Test
    fun unchangedStylesAreNotRemovedAndAddedAgain() {
        // 每删、加一个全角引号或标题样式，DynamicLayout 都要重排整段：满是引号的段落里打一个字曾要 1.5 秒
        val t = SpanText("# 标题\n\n他说：“你好”，又说：“再见”。**粗体**\n\n> 引用\n\n- 列表")
        styler.restyleAll(t)
        t.sets = 0; t.removes = 0
        styler.restyle(t, 8, 12)
        styler.restyleAll(t)
        assertEquals(0, t.sets)
        assertEquals(0, t.removes)
    }

    @Test
    fun changedLineGetsNewStylesOnly() {
        val t = SpanText("他说：“你好”，普通")
        styler.restyleAll(t)
        // 行尾加了粗体：引号样式不动，只加粗体和两个标记
        t.replace(t.length, t.length, "**粗**")
        t.sets = 0; t.removes = 0
        styler.restyle(t, 0, t.length)
        assertEquals(3, t.sets)
        assertEquals(0, t.removes)
    }

    @Test
    fun restyleAllIsIdempotent() {
        val t = SpanText("# 标题\n\n> 引用\n\n- 列表\n\n---\n")
        styler.restyleAll(t)
        val first = t.spans.map { "${it.what.javaClass.simpleName}[${it.start},${it.end})" }.sorted()
        styler.restyleAll(t)
        assertEquals(first, t.spans.map { "${it.what.javaClass.simpleName}[${it.start},${it.end})" }.sorted())
    }
}

/** 单测用的 Editable：JVM 上 android.text 只有空壳。只实现 [MarkdownStyler] 用到的部分，样式位置不随编辑移动。 */
class SpanText(text: String) : Editable {
    class Span(val what: Any, val start: Int, val end: Int, val flags: Int)

    private val sb = StringBuilder(text)
    val spans = mutableListOf<Span>()
    var sets = 0
    var removes = 0

    override val length get() = sb.length
    override fun get(index: Int) = sb[index]
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = sb.substring(startIndex, endIndex)
    override fun toString() = sb.toString()
    override fun getChars(start: Int, end: Int, dest: CharArray, destoff: Int) = sb.toString().toCharArray(dest, destoff, start, end).let { }

    override fun setSpan(what: Any, start: Int, end: Int, flags: Int) {
        sets++
        spans.removeAll { it.what === what }
        spans += Span(what, start, end, flags)
    }
    override fun removeSpan(what: Any) { removes++; spans.removeAll { it.what === what } }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any?> getSpans(start: Int, end: Int, type: Class<T>?): Array<T> {
        // 和 SpannableStringBuilder.getSpans 的规则一样：非空样式只在端点相接时不算，空样式或空查询相接也算
        val hits = spans.filter { s ->
            type!!.isInstance(s.what) && s.start <= end && s.end >= start &&
                !(s.start != s.end && start != end && (s.start == end || s.end == start))
        }.map { it.what }
        val arr = java.lang.reflect.Array.newInstance(type!!, hits.size) as Array<T>
        hits.forEachIndexed { i, w -> arr[i] = w as T }
        return arr
    }
    override fun getSpanStart(tag: Any) = spans.firstOrNull { it.what === tag }?.start ?: -1
    override fun getSpanEnd(tag: Any) = spans.firstOrNull { it.what === tag }?.end ?: -1
    override fun getSpanFlags(tag: Any) = spans.firstOrNull { it.what === tag }?.flags ?: 0
    override fun nextSpanTransition(start: Int, limit: Int, type: Class<*>?) = limit

    override fun replace(st: Int, en: Int, source: CharSequence, start: Int, end: Int): Editable { sb.replace(st, en, source.substring(start, end)); return this }
    override fun replace(st: Int, en: Int, text: CharSequence) = replace(st, en, text, 0, text.length)
    override fun insert(where: Int, text: CharSequence, start: Int, end: Int) = replace(where, where, text, start, end)
    override fun insert(where: Int, text: CharSequence) = replace(where, where, text)
    override fun delete(st: Int, en: Int) = replace(st, en, "")
    override fun append(text: CharSequence?) = replace(length, length, text ?: "null")
    override fun append(text: CharSequence?, start: Int, end: Int) = replace(length, length, text ?: "null", start, end)
    override fun append(text: Char) = append(text.toString())
    override fun clear() { sb.setLength(0) }
    override fun clearSpans() { spans.clear() }
    override fun setFilters(filters: Array<out InputFilter>?) = Unit
    override fun getFilters(): Array<InputFilter> = emptyArray()
}
