package app.momoda

/** 首行即标题；文件名跟随标题。纯逻辑，便于单测。 */
object Titles {

    private val headingPrefix = Regex("^#{1,6}\\s*")
    private val emphasis = Regex("[*_`]+")
    private val illegal = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")
    private const val MAX_CHARS = 60

    /** 第一个非空行去掉 `#` 和强调符号后的文字；没有内容时返回 null（未命名）。 */
    fun titleOf(text: CharSequence): String? {
        val first = text.lineSequence().firstOrNull { it.isNotBlank() } ?: return null
        val t = first.trim().replace(headingPrefix, "").replace(emphasis, "").trim()
        return t.ifBlank { null }
    }

    /**
     * 把首行换成新标题：首行是标题行时保留它的 `#` 级别，否则把首行改成一级标题。
     * （“首行即标题”：改标题就是改首行。）
     */
    fun withTitle(text: String, newTitle: String): String {
        val firstLineEnd = text.indexOf('\n').let { if (it < 0) text.length else it }
        val marker = headingPrefix.find(text.substring(0, firstLineEnd))?.value?.trimEnd()?.ifEmpty { null } ?: "#"
        return "$marker ${newTitle.trim()}" + text.substring(firstLineEnd)
    }

    /** 摘要：标题之后的前几行正文，去掉 Markdown 标记，压成一行。 */
    fun excerptOf(text: String, maxChars: Int = 60): String {
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        val body = if (lines.isNotEmpty() && titleOf(lines.first()) != null) lines.drop(1) else lines
        val joined = body.joinToString(" ") { stripMarkers(it).trim() }
        return if (joined.length > maxChars) joined.take(maxChars) + "…" else joined
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
