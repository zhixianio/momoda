package app.momoda

/**
 * 输入法上屏之后的整理（不依赖输入法）：
 * - 方括号键输入直角引号「」『』；
 * - 中英文之间自动加空格：汉字和英文字母、数字相邻时补一个空格。
 * 代码里（`` ` `` 包着的、``` 围起来的代码块）不处理。纯逻辑，输入是插入之后的全文和插入的范围。
 */
object TypingRules {

    fun isCjk(c: Char): Boolean {
        val script = Character.UnicodeScript.of(c.code)
        return script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA ||
            script == Character.UnicodeScript.KATAKANA
    }

    /** 中文标点（全角标点、CJK 符号）。 */
    private fun isCjkPunct(c: Char): Boolean {
        val block = Character.UnicodeBlock.of(c)
        return block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
            block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS || c in "“”‘’—…"
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
     * - [cornerQuotes]：方括号键输入直角引号，像 macOS 的中文输入法。中文状态下输入法把 [ ] 打成【】，换成「」；
     *   { } 输入法不转换，在中文语境里（前面是汉字或中文标点）换成『』。英文状态下的 [ ] 不动，Markdown 链接照常能打。
     * - [autoSpace]：汉字和英文字母、数字相邻时补一个空格。
     * 返回的 [Edit] 替换插入的这一段（可能前后各多一个空格），光标放在插入的文字后面。
     */
    fun afterInsert(t: CharSequence, start: Int, end: Int, cornerQuotes: Boolean, autoSpace: Boolean): Edit? {
        if (start < 0 || end > t.length || start >= end || inCode(t, start)) return null
        val inserted = StringBuilder(t.subSequence(start, end))
        if (cornerQuotes && inserted.length == 1) {
            val c = inserted[0]
            val chinese = chineseContext(t, start)
            when {
                c == '【' -> inserted.setCharAt(0, '「')
                c == '】' -> inserted.setCharAt(0, '」')
                c == '{' && chinese -> inserted.setCharAt(0, '『')
                c == '}' && chinese -> inserted.setCharAt(0, '』')
            }
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
        return Edit(start, end, before + inserted + after, start + before.length + inserted.length)
    }

    private fun needsSpace(a: Char, b: Char) = (isCjk(a) && isLatin(b)) || (isLatin(a) && isCjk(b))

    /** [pos] 前面（跳过空格）是汉字、中文标点或段首。 */
    private fun chineseContext(t: CharSequence, pos: Int): Boolean {
        var i = pos - 1
        while (i >= 0 && t[i] == ' ') i--
        return i < 0 || t[i] == '\n' || isCjk(t[i]) || isCjkPunct(t[i]) || t[i] in "「」『』"
    }
}
