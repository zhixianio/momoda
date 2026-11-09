package app.momoda

import java.text.BreakIterator
import java.util.Locale

/**
 * Vim 里只和文字有关的部分：找词、算操作符的范围、整段的增删。不依赖排版，可以单测。
 *
 * “行”在这里指逻辑行（两个换行之间的一段文字），也就是 Markdown 里的一段或一个列表项。
 * 段落之间的空行（回车写入的 `\n\n`）算作分隔，删整段时连同分隔一起删，不留多余的空行。
 */
object VimText {

    /**
     * 操作符作用的范围 [start, end)。[linewise] 为 true 时是整段（dd、yy、cc 等）：
     * [start, end) 含段落分隔，[innerStart, innerEnd) 只是这几段的文字（cc 改的、yy 存的）。
     */
    data class Range(
        val start: Int,
        val end: Int,
        val linewise: Boolean = false,
        val innerStart: Int = start,
        val innerEnd: Int = end,
    )

    // 中文分词交给 ICU（Android 上 java.text 的实现就是 ICU，带中文词典）
    private val words: BreakIterator by lazy { BreakIterator.getWordInstance(Locale.CHINA) }

    fun isWordChar(c: Char) = c.isLetterOrDigit()

    fun lineStart(t: CharSequence, at: Int): Int {
        var i = at.coerceIn(0, t.length) - 1
        while (i >= 0 && t[i] != '\n') i--
        return i + 1
    }

    /** 逻辑行的末尾（不含换行）。 */
    fun lineEnd(t: CharSequence, at: Int): Int {
        var i = at.coerceIn(0, t.length)
        while (i < t.length && t[i] != '\n') i++
        return i
    }

    /** w：下一个词的开头。 */
    fun nextWordStart(t: CharSequence, from: Int): Int {
        if (from >= t.length) return t.length
        words.setText(t.toString())
        var pos = words.following(from)
        while (pos != BreakIterator.DONE && pos < t.length && !isWordChar(t[pos])) pos = words.next()
        return if (pos == BreakIterator.DONE) t.length else pos
    }

    /** b：上一个词的开头（光标在词中间时是这个词的开头）。 */
    fun prevWordStart(t: CharSequence, from: Int): Int {
        if (from <= 0) return 0
        words.setText(t.toString())
        var pos = words.preceding(from.coerceAtMost(t.length))
        while (pos != BreakIterator.DONE && pos > 0 && !isWordChar(t[pos])) pos = words.previous()
        return if (pos == BreakIterator.DONE) 0 else pos
    }

    /** e：这个词（或下一个词）的最后一个字。 */
    fun wordEnd(t: CharSequence, from: Int): Int {
        if (t.isEmpty()) return 0
        words.setText(t.toString())
        var start = (from + 1).coerceAtMost(t.length)
        while (start < t.length) {
            if (isWordChar(t[start])) {
                val end = words.following(start)
                return (if (end == BreakIterator.DONE) t.length else end) - 1
            }
            start++
        }
        return t.length - 1
    }

    /** iw / aw：光标所在的词（aw 连同后面的空白）。光标不在词上时是这一串空白或标点。 */
    fun wordObject(t: CharSequence, at: Int, around: Boolean): Range {
        if (t.isEmpty()) return Range(0, 0)
        val i = at.coerceIn(0, t.length - 1)
        words.setText(t.toString())
        val end0 = words.following(i).let { if (it == BreakIterator.DONE) t.length else it }
        val start = words.preceding(end0).let { if (it == BreakIterator.DONE) 0 else it }
        var end = end0
        if (around) while (end < t.length && t[end] == ' ') end++
        return Range(start, end)
    }

    /**
     * 操作符 + w 的范围。和 vim 一样：
     * - 不跨过行尾，最后一个词上 dw 只删到行尾，不会把下一段并上来；
     * - cw 在词上时等于 ce，只改到词尾，不吃掉后面的空格。
     */
    fun wordForward(t: CharSequence, from: Int, count: Int, change: Boolean): Range {
        var end = from
        repeat(count) { end = nextWordStart(t, end) }
        if (change && from < t.length && isWordChar(t[from])) {
            var e = from - 1
            repeat(count) { e = wordEnd(t, e) }
            end = e + 1
        }
        val lineEnd = lineEnd(t, from)
        if (end > lineEnd && lineEnd >= from) end = lineEnd
        return Range(from, end.coerceAtLeast(from))
    }

