package app.momoda

/** 首行即标题；文件名跟随标题。纯逻辑，便于单测。 */
object Titles {

    private val headingPrefix = Regex("^#{1,6}\\s*")
    private val emphasis = Regex("[*_`]+")
    private val illegal = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")
    private const val MAX_CHARS = 60

    /**
     * 开头 YAML 头（`---` 开始、`---` 或 `...` 结束，别的笔记应用常用）结束的位置；没有时是 0。
     * 认标题、取摘要都从这之后开始，YAML 头本身原样保留。
     */
    fun frontMatterEnd(text: CharSequence): Int {
        if (!(text.startsWith("---\n") || text.startsWith("---\r\n"))) return 0
        var i = text.indexOf('\n') + 1
        while (i in 1 until text.length) {
            val end = text.indexOf('\n', i).let { if (it < 0) text.length else it }
            val line = text.subSequence(i, end).toString().trimEnd('\r')
            if (line == "---" || line == "...") return (end + 1).coerceAtMost(text.length)
            i = end + 1
        }
        return 0 // 没有结束行：不当作 YAML 头
    }

    /** YAML 头之后第一个非空行去掉 `#` 和强调符号后的文字；没有内容时返回 null（未命名）。 */
    fun titleOf(text: CharSequence): String? {
        val body = text.subSequence(frontMatterEnd(text), text.length)
        val first = body.lineSequence().firstOrNull { it.isNotBlank() } ?: return null
        val t = first.trim().replace(headingPrefix, "").replace(emphasis, "").trim()
        return t.ifBlank { null }
    }

    /**
     * 把首行换成新标题：首行是标题行时保留它的 `#` 级别，否则把首行改成一级标题。
     * （“首行即标题”：改标题就是改首行。）
     */
    fun withTitle(text: String, newTitle: String): String {
        val head = text.substring(0, frontMatterEnd(text))
        val body = text.substring(head.length)
        val firstLineEnd = body.indexOf('\n').let { if (it < 0) body.length else it }
        val marker = headingPrefix.find(body.substring(0, firstLineEnd))?.value?.trimEnd()?.ifEmpty { null } ?: "#"
        return head + "$marker ${newTitle.trim()}" + body.substring(firstLineEnd)
    }

    /**
     * 文件名是不是墨墨打起的：墨墨打新建的（[createdHere]），或者文件名就是标题（跟着标题改过名的、
     * 欢迎文档这类）。日期名不能单凭名字判断：别的笔记应用的日记常常也叫 `2026-11-04.md`。
     */
    fun isMomodaName(name: String, title: String?, createdHere: Boolean): Boolean {
        if (createdHere) return true
        val base = baseNameFor(title ?: return false, "")
        if (base.isEmpty()) return false
        return name == base || name.matches(Regex(Regex.escape(base) + " \\d+"))
    }

    /** 摘要：标题之后的前几行正文，去掉 Markdown 标记，压成一行。 */
    fun excerptOf(text: String, maxChars: Int = 60): String {
        // 够长就停，不必解析整篇长文
        val joined = StringBuilder()
        var firstLine = true
        var count = 0
        for (line in text.substring(frontMatterEnd(text)).lineSequence()) {
            if (line.isBlank()) continue
            if (firstLine) { firstLine = false; if (titleOf(line) != null) continue }
            if (count++ > 0) joined.append(' ')
            joined.append(stripMarkers(line).trim())
            if (joined.length > maxChars) return joined.take(maxChars).toString() + "…"
        }
        return joined.toString()
    }

    /** 去掉 Markdown 解析器认出的标记（`#`、`**`、`- ` 等），其他字符原样保留，例如 `3 * 4`。 */
    private fun stripMarkers(line: String): String {
        val drop = BooleanArray(line.length)
        for (st in Markdown.parseLine(line)) if (st.kind == Markdown.Kind.MARKER) for (i in st.start until st.end) drop[i] = true
        return line.filterIndexed { i, _ -> !drop[i] }
    }

    /** 把标题变成安全的文件名主体（不含扩展名）；变空时用 [fallback]。 */
    fun baseNameFor(title: String?, fallback: String): String {
        val cleaned = (title ?: "")
            .replace(illegal, " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.')
            .trim()
        val limited = if (cleaned.codePointCount(0, cleaned.length) > MAX_CHARS) {
            cleaned.substring(0, cleaned.offsetByCodePoints(0, MAX_CHARS)).trim()
        } else cleaned
        return limited.ifBlank { fallback }
    }

    /** `base.md`、`base 2.md`、`base 3.md`……返回第一个 [taken] 为 false 的文件名。 */
    fun uniqueFileName(base: String, taken: (String) -> Boolean): String {
        if (!taken("$base.md")) return "$base.md"
        var n = 2
        while (taken("$base $n.md")) n++
        return "$base $n.md"
    }
}
