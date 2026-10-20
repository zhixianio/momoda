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
interface MdSpan

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
        val stop = to.coerceIn(0, text.length)
        while (true) {
            val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
            styleLine(text, lineStart, lineEnd)
            if (lineEnd >= stop || lineEnd >= text.length) break
            lineStart = lineEnd + 1
        }
    }

    fun restyleAll(text: Editable) = restyle(text, 0, text.length)

    private fun styleLine(text: Editable, start: Int, end: Int) {
        // 段落样式要覆盖到行尾的换行符
        val paraEnd = if (end < text.length) end + 1 else end
        for (span in text.getSpans(start, paraEnd, MdSpan::class.java)) {
            val s = text.getSpanStart(span)
            val e = text.getSpanEnd(span)
            // getSpans 会带上只是“碰到”边界的相邻行的样式，只清理落在本行内的
            if (s >= start && e <= paraEnd && !(s == e && s == paraEnd && paraEnd != end)) text.removeSpan(span)
        }
        if (end <= start) return
        val line = text.subSequence(start, end).toString()
        val styles = Markdown.parseLine(line)
        if (indent && line.isNotBlank() && styles.none { it.kind in BLOCK_KINDS }) {
            para(text, IndentSpan(measure("中中").toInt()), start, paraEnd)
        }
        for (i in line.indices) {
            val c = line[i]
            if (c == '“' || c == '”' || c == '‘' || c == '’') {
                val before = line.getOrNull(i - 1)
                val after = line.getOrNull(i + 1)
                if ((before != null && TextStats.isCjk(before)) || (after != null && TextStats.isCjk(after))) {
                    char(text, FullWidthQuoteSpan(), start + i, start + i + 1)
                }
            }
        }
        for (st in styles) {
            val s = start + st.start
            val e = start + st.end
            when (st.kind) {
                Markdown.Kind.MARKER -> char(text, MarkerSpan(), s, e)
                Markdown.Kind.HEADING1 -> char(text, HeadingSpan(1.35f), s, e)
                Markdown.Kind.HEADING2 -> char(text, HeadingSpan(1.2f), s, e)
                Markdown.Kind.HEADING3 -> char(text, HeadingSpan(1.08f), s, e)
                Markdown.Kind.BOLD -> char(text, BoldSpan(), s, e)
                Markdown.Kind.ITALIC -> char(text, ItalicSpan(), s, e)
                Markdown.Kind.QUOTE -> para(text, QuoteBarSpan((14 * dp).toInt(), (3 * dp).toInt()), start, paraEnd)
                Markdown.Kind.LIST_ITEM -> para(text, HangingIndentSpan(measure(line.substring(0, st.end)).toInt()), start, paraEnd)
                Markdown.Kind.RULE -> para(text, RuleSpan(1f * dp), start, paraEnd)
            }
        }
    }

    private fun char(text: Editable, span: Any, s: Int, e: Int) {
        if (e > s) text.setSpan(span, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun para(text: Editable, span: Any, s: Int, e: Int) {
        if (e > s) text.setSpan(span, s, e, Spanned.SPAN_PARAGRAPH)
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