    /** 从 [from] 往后（不含 [from]）或往前找 [q]，忽略大小写，找到头就从另一头接着找；找不到返回 -1。 */
    fun find(t: CharSequence, q: String, from: Int, forward: Boolean): Int {
        val s = t.toString()
        if (q.isEmpty() || s.isEmpty()) return -1
        return if (forward) {
            s.indexOf(q, (from + 1).coerceAtMost(s.length), ignoreCase = true).takeIf { it >= 0 }
                ?: s.indexOf(q, 0, ignoreCase = true)
        } else {
            (if (from > 0) s.lastIndexOf(q, from - 1, ignoreCase = true) else -1).takeIf { it >= 0 }
                ?: s.lastIndexOf(q, s.length, ignoreCase = true)
        }
    }

    /** 最后一段（有字的那段）的开头。 */
    fun lastLineStart(t: CharSequence): Int {
        var i = t.length
        while (i > 0 && t[i - 1] == '\n') i--
        return lineStart(t, i)
    }

    /** 从 [at] 所在段往后（[delta] > 0）或往前数 [delta] 段，跳过段落之间的空行；到头就停。 */
    fun advanceLines(t: CharSequence, at: Int, delta: Int): Int {
        var pos = lineStart(t, at)
        repeat(kotlin.math.abs(delta)) {
            if (delta > 0) {
                var next = lineEnd(t, pos)
                while (next < t.length && t[next] == '\n') next++
                if (next < t.length) pos = next
            } else {
                var prev = pos
                while (prev > 0 && t[prev - 1] == '\n') prev--
                if (prev > 0) pos = lineStart(t, prev)
            }
        }
        return pos
    }

    /** 从光标所在段开始的 [count] 段（dd、yy、cc 的范围）。 */
    fun lines(t: CharSequence, at: Int, count: Int): Range = linesBetween(t, at, advanceLines(t, at, count - 1))

    /**
     * [a] 和 [b] 所在段之间（含两端）的整段，连同后面的分隔一起；
     * 删的是最后几段时，连同前面的分隔一起，不在文末留空行。
     */
    fun linesBetween(t: CharSequence, a: Int, b: Int): Range {
        val start = lineStart(t, minOf(a, b))
        val end = lineEnd(t, maxOf(a, b))
        var afterSep = end
        while (afterSep < t.length && t[afterSep] == '\n') afterSep++
        if (afterSep < t.length) return Range(start, afterSep, true, start, end)
        var beforeSep = start
        while (beforeSep > 0 && t[beforeSep - 1] == '\n') beforeSep--
        return Range(beforeSep, t.length, true, start, end)
    }

    /** 整段寄存器里存的文字：只要这几段本身，粘贴时再补上分隔。 */
    fun linesText(t: CharSequence, r: Range): String = t.subSequence(r.innerStart, r.innerEnd).toString()

    /** p：在光标所在段后面另起一段，返回（插入位置，插入的文字，插完后光标位置）。 */
    fun pasteLinesAfter(t: CharSequence, at: Int, content: String): Triple<Int, String, Int> {
        val e = lineEnd(t, at)
        val sep = separatorAround(t, at)
        return Triple(e, "$sep$content", e + sep.length)
    }

    /** P：在光标所在段前面另起一段。 */
    fun pasteLinesBefore(t: CharSequence, at: Int, content: String): Triple<Int, String, Int> {
        val s = lineStart(t, at)
        return Triple(s, "$content${separatorAround(t, at)}", s)
    }

    /**
     * 粘贴整行时用的分隔：光标所在行和上一行或下一行之间只隔一个换行（列表项、Shift+Enter 换的行）时也只用一个换行，
     * 粘贴的行留在同一个列表里；否则空一行，另起一段。
     */
    private fun separatorAround(t: CharSequence, at: Int): String {
        val s = lineStart(t, at)
        val e = lineEnd(t, at)
        val tightBelow = e + 1 < t.length && t[e] == '\n' && t[e + 1] != '\n'
        val tightAbove = s >= 2 && t[s - 1] == '\n' && t[s - 2] != '\n'
        return if (tightBelow || tightAbove) "\n" else "\n\n"
    }

    // ---------- f F t T：在本段里找一个字 ----------

