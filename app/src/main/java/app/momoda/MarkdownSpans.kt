package app.momoda

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.text.Editable
import android.text.Layout
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineBackgroundSpan
import android.text.style.MetricAffectingSpan

/** 墨墨打自己加的 Markdown 样式。实现了这个接口的 Span 不会被“去掉外来样式”清理掉。 */
interface MdSpan {
    /** 类型和参数都相同的两个样式可以互相替代：重排时已有的就留着，不删掉重加（见 [MarkdownStyler]）。 */
    val key: Any get() = javaClass
}

private const val MARKER_GRAY = 0xFF9A9A9A.toInt()
private const val RULE_GRAY = 0xFF888888.toInt()

/**
 * 专注模式：正文整体是浅灰色，这个 Span 把光标所在的段落染回黑色。
 * 光标换段时只移动这一个 Span，墨水屏上只有新旧两段的颜色变化。
 * 它的优先级更高，先被应用，所以段内的 [MarkerSpan] 仍然是灰色。
 */
class FocusSpan : ForegroundColorSpan(Color.BLACK)

/** 标记符号淡显。 */
class MarkerSpan : ForegroundColorSpan(MARKER_GRAY), MdSpan

/** 标题：加大并加粗（霞鹜文楷只有一个字重，用合成粗体）。 */
class HeadingSpan(private val scale: Float) : MetricAffectingSpan(), MdSpan {
    override val key get() = listOf(javaClass, scale)
    override fun updateDrawState(tp: TextPaint) = apply(tp)
    override fun updateMeasureState(tp: TextPaint) = apply(tp)
    private fun apply(tp: TextPaint) {
        tp.textSize *= scale
        tp.isFakeBoldText = true
    }
}

class BoldSpan : MetricAffectingSpan(), MdSpan {
    override fun updateDrawState(tp: TextPaint) { tp.isFakeBoldText = true }
    override fun updateMeasureState(tp: TextPaint) { tp.isFakeBoldText = true }
}

/** 斜体：字体没有斜体字形，用倾斜模拟。 */
class ItalicSpan : MetricAffectingSpan(), MdSpan {
    override fun updateDrawState(tp: TextPaint) { tp.textSkewX = -0.18f }
    override fun updateMeasureState(tp: TextPaint) { tp.textSkewX = -0.18f }
}

/** 引用：左侧一条竖线，正文右移。 */
class QuoteBarSpan(private val margin: Int, private val barWidth: Int) : LeadingMarginSpan, MdSpan {
    override val key get() = listOf(javaClass, margin, barWidth)
    override fun getLeadingMargin(first: Boolean) = margin
    override fun drawLeadingMargin(
        c: Canvas, p: Paint, x: Int, dir: Int, top: Int, baseline: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int, first: Boolean, layout: Layout,
    ) {
        val style = p.style
        val color = p.color
        p.style = Paint.Style.FILL
        p.color = RULE_GRAY
        c.drawRect(x.toFloat(), top.toFloat(), (x + dir * barWidth).toFloat(), bottom.toFloat(), p)
        p.style = style
        p.color = color
    }
}

/** 列表悬挂缩进：折行后的文字和第一行的正文对齐，而不是和 `- ` 对齐。 */
class HangingIndentSpan(private val rest: Int) : LeadingMarginSpan, MdSpan {
    override val key get() = listOf(javaClass, rest)
    override fun getLeadingMargin(first: Boolean) = if (first) 0 else rest
    override fun drawLeadingMargin(
        c: Canvas, p: Paint, x: Int, dir: Int, top: Int, baseline: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int, first: Boolean, layout: Layout,
    ) = Unit
}

/**
 * 中文语境里的引号用全角字形（OpenType `fwid`）。霞鹜文楷默认把 “” 排成西文宽度，
 * 夹在汉字中间显得局促。只作用于紧挨着汉字的引号，英文里的引号保持原样。
 */
class FullWidthQuoteSpan : MetricAffectingSpan(), MdSpan {
    override fun updateDrawState(tp: TextPaint) { tp.fontFeatureSettings = "'fwid'" }
    override fun updateMeasureState(tp: TextPaint) { tp.fontFeatureSettings = "'fwid'" }
}

