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