    /**
     * 普通模式下按键不经过输入法，打不出中文标点：英文标点也匹配对应的中文标点，
     * 例如 `f,` 跳到下一个“，”，`f.` 跳到“。”，`f\\` 跳到“、”（中文键盘上反斜杠就是顿号）。
     */
    private val fullWidth = mapOf(
        ',' to "，", '.' to "。", ';' to "；", ':' to "：", '?' to "？", '!' to "！",
        '(' to "（", ')' to "）", '[' to "「【『", ']' to "」】』", '{' to "『", '}' to "』",
        '<' to "《〈", '>' to "》〉", '"' to "“”", '\'' to "‘’", '\\' to "、", '~' to "～", '-' to "—",
    )

    /**
     * [c] 算不算要找的 [target]：英文标点也匹配对应的中文标点，反过来也一样
     * （有的输入法，例如 ChromeOS 的拼音，会把逗号键直接变成“，”交过来），取离光标最近的那一个。
     */
    fun matches(c: Char, target: Char, readings: (Char) -> List<String>? = { null }): Boolean =
        c == target || fullWidth[target]?.contains(c) == true ||
            fullWidth.entries.any { (ascii, cjk) -> target in cjk && (c == ascii || c in cjk) } ||
            initialMatches(c, target, readings)

    /**
     * 小写字母也匹配拼音以它开头的汉字（多音字任一读音都算）：普通模式下打不出汉字，`fs` 就跳到下一个“是”“说”。
     * 照顾小鹤双拼的习惯：拼音里没有以 u、i、v 开头的音节，所以 u、i、v 分别找 sh、ch、zh 开头的字。
     */
    private fun initialMatches(c: Char, target: Char, readings: (Char) -> List<String>?): Boolean {
        if (target !in 'a'..'z' || !TypingRules.isCjk(c)) return false
        val prefix = when (target) { 'u' -> "sh"; 'i' -> "ch"; 'v' -> "zh"; else -> target.toString() }
        return readings(c)?.any { it.startsWith(prefix) } == true
    }

    /**
     * r 换成什么：光标处是中文标点时，英文标点键换成对应的中文标点（在“，”上按 r. 得到“。”）；
     * 其他情况原样（在字母上按 r, 就是英文逗号）。
     */
    fun replacementFor(old: Char, typed: Char): Char {
        val cjk = fullWidth[typed] ?: return typed
        return if (fullWidth.values.any { old in it }) cjk[0] else typed
    }

    /**
     * 从 [from] 往后（[forward]）或往前找本段里第 [n] 个 [target]，返回光标该停的位置；找不到返回 null。
     * [till] 是 t / T：停在那个字前面（往前找时是后面）。[repeat] 是 ; 和 , 重复 t / T：
     * 光标正好紧挨着目标时跳过它，找下一个，否则会原地不动。
     */
    fun findChar(
        t: CharSequence, from: Int, target: Char, forward: Boolean, till: Boolean, n: Int = 1, repeat: Boolean = false,
        /** 汉字的拼音读音（[Pinyin.readings]）：有的话小写字母也按拼音首字母找汉字。 */
        readings: (Char) -> List<String>? = { null },
    ): Int? {
        val s = lineStart(t, from)
        val e = lineEnd(t, from)
        var i = from
        // t 的重复：紧挨着的那个不算
        if (till && repeat) i += if (forward) 1 else -1
        var found = 0
        while (true) {
            i += if (forward) 1 else -1
            if (i < s || i >= e) return null
            if (matches(t[i], target, readings) && ++found == n) return if (!till) i else if (forward) i - 1 else i + 1
        }
    }

    /** d、c、y 接 f F t T 时作用的范围：往后找包含目标那个字，往前找不含光标所在的字（和 vim 一样）。 */
    fun findRange(from: Int, to: Int, forward: Boolean): Range =
        if (forward) Range(from, to + 1) else Range(to, from)

    /** ~：大小写互换（只影响有大小写的字母，汉字、标点不变）。 */
    fun toggleCase(s: CharSequence): String = buildString(s.length) {
        for (c in s) append(if (c.isUpperCase()) c.lowercaseChar() else if (c.isLowerCase()) c.uppercaseChar() else c)
    }

    // ---------- ( )：句子 ----------

    /** 句末标点；英文句点只在后面是空白或行尾时算（3.14、e.g. 里的不算）。 */
    private fun endsSentence(t: CharSequence, i: Int, lineEnd: Int): Boolean = when (t[i]) {
        '。', '！', '？', '!', '?', '…' -> true
        '.' -> i + 1 >= lineEnd || t[i + 1] == ' '
        else -> false
    }

