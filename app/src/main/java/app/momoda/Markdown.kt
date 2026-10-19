package app.momoda

/**
 * 轻量 Markdown 解析（DESIGN §2.2）：只认写作常用的语法，按“行”解析，输出样式区间。
 * 纯逻辑，便于单测；EditorView 把结果变成 Span。
 *
 * 渲染策略是“标记淡显”：`#`、`**`、`>`、`-` 等标记始终可见，只是变成浅灰色；正文按格式渲染。
 */
object Markdown {

    enum class Kind { MARKER, HEADING1, HEADING2, HEADING3, BOLD, ITALIC, QUOTE, LIST_ITEM, RULE }

    /** [start, end) 是相对行首的偏移；LIST_ITEM 的 [end] 是标记的宽度（用于悬挂缩进）。 */
    data class Style(val kind: Kind, val start: Int, val end: Int)

    private val heading = Regex("^(#{1,3})(\\s+)(.*)$")
    private val quote = Regex("^(>\\s?)")
    private val listMarker = Regex("^(\\s*)([-*+]|\\d+[.)])(\\s+)")
    private val rule = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")
    private val bold = Regex("(\\*\\*|__)(?=\\S)(.+?)(?<=\\S)\\1")
    private val italic = Regex("(?<![*_\\w])([*_])(?=[^\\s*_])(.+?)(?<=[^\\s*_])\\1(?![*_\\w])")

    fun parseLine(line: String): List<Style> {
        val out = mutableListOf<Style>()

        if (rule.matches(line)) {
            out += Style(Kind.MARKER, 0, line.length)
            out += Style(Kind.RULE, 0, line.length)
            return out
        }

        heading.find(line)?.let { m ->
            val markerEnd = m.groups[1]!!.range.last + 1 + m.groups[2]!!.value.length
            val kind = when (m.groups[1]!!.value.length) { 1 -> Kind.HEADING1; 2 -> Kind.HEADING2; else -> Kind.HEADING3 }
            out += Style(Kind.MARKER, 0, markerEnd)
            if (markerEnd < line.length) out += Style(kind, markerEnd, line.length)
            inline(line, markerEnd, out)
            return out
        }

        var bodyStart = 0
        quote.find(line)?.let { m ->
            out += Style(Kind.MARKER, 0, m.value.length)
            out += Style(Kind.QUOTE, 0, line.length)
            bodyStart = m.value.length
        }
        listMarker.find(line.substring(bodyStart))?.let { m ->
            val s = bodyStart + m.groups[2]!!.range.first
            val e = bodyStart + m.value.length
            out += Style(Kind.MARKER, s, e)
            out += Style(Kind.LIST_ITEM, 0, e)
            bodyStart = e
        }
        inline(line, bodyStart, out)
        return out
    }

    /** 行内的粗体、斜体。先找粗体，再在剩下的部分找斜体，避免 `**` 被当成两个斜体标记。 */
    private fun inline(line: String, from: Int, out: MutableList<Style>) {
        if (from >= line.length) return
        val taken = BooleanArray(line.length)
        for (m in bold.findAll(line, from)) {
            val r = m.range
            val ml = m.groups[1]!!.value.length
            out += Style(Kind.MARKER, r.first, r.first + ml)
            out += Style(Kind.BOLD, r.first + ml, r.last + 1 - ml)
            out += Style(Kind.MARKER, r.last + 1 - ml, r.last + 1)
            for (i in r) taken[i] = true
        }
        for (m in italic.findAll(line, from)) {
            val r = m.range
            if (r.any { taken[it] }) continue
            out += Style(Kind.MARKER, r.first, r.first + 1)
            out += Style(Kind.ITALIC, r.first + 1, r.last)
            out += Style(Kind.MARKER, r.last, r.last + 1)
        }
    }
}
