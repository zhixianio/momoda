package app.momoda

/**
 * 输入法上屏之后的整理（不依赖输入法）：
 * - 中文引号：中文语境里打出的引号换成选定的样式，开、闭自动配对；
 * - 中英文之间自动加空格：汉字和英文字母、数字相邻时补一个空格。
 * 代码里（`` ` `` 包着的、``` 围起来的代码块）不处理。纯逻辑，输入是插入之后的全文和插入的范围。
 */
object TypingRules {

    enum class QuoteStyle(val label: String, val open2: Char, val close2: Char, val open1: Char, val close1: Char) {
        KEEP("不变", '"', '"', '\'', '\''),
        CURLY("弯引号 “”", '“', '”', '‘', '’'),
        CORNER("直角引号 「」", '「', '」', '『', '』'),
    }

    private const val DOUBLE = "\"“”「」"
    private const val SINGLE = "'‘’『』"
    private const val OPENERS = "“「‘『"
    private const val CLOSERS = "”」’』"

    fun isCjk(c: Char): Boolean {
        val script = Character.UnicodeScript.of(c.code)
        return script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA ||
            script == Character.UnicodeScript.KATAKANA
    }

    /** 中文标点（全角标点、CJK 符号）：引号前面是它们时也算中文语境。 */
    private fun isCjkPunct(c: Char): Boolean {
        val block = Character.UnicodeBlock.of(c)
        return block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
            block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS || c in OPENERS || c in CLOSERS || c == '—' || c == '…'
    }

    fun isLatin(c: Char) = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9'

    /** [pos] 处是不是代码：同一段里前面有奇数个反引号，或者在 ``` 代码块里。 */
    fun inCode(t: CharSequence, pos: Int): Boolean {
        val lineStart = VimText.lineStart(t, pos)
        var ticks = 0
        for (i in lineStart until pos.coerceAtMost(t.length)) if (t[i] == '`') ticks++
        if (ticks % 2 == 1) return true
        if (!t.contains("```")) return false
        var fences = 0
        var i = 0
        while (i < lineStart) {
            if (t.startsWith("```", i)) fences++
            val nl = t.indexOf('\n', i)
            if (nl < 0) break
            i = nl + 1
        }
        return fences % 2 == 1
    }

    /**
     * 刚在 [start, end) 插入了文字之后要做的整理；不需要时返回 null。
     * 返回的 [Edit] 替换 [start-1, end+1) 附近的一小段，光标放在插入的文字后面。
     */
    fun afterInsert(t: CharSequence, start: Int, end: Int, quotes: QuoteStyle, autoSpace: Boolean): Edit? {
        if (start < 0 || end > t.length || start >= end || inCode(t, start)) return null
        val inserted = StringBuilder(t.subSequence(start, end))
        // 引号：只处理单独打出的一个引号字符
        if (quotes != QuoteStyle.KEEP && inserted.length == 1 && (inserted[0] in DOUBLE || inserted[0] in SINGLE)) {
            quoteFor(t, start, inserted[0], quotes)?.let { inserted.setCharAt(0, it) }
        }
        var before = ""
        var after = ""
        if (autoSpace) {
            val prev = if (start > 0) t[start - 1] else null
            val next = if (end < t.length) t[end] else null
            if (prev != null && needsSpace(prev, inserted.first())) before = " "
            if (next != null && needsSpace(inserted.last(), next)) after = " "
        }
        val changed = before.isNotEmpty() || after.isNotEmpty() || inserted.toString() != t.subSequence(start, end).toString()
        if (!changed) return null
        val text = before + inserted + after
        return Edit(start, end, text, start + before.length + inserted.length)
    }

    private fun needsSpace(a: Char, b: Char) = (isCjk(a) && isLatin(b)) || (isLatin(a) && isCjk(b))

    /** 在 [pos] 打出的引号 [typed] 应当换成哪个字符；不是中文语境时返回 null（保持原样）。 */
    private fun quoteFor(t: CharSequence, pos: Int, typed: Char, style: QuoteStyle): Char? {
        val double = typed in DOUBLE
        // 输入法给的中文引号一定是中文语境；英文直引号要看前面是不是中文
        if (typed == '"' || typed == '\'') {
            var i = pos - 1
            while (i >= 0 && t[i] == ' ') i--
            val chinese = i < 0 || t[i] == '\n' || isCjk(t[i]) || isCjkPunct(t[i])
            if (!chinese) return null
        }
        // 开还是闭：同一段里前面有没配对的同类开引号，就是闭引号
        val opener = if (double) "“「" else "‘『"
        val closer = if (double) "”」" else "’』"
        var open = 0
        var i = pos - 1
        while (i >= 0 && t[i] != '\n') {
            when (t[i]) {
                in opener -> open++
                in closer -> open--
            }
            i--
        }
        val close = open > 0
        return when {
            double && close -> style.close2
            double -> style.open2
            close -> style.close1
            else -> style.open1
        }
    }
}