/** 段首缩进两字。 */
class IndentSpan(private val width: Int) : LeadingMarginSpan, MdSpan {
    override val key get() = listOf(javaClass, width)
    override fun getLeadingMargin(first: Boolean) = if (first) width else 0
    override fun drawLeadingMargin(
        c: Canvas, p: Paint, x: Int, dir: Int, top: Int, baseline: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int, first: Boolean, layout: Layout,
    ) = Unit
}

/**
 * 分隔线：画一条和 `-` 字形同一高度、同样粗细、同样灰度的横线，让 `---` 融进线里，
 * 看上去只有一条线；标记本身仍在，光标移上去照样可以编辑。
 * （按文字的大致中线画时，霞鹜文楷的 `-` 偏下，会在线的下方露出三个短横。）
 */
class RuleSpan(private val minThickness: Float) : LineBackgroundSpan, MdSpan {
    override val key get() = listOf(javaClass, minThickness)
    private val dash = Rect()

    override fun drawBackground(
        c: Canvas, p: Paint, left: Int, right: Int, top: Int, baseline: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int, lineNumber: Int,
    ) {
        val color = p.color
        p.color = MARKER_GRAY
        p.getTextBounds("-", 0, 1, dash)
        val y = baseline + dash.exactCenterY()
        val half = maxOf(minThickness, dash.height().toFloat()) / 2
        c.drawRect(left.toFloat(), y - half, right.toFloat(), y + half, p)
        p.color = color
    }
}

/** 按行把 [Markdown.parseLine] 的结果挂到 Editable 上。 */
class MarkdownStyler(private val dp: Float, private val measure: (CharSequence) -> Float) {

    /** 普通段落段首缩进两字（标题、列表、引用、分隔线不缩进）。 */
    var indent = false

    /** 重新渲染与 [from, to) 有交集的所有行。 */
    fun restyle(text: Editable, from: Int, to: Int) {
        var lineStart = lineStartOf(text, from.coerceIn(0, text.length))
        var stop = to.coerceIn(0, text.length)
        // 编辑会把样式拉长到相邻几行：SpannableStringBuilder 把段落样式的端点挪到下一个换行之后
        // （在引用行末回车、再在空的“> ”上回车），在标题、粗体中间回车时字符样式也跟着跨到下一行。
        // styleLine 只处理落在本行内的样式，拉长的会留下来：引用竖线叠成好几条、延伸到下面的段落，
        // 回车后的下一行仍显示成标题。所以把拉长的样式删掉，它覆盖的行全部重排。
        while (true) {
            val stretched = text.getSpans(lineStart, stop, MdSpan::class.java).filter { span ->
                text.getSpanEnd(span) > lineStart && text.getSpanStart(span) < stop &&
                    (text.getSpanStart(span) < lineStart || text.getSpanEnd(span) > stop)
            }
            if (stretched.isEmpty()) break
            for (span in stretched) {
                lineStart = minOf(lineStart, lineStartOf(text, text.getSpanStart(span)))
                stop = maxOf(stop, text.getSpanEnd(span))
            }
        }
        for (span in text.getSpans(lineStart, stop, MdSpan::class.java)) {
            // 跨了行：中间夹着换行（段落样式本来就包含行尾的那个换行）
            if (text.indexOf('\n', text.getSpanStart(span)) in 0 until text.getSpanEnd(span) - 1) text.removeSpan(span)
        }
        while (true) {
            val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
            styleLine(text, lineStart, lineEnd)
            if (lineEnd >= stop || lineEnd >= text.length) break
            lineStart = lineEnd + 1
        }
    }

    fun restyleAll(text: Editable) = restyle(text, 0, text.length)

    /** 本行应有的样式。 */
    private class Want(val span: MdSpan, val start: Int, val end: Int, val flags: Int)

