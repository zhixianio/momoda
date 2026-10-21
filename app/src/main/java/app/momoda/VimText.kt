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

    /** [i] 是不是段距空行（紧跟文字行后的第一个空行，见 EditorView.BlankLineSpacing）的开头。 */
    fun isGapLineStart(t: CharSequence, i: Int): Boolean =
        i in 2 until t.length && t[i] == '\n' && t[i - 1] == '\n' && t[i - 2] != '\n'

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
        return Triple(e, "\n\n$content", e + 2)
    }

    /** P：在光标所在段前面另起一段。 */
    fun pasteLinesBefore(t: CharSequence, at: Int, content: String): Triple<Int, String, Int> {
        val s = lineStart(t, at)
        return Triple(s, "$content\n\n", s)
    }
}