    /** 跟在句末标点后面、还算这一句的：后引号、后括号。 */
    private const val CLOSERS = "”’」』）)》】\"'"

    /** 一句：[start, innerEnd) 是这句话，[innerEnd, end) 是后面的空格。 */
    private class Sentence(val start: Int, val innerEnd: Int, val end: Int)

    /** 逻辑行 [s, e) 里的句子（一段的结尾也是一句的结尾）。 */
    private fun sentencesIn(t: CharSequence, s: Int, e: Int): List<Sentence> {
        val out = mutableListOf<Sentence>()
        var i = s
        while (i < e && t[i] == ' ') i++
        while (i < e) {
            val start = i
            var j = i
            while (j < e && !endsSentence(t, j, e)) j++
            if (j < e) {
                j++
                while (j < e && (endsSentence(t, j, e) || t[j] in CLOSERS)) j++ // “？！”“……”“。」”
            }
            val innerEnd = j
            while (j < e && t[j] == ' ') j++
            out += Sentence(start, innerEnd, j)
            i = j
        }
        return out
    }

    /** ) ：下一句的开头；这一段没有了就是下一段的第一句；到头了是文末。 */
    fun nextSentenceStart(t: CharSequence, at: Int): Int {
        val s = lineStart(t, at)
        val e = lineEnd(t, at)
        sentencesIn(t, s, e).firstOrNull { it.start > at }?.let { return it.start }
        var i = e
        while (i < t.length && t[i] == '\n') i++
        if (i >= t.length) return lineEnd(t, lastLineStart(t)) // 最后一句：到文末（普通模式下光标再收到最后一个字上）
        while (i < t.length && t[i] == ' ') i++
        return i
    }

    /** ( ：这一句的开头；已经在句首时是上一句的开头（可以跨到上一段）。 */
    fun prevSentenceStart(t: CharSequence, at: Int): Int {
        val s = lineStart(t, at)
        sentencesIn(t, s, lineEnd(t, at)).lastOrNull { it.start < at }?.let { return it.start }
        var i = s - 1
        while (i >= 0 && t[i] == '\n') i--
        if (i < 0) return 0
        val ps = lineStart(t, i)
        return sentencesIn(t, ps, lineEnd(t, ps)).lastOrNull()?.start ?: ps
    }

    /** is / as：光标所在的句子（as 连同后面的空格）。 */
    fun sentenceObject(t: CharSequence, at: Int, around: Boolean): Range? {
        val s = lineStart(t, at)
        val all = sentencesIn(t, s, lineEnd(t, at))
        val one = all.firstOrNull { at < it.end } ?: all.lastOrNull() ?: return null
        return Range(one.start, if (around) one.end else one.innerEnd)
    }

    // ---------- { }：段落（连续的有字的行；列表算一整段） ----------

    /** [at] 所在的段落 [start, end)（不含结尾的换行）；[at] 在空行上时返回 null。 */
    fun paragraphAt(t: CharSequence, at: Int): IntRange? {
        val ls = lineStart(t, at)
        if (lineEnd(t, at) == ls) return null
        var start = ls
        while (start >= 2 && t[start - 1] == '\n' && t[start - 2] != '\n') start = lineStart(t, start - 1)
        var end = lineEnd(t, at)
        while (end + 1 < t.length && t[end] == '\n' && t[end + 1] != '\n') end = lineEnd(t, end + 1)
        return start until end
    }

    /** } ：下一段的开头；已经是最后一段时到这一段末尾。 */
    fun nextParagraphStart(t: CharSequence, at: Int): Int {
        var i = paragraphAt(t, at)?.let { it.last + 1 } ?: at
        val end = i
        while (i < t.length && t[i] == '\n') i++
        return if (i >= t.length) end.coerceAtMost(t.length) else i
    }

    /** { ：这一段的开头；已经在段首时是上一段的开头。 */
    fun prevParagraphStart(t: CharSequence, at: Int): Int {
        val here = paragraphAt(t, at)
        if (here != null && at > here.first) return here.first
        var i = (here?.first ?: at) - 1
        while (i >= 0 && t[i] == '\n') i--
        if (i < 0) return 0
        return paragraphAt(t, i)?.first ?: 0
    }