    /**
     * 把一行的样式改成应有的样子。已有的样式类型、参数、位置都对的就留着，只删多余的、只加缺的：
     * 每删、加一个影响排版的样式（标题、粗体、全角引号），DynamicLayout 都要把这一段重排一遍。
     * 在一段满是中文引号的长段落里打一个字，如果全部删掉重加，就要重排几十遍（长文档里每个字 1 秒以上）。
     */
    private fun styleLine(text: Editable, start: Int, end: Int) {
        // 段落样式要覆盖到行尾的换行符
        val paraEnd = if (end < text.length) end + 1 else end
        val old = text.getSpans(start, paraEnd, MdSpan::class.java).filterTo(mutableListOf()) { span ->
            val s = text.getSpanStart(span)
            val e = text.getSpanEnd(span)
            // getSpans 会带上只是“碰到”边界的相邻行的样式，只管落在本行内的
            s >= start && e <= paraEnd && !(s == e && s == paraEnd && paraEnd != end)
        }
        val want = wanted(text, start, end, paraEnd)
        for (w in want) {
            val same = old.indexOfFirst { o ->
                o.key == w.span.key && text.getSpanStart(o) == w.start && text.getSpanEnd(o) == w.end &&
                    text.getSpanFlags(o) and Spanned.SPAN_POINT_MARK_MASK == w.flags
            }
            if (same >= 0) old.removeAt(same) else text.setSpan(w.span, w.start, w.end, w.flags)
        }
        for (o in old) text.removeSpan(o)
    }

    private fun wanted(text: Editable, start: Int, end: Int, paraEnd: Int): List<Want> {
        val out = mutableListOf<Want>()
        fun char(span: MdSpan, s: Int, e: Int) { if (e > s) out += Want(span, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        fun para(span: MdSpan, s: Int, e: Int) { if (e > s) out += Want(span, s, e, Spanned.SPAN_PARAGRAPH) }
        if (end <= start) return out
        val line = text.subSequence(start, end).toString()
        val styles = Markdown.parseLine(line)
        if (indent && line.isNotBlank() && styles.none { it.kind in BLOCK_KINDS }) {
            para(IndentSpan(measure("中中").toInt()), start, paraEnd)
        }
        for (i in line.indices) {
            val c = line[i]
            if (c == '“' || c == '”' || c == '‘' || c == '’') {
                val before = line.getOrNull(i - 1)
                val after = line.getOrNull(i + 1)
                if ((before != null && TextStats.isCjk(before)) || (after != null && TextStats.isCjk(after))) {
                    char(FullWidthQuoteSpan(), start + i, start + i + 1)
                }
            }
        }
        for (st in styles) {
            val s = start + st.start
            val e = start + st.end
            when (st.kind) {
                Markdown.Kind.MARKER -> char(MarkerSpan(), s, e)
                Markdown.Kind.HEADING1 -> char(HeadingSpan(1.35f), s, e)
                Markdown.Kind.HEADING2 -> char(HeadingSpan(1.2f), s, e)
                Markdown.Kind.HEADING3 -> char(HeadingSpan(1.08f), s, e)
                Markdown.Kind.BOLD -> char(BoldSpan(), s, e)
                Markdown.Kind.ITALIC -> char(ItalicSpan(), s, e)
                Markdown.Kind.QUOTE -> para(QuoteBarSpan((14 * dp).toInt(), (3 * dp).toInt()), start, paraEnd)
                Markdown.Kind.LIST_ITEM -> para(HangingIndentSpan(measure(line.substring(0, st.end)).toInt()), start, paraEnd)
                Markdown.Kind.RULE -> para(RuleSpan(1f * dp), start, paraEnd)
            }
        }
        return out
    }

    private companion object {
        val BLOCK_KINDS = setOf(
            Markdown.Kind.HEADING1, Markdown.Kind.HEADING2, Markdown.Kind.HEADING3,
            Markdown.Kind.QUOTE, Markdown.Kind.LIST_ITEM, Markdown.Kind.RULE,
        )
    }

    private fun lineStartOf(text: CharSequence, pos: Int): Int {
        var i = pos - 1
        while (i >= 0 && text[i] != '\n') i--
        return i + 1
    }
}