    /** ip / ap：光标所在的段落；ap 连同段落分隔（和 dd 一样，删掉不留多余空行）。 */
    fun paragraphObject(t: CharSequence, at: Int, around: Boolean): Range? {
        val p = paragraphAt(t, at) ?: return null
        return if (around) linesBetween(t, p.first, p.last + 1) else Range(p.first, p.last + 1)
    }

    // ---------- i( a( i" a" i「 等：括号、引号里面 ----------

    /**
     * 普通模式下打不出中文括号引号：英文的也匹配对应的中文的，取包着光标的最里面那一对。
     * 例如 `ci"` 改“”里的话，`di[` 删「」或【】里的，`ya(` 连（）一起复制。
     */
    private fun pairsFor(key: Char): List<String> = when (key) {
        '(', ')', 'b' -> listOf("()", "（）")
        '[', ']' -> listOf("[]", "「」", "【】")
        '{', '}', 'B' -> listOf("{}", "『』")
        '<', '>' -> listOf("<>", "《》", "〈〉")
        '"' -> listOf("\"\"", "“”")
        '\'' -> listOf("''", "‘’")
        else -> emptyList()
    }

    fun isPairKey(key: Char) = pairsFor(key).isNotEmpty()

    /** 包着 [at] 的一对括号或引号，只在本段里找；i 是里面，a 连括号一起。找不到返回 null。 */
    fun pairObject(t: CharSequence, at: Int, key: Char, around: Boolean): Range? {
        val s = lineStart(t, at)
        val e = lineEnd(t, at)
        if (s == e) return null // 空行（空文档、文末的空行）
        val found = pairsFor(key).mapNotNull { pair ->
            val (open, close) = pair[0] to pair[1]
            if (open == close) sameCharPair(t, at, open, s, e) else nestedPair(t, at, open, close, s, e)
        }
        // 先挑包着光标的最里面一对；都不包着（光标在第一对引号前面）时取光标后面最近的一对
        val (o, c) = found.filter { (o, c) -> at in o..c }.maxByOrNull { it.first }
            ?: found.minByOrNull { it.first } ?: return null
        return if (around) Range(o, c + 1) else Range(o + 1, c)
    }

    private fun nestedPair(t: CharSequence, at: Int, open: Char, close: Char, s: Int, e: Int): Pair<Int, Int>? {
        var depth = 0
        val from = at.coerceAtMost(e - 1)
        var o = if (t[from] == close) from - 1 else from
        while (o >= s) {
            if (t[o] == close) depth++
            else if (t[o] == open) { if (depth == 0) break; depth-- }
            o--
        }
        if (o < s) return null
        depth = 0
        var c = o + 1
        while (c < e) {
            if (t[c] == open) depth++
            else if (t[c] == close) { if (depth == 0) return o to c; depth-- }
            c++
        }
        return null
    }

    /**
     * 两边一样的引号（"" ''）：按本段里从头数，光标前有奇数个引号就是在一对里面；
     * 光标正好在右引号上时就是这一对；在引号外面时取光标后面的第一对。
     */
    private fun sameCharPair(t: CharSequence, at: Int, q: Char, s: Int, e: Int): Pair<Int, Int>? {
        val i = at.coerceAtMost(e - 1)
        val before = (s until i).filter { t[it] == q }
        if (before.size % 2 == 1) {
            if (t[i] == q) return before.last() to i // 光标在右引号上
            val c = (i until e).firstOrNull { t[it] == q } ?: return null
            return before.last() to c
        }
        val o = (i until e).firstOrNull { t[it] == q } ?: return null
        val c = (o + 1 until e).firstOrNull { t[it] == q } ?: return null
        return o to c
    }

    // ---------- J：把下一段接到这一段末尾 ----------

    /**
     * 把 [at] 所在这一段和下一段合成一段：去掉中间的换行（含段落间的空行）和下一段开头的空格。
     * 两边都是英文、数字时中间补一个空格，中文之间不加。没有下一段时返回 null。
     */
    fun joinNext(t: CharSequence, at: Int): Edit? {
        val e = lineEnd(t, at)
        var j = e
        while (j < t.length && t[j] == '\n') j++
        if (j >= t.length || j == e) return null
        while (j < t.length && t[j] == ' ') j++
        val left = if (e > lineStart(t, at)) t[e - 1] else ' '
        val right = if (j < t.length) t[j] else ' '
        val space = if (left != ' ' && right != '\n' && TypingRules.isLatin(left) && TypingRules.isLatin(right)) " " else ""
        return Edit(e, j, space, e)
    }
}
