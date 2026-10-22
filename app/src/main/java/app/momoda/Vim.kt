package app.momoda

import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputMethodManager

/**
 * Vim 模式：normal 模式在输入法之前截获按键，Esc 回到 normal（对任何输入法都立即生效）。
 * 支持数字前缀；移动 hjkl w b e 0 $ gg G；操作符 d c y 接动作或 iw aw，dd cc yy D C Y；x X s p P u Ctrl+R；
 * 可视模式 v V；. 重复上一次修改；/ ? n N 搜索；进入输入 i a A I o O。只和文字有关的逻辑在 [VimText]。
 * j/k/0/$ 按屏幕上的视觉行移动（相当于 vim 的 gj/gk），更适合不分行的散文。
 */
class Vim(
    private val editor: EditorView,
    private val onModeChange: (Mode) -> Unit,
    /** 弹出单行输入框（/ 和 ? 搜索用），确定后回调输入的文字。 */
    private val prompt: (title: String, onDone: (String) -> Unit) -> Unit = { _, _ -> },
    private val toast: (String) -> Unit = {},
) {

    enum class Mode { INSERT, NORMAL, VISUAL, VISUAL_LINE }

    private companion object {
        /** 数字前缀的上限：误打一串数字（比如手机号）再接 j 或 p，不至于卡死或撑爆内存。 */
        const val MAX_COUNT = 9999
    }

    var enabled = false
        set(value) {
            field = value
            setMode(Mode.INSERT)
        }

    var mode = Mode.INSERT
        private set

    private val imm = editor.context.getSystemService(InputMethodManager::class.java)

    /** 返回 true 表示按键已被消费，不再交给输入法和编辑器。 */
    fun handle(keyCode: Int, event: KeyEvent): Boolean {
        if (!enabled) return false
        if (mode == Mode.INSERT) {
            // Esc 立刻切到 normal，但不消费：照样交给输入法，让它收起候选、清掉拼写。
            // 不能等输入法“放行”Esc 再切换：汉王输入法会吞掉所有 Esc，fcitx5 有联想时也会吞一次。
            if (keyCode == KeyEvent.KEYCODE_ESCAPE && event.action == KeyEvent.ACTION_DOWN) {
                finishInsertRecording()
                setMode(Mode.NORMAL)
                // 和 vim 一样，回到 normal 时光标退一格，停在刚打的最后一个字上（行首除外）
                if (cursor > 0 && text[cursor - 1] != '\n') move(cursor - 1)
                // 把输入法内部可能残留的拼音缓冲清掉，免得回到 insert 时接着拼
                editor.post { imm.restartInput(editor) }
            }
            return false
        }
        // NORMAL：吞掉所有按键（Ctrl 组合键已经在上游处理过）。
        // 例外是 Esc 的抬起，它的按下刚交给了输入法，抬起也给它，保持成对。
        // Esc 在 normal 模式下取消输了一半的命令（d、数字前缀等）
        if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                reset(); keys.clear(); changed = false
                if (mode != Mode.NORMAL) setMode(Mode.NORMAL)
            }
            return event.action == KeyEvent.ACTION_DOWN
        }
        if (event.action != KeyEvent.ACTION_DOWN) return true
        // 单独按下的 Shift、Alt 等不是命令：不然 d 之后按 Shift（为了打 $、G）会把 d 取消掉
        if (KeyEvent.isModifierKey(keyCode)) return true
        if (event.isCtrlPressed && keyCode == KeyEvent.KEYCODE_R) {
            editor.onKeyShortcut(KeyEvent.KEYCODE_Z, ctrlShift(event, KeyEvent.KEYCODE_Z))
            return true
        }
        // 忽略大写锁定：不小心开了 CapsLock，hjkl 会变成 HJKL，整个 normal 模式就像失灵了。大写命令照常用 Shift
        val ch = event.getUnicodeChar(event.metaState and KeyEvent.META_CAPS_LOCK_ON.inv()).toChar()
        if (mode == Mode.VISUAL || mode == Mode.VISUAL_LINE) {
            // 可视模式里的修改不记给 . 重复；别让它的 changed 漏到下一条普通命令上
            visual(ch, event); keys.clear(); changed = false
        } else command(ch, event)
        if (mode == Mode.NORMAL) stayOnChar()
        return true
    }

    // ---------- . 重复上一次修改 ----------

    /** 正在输入的这条命令的按键（含数字前缀）。 */
    private val keys = StringBuilder()
    /** 这条命令改了文字（或进入了输入模式）。 */
    private var changed = false
    /** 上一次修改的按键，以及它进入输入模式后打的字。 */
    private var lastChange = ""
    private var lastInserted: String? = null
    /** 进入输入模式时的位置：Esc 时把这之后打的字记下来，给 . 重放。 */
    private var insertStart = -1
    private var replaying = false

    /** normal 模式的一个按键：交给 [normal]，一条命令完整结束时记下它（如果它改了文字）。 */
    private fun command(ch: Char, event: KeyEvent) {
        if (ch == '.' && count == 0 && operator == null && pending == null) { repeatLast(event); return }
        keys.append(ch)
        normal(ch, event)
        val done = count == 0 && operator == null && pending == null
        if (!done) return
        if (changed && !replaying) {
            lastChange = keys.toString()
            lastInserted = null
            insertStart = if (mode == Mode.INSERT) cursor else -1
        }
        keys.clear()
        changed = false
    }

    private fun finishInsertRecording() {
        val s = insertStart
        insertStart = -1
        var c = cursor
        // 还没上屏的拼音（输入法随后会清掉）不算打的字
        val composing = BaseInputConnection.getComposingSpanStart(text)
        if (composing in s until c) c = composing
        if (s in 0..c && c <= text.length) lastInserted = text.subSequence(s, c).toString()
    }

    private fun repeatLast(event: KeyEvent) {
        if (lastChange.isEmpty()) return
        replaying = true
        try {
            for (k in lastChange) { normal(k, event); }
            if (mode == Mode.INSERT) {
                lastInserted?.let { text.insert(cursor, it); move(cursor) }
                setMode(Mode.NORMAL)
                if (cursor > 0 && text[cursor - 1] != '\n') move(cursor - 1)
            }
        } finally {
            replaying = false
            reset()
            keys.clear()
            changed = false
        }
    }

    // ---------- 可视模式 ----------

    /** 可视模式的起点；光标（[head]）是另一端。 */
    private var anchor = 0
    private var head = 0

    private fun enterVisual(m: Mode) {
        val c = cursor
        if (mode != Mode.VISUAL && mode != Mode.VISUAL_LINE) { anchor = c; head = c }
        setMode(m)
        showSelection()
    }

    /** 当前选中的范围；整段可视时是这几段（含分隔，见 [VimText.linesBetween]）。 */
    private fun selection(): VimText.Range {
        val t = text
        return if (mode == Mode.VISUAL_LINE) VimText.linesBetween(t, anchor, head)
        else VimText.Range(minOf(anchor, head), (maxOf(anchor, head) + 1).coerceAtMost(t.length))
    }

    private fun showSelection() {
        anchor = anchor.coerceIn(0, text.length)
        head = head.coerceIn(0, text.length)
        val r = selection()
        if (r.linewise) editor.setSelection(r.innerStart, r.innerEnd) else editor.setSelection(r.start, r.end)
        // 把光标那一端翻进屏幕
        editor.bringPointIntoView(head)
    }

    private fun visual(ch: Char, event: KeyEvent) {
        if (ch in '1'..'9' || (ch == '0' && count > 0)) { count = (count * 10 + (ch - '0')).coerceAtMost(MAX_COUNT); return }
        val n = count.coerceAtLeast(1)
        count = 0
        val p = pending
        pending = null
        when {
            ch == 'v' -> if (mode == Mode.VISUAL) exitVisual() else enterVisual(Mode.VISUAL)
            ch == 'V' -> if (mode == Mode.VISUAL_LINE) exitVisual() else enterVisual(Mode.VISUAL_LINE)
            ch == 'o' -> { val a = anchor; anchor = head; head = a; showSelection() }
            ch == 'd' || ch == 'x' -> { val r = selection(); exitVisual(); apply('d', r) }
            ch == 'c' || ch == 's' -> { val r = selection(); exitVisual(); apply('c', r) }
            ch == 'y' -> { val r = selection(); exitVisual(); apply('y', r) }
            ch == 'p' || ch == 'P' -> {
                val r = selection()
                val (reg, lines) = register to registerLines
                exitVisual()
                apply('d', r)
                register = reg; registerLines = lines
                paste(after = false, 1)
            }
            p == 'g' && ch == 'g' -> visualMove(n) { move(0) }
            ch == 'g' -> pending = 'g'
            // 只借用移动类按键；i、a 等在可视模式里另有含义（文本对象），这里先不支持
            ch in "hjklwbe0\$GnN" -> visualMove(n) { normal(ch, event) }
        }
    }

    /** 在可视模式里移动光标那一端：借用 normal 模式的移动，再把选区画出来。 */
    private inline fun visualMove(n: Int, step: () -> Unit) {
        val saved = mode
        mode = Mode.NORMAL
        editor.setSelection(head.coerceIn(0, text.length))
        count = if (n > 1) n else 0
        step()
        count = 0
        head = cursor
        mode = saved
        showSelection()
    }

    private fun exitVisual() {
        val h = head
        setMode(Mode.NORMAL)
        move(h)
    }

    // ---------- / ? n N 搜索 ----------

    private var lastSearch = ""
    private var lastSearchForward = true

    private fun search(forward: Boolean) {
        prompt(if (forward) "向后搜索" else "向前搜索") { q ->
            if (q.isEmpty()) return@prompt
            lastSearch = q
            lastSearchForward = forward
            findNext(forward)
        }
    }

    /** 找下一个；找不到时提示并返回 false。 */
    private fun findNext(forward: Boolean): Boolean {
        if (lastSearch.isEmpty()) return false
        val at = VimText.find(text, lastSearch, cursor, forward)
        if (at < 0) { toast("找不到“$lastSearch”"); return false }
        move(at)
        return true
    }

    // ---------- normal 模式：数字前缀、操作符（d c y）+ 动作或文本对象 ----------

    /** 正在输入的数字前缀；0 表示没有。 */
    private var count = 0
    /** 等待动作的操作符（d、c、y）和它前面的数字。 */
    private var operator: Char? = null
    private var operatorCount = 1
    /** 等待第二个键：g（gg）、i / a（iw、aw）。 */
    private var pending: Char? = null

    /** 寄存器：d、c、y、x 存进来，p、P 粘出去。[registerLines] 为 true 时是整段。 */
    private var register = ""
    private var registerLines = false

    private fun reset() { count = 0; operator = null; operatorCount = 1; pending = null }

    private fun normal(ch: Char, event: KeyEvent) {
        val t = text
        val c = cursor
        // 数字前缀：单独的 0 是“到行首”
        if (ch in '1'..'9' || (ch == '0' && count > 0)) { count = (count * 10 + (ch - '0')).coerceAtMost(MAX_COUNT); return }
        val n = count.coerceAtLeast(1)
        count = 0

        val p = pending
        pending = null
        val op = operator
        if (op != null) {
            val total = operatorCount * n
            val range = when {
                p == 'g' && ch == 'g' -> VimText.linesBetween(t, 0, c)
                p == 'i' && ch == 'w' -> VimText.wordObject(t, c, around = false)
                p == 'a' && ch == 'w' -> VimText.wordObject(t, c, around = true)
                p != null -> null
                ch == op -> VimText.lines(t, c, total)
                ch == 'g' || ch == 'i' || ch == 'a' -> { pending = ch; count = 0; return }
                else -> motionRange(ch, total, change = op == 'c')
            }
            operator = null
            if (range != null) apply(op, range)
            return
        }

        when {
            p == 'g' && ch == 'g' -> move(if (n > 1) VimText.advanceLines(t, 0, n - 1) else 0)
            p != null -> Unit
            ch == 'g' -> pending = 'g'
            ch == 'v' -> enterVisual(Mode.VISUAL)
            ch == 'V' -> enterVisual(Mode.VISUAL_LINE)
            ch == '/' -> search(forward = true)
            ch == '?' -> search(forward = false)
            ch == 'n' -> for (i in 0 until n) if (!findNext(lastSearchForward)) break
            ch == 'N' -> for (i in 0 until n) if (!findNext(!lastSearchForward)) break
            ch == 'd' || ch == 'c' || ch == 'y' -> { operator = ch; operatorCount = n }
            ch == 'D' -> apply('d', VimText.Range(c, VimText.lineEnd(t, c)))
            ch == 'C' -> apply('c', VimText.Range(c, VimText.lineEnd(t, c)))
            ch == 'Y' -> apply('y', VimText.lines(t, c, n))
            ch == 'h' -> repeat(n) { moveChar(-1) }
            ch == 'l' -> repeat(n) { moveChar(+1) }
            ch == 'j' -> repeat(n) { moveLine(+1) }
            ch == 'k' -> repeat(n) { moveLine(-1) }
            ch == 'w' -> { var to = c; repeat(n) { to = VimText.nextWordStart(t, to) }; move(to) }
            ch == 'b' -> { var to = c; repeat(n) { to = VimText.prevWordStart(t, to) }; move(to) }
            ch == 'e' -> { var to = c; repeat(n) { to = VimText.wordEnd(t, to) }; move(to) }
            ch == '0' -> layout?.let { move(it.getLineStart(it.getLineForOffset(c))) }
            ch == '$' -> layout?.let { move(lineEndExclusiveNewline(it.getLineForOffset(c))) }
            // G 停在最后一段的开头（文件末尾的换行后面那一行是空的，不算）
            ch == 'G' -> move(if (n > 1) VimText.advanceLines(t, 0, n - 1) else VimText.lastLineStart(t))
            // 和 vim 一样，x 不删换行：否则在空行上按 x 会把上下两段并成一段
            ch == 'x' -> apply('d', VimText.Range(c, minOf(c + n, VimText.lineEnd(t, c))))
            ch == 'X' -> apply('d', VimText.Range(maxOf(c - n, VimText.lineStart(t, c)), c))
            ch == 's' -> apply('c', VimText.Range(c, minOf(c + n, VimText.lineEnd(t, c))))
            ch == 'p' -> paste(after = true, n)
            ch == 'P' -> paste(after = false, n)
            ch == 'u' -> repeat(n) { editor.onKeyShortcut(KeyEvent.KEYCODE_Z, ctrl(event, KeyEvent.KEYCODE_Z)) }
            ch == 'i' -> { changed = true; setMode(Mode.INSERT) }
            ch == 'a' -> { changed = true; if (c < t.length && t[c] != '\n') move(c + 1); setMode(Mode.INSERT) }
            ch == 'A' -> { changed = true; move(VimText.lineEnd(t, c)); setMode(Mode.INSERT) }
            ch == 'I' -> { changed = true; move(VimText.lineStart(t, c)); setMode(Mode.INSERT) }
            // 新起的是一段（和回车一样空一行）：只插一个换行的话，新行会被当成段距空行压扁
            ch == 'o' -> { changed = true; val e = VimText.lineEnd(t, c); t.insert(e, "\n\n"); move(e + 2); setMode(Mode.INSERT) }
            ch == 'O' -> { changed = true; val s = VimText.lineStart(t, c); t.insert(s, "\n\n"); move(s); setMode(Mode.INSERT) }
        }
    }

    /** 操作符后面跟的动作对应的范围；不支持的动作返回 null（操作取消）。 */
    private fun motionRange(ch: Char, n: Int, change: Boolean): VimText.Range? {
        val t = text
        val c = cursor
        return when (ch) {
            'w' -> VimText.wordForward(t, c, n, change)
            'e' -> { var e = c; repeat(n) { e = VimText.wordEnd(t, e) }; VimText.Range(c, (e + 1).coerceAtMost(t.length)) }
            'b' -> { var b = c; repeat(n) { b = VimText.prevWordStart(t, b) }; VimText.Range(b, c) }
            'h' -> VimText.Range(maxOf(c - n, VimText.lineStart(t, c)), c)
            'l' -> VimText.Range(c, minOf(c + n, VimText.lineEnd(t, c)))
            '0' -> VimText.Range(VimText.lineStart(t, c), c)
            '$' -> VimText.Range(c, VimText.lineEnd(t, c))
            'j' -> VimText.linesBetween(t, c, VimText.advanceLines(t, c, n))
            'k' -> VimText.linesBetween(t, VimText.advanceLines(t, c, -n), c)
            'G' -> VimText.linesBetween(t, c, t.length)
            else -> null
        }
    }

    /** 对范围执行操作符：y 存进寄存器，d 存了再删，c 删掉后进入输入模式（整段时留下空行）。 */
    private fun apply(op: Char, r: VimText.Range) {
        val t = text
        if (r.end < r.start || r.end > t.length) return
        // 空范围（例如空行上的 x）什么都不做，也不清掉寄存器
        if (r.start == r.end && !r.linewise) { if (op == 'c') setMode(Mode.INSERT); return }
        register = if (r.linewise) VimText.linesText(t, r) else t.subSequence(r.start, r.end).toString()
        registerLines = r.linewise
        if (op != 'y') changed = true
        when (op) {
            'y' -> move(if (r.linewise) r.innerStart else r.start)
            'd' -> {
                t.delete(r.start, r.end)
                // 整段删掉后停在下一段开头（删的是最后几段时停在新的最后一段开头）
                move(if (r.linewise) VimText.lineStart(text, r.start.coerceAtMost(text.length)) else r.start)
            }
            'c' -> {
                // cc：只清空这几段的文字，段落分隔留着，光标停在原位输入
                val s = if (r.linewise) r.innerStart else r.start
                val e = if (r.linewise) r.innerEnd else r.end
                t.delete(s, e)
                move(s)
                setMode(Mode.INSERT)
            }
        }
    }

    /** p / P：整段时另起一段，否则插在光标后（P 是光标前）。 */
    private fun paste(after: Boolean, n: Int) {
        if (register.isEmpty()) return
        changed = true
        val t = text
        val c = cursor
        if (registerLines) {
            val content = List(n) { register }.joinToString("\n\n")
            val (at, insert, to) = if (after) VimText.pasteLinesAfter(t, c, content) else VimText.pasteLinesBefore(t, c, content)
            t.insert(at, insert)
            move(to)
        } else {
            val content = register.repeat(n)
            val at = if (after && c < t.length && t[c] != '\n') c + 1 else c
            t.insert(at, content)
            move(at + content.length - 1)
        }
    }

    private fun setMode(m: Mode) {
        val wasVisual = mode == Mode.VISUAL || mode == Mode.VISUAL_LINE
        mode = m
        reset()
        if (wasVisual && m != Mode.VISUAL && m != Mode.VISUAL_LINE) editor.setSelection(head.coerceIn(0, text.length))
        editor.blockCursor = m == Mode.NORMAL
        onModeChange(m)
    }

    private val text get() = editor.text
    private val cursor get() = if (mode == Mode.VISUAL || mode == Mode.VISUAL_LINE) head else editor.selectionStart.coerceAtLeast(0)
    private val layout get() = editor.layout

    private fun move(offset: Int) = editor.setSelection(offset.coerceIn(0, text.length))

    /** 新的空文档：直接进入插入模式，接着写标题（普通模式下光标会停在“# ”的空格上）。 */
    fun startInsert() {
        if (enabled && mode != Mode.INSERT) setMode(Mode.INSERT)
    }

    /**
     * 文字被 Vim 以外的地方换掉之后（换了文档、AI 替换、同步载入）：可视模式退回普通模式，
     * 选区的两端是旧文字里的位置，不能再用；普通模式下光标收到字上（存下来的位置可能是插入模式时的行尾）。
     */
    fun afterExternalChange() {
        if (!enabled) return
        if (mode == Mode.VISUAL || mode == Mode.VISUAL_LINE) {
            head = editor.selectionStart.coerceIn(0, text.length)
            anchor = head
            setMode(Mode.NORMAL)
        }
        if (mode == Mode.NORMAL) stayOnChar()
    }

    /** normal 模式下光标停在字上，不停在行尾之后（空行除外），和 vim 一样。 */
    private fun stayOnChar() {
        val c = cursor
        if (c > 0 && (c == text.length || text[c] == '\n') && text[c - 1] != '\n') move(c - 1)
    }

    /** 左右移一个字符；落到段距空行上就再移一个（那一行只有 1/4 行高，不是能写字的地方）。 */
    private fun moveChar(delta: Int) {
        move(cursor + delta)
        if (VimText.isGapLineStart(text, cursor)) move(cursor + delta)
    }

    private fun moveLine(delta: Int) {
        val l = layout ?: return
        val line = l.getLineForOffset(cursor)
        val target = (line + delta).coerceIn(0, l.lineCount - 1)
        if (target == line) return
        val x = l.getPrimaryHorizontal(cursor)
        move(l.getOffsetForHorizontal(target, x))
        editor.skipParagraphGap(delta, x)
    }

    private fun lineEndExclusiveNewline(line: Int): Int {
        val l = layout ?: return cursor
        val end = l.getLineEnd(line)
        return if (end > 0 && end <= text.length && text[end - 1] == '\n') end - 1 else end
    }






    private fun ctrl(src: KeyEvent, code: Int) =
        KeyEvent(src.downTime, src.eventTime, KeyEvent.ACTION_DOWN, code, 0, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)

    private fun ctrlShift(src: KeyEvent, code: Int) =
        KeyEvent(src.downTime, src.eventTime, KeyEvent.ACTION_DOWN, code, 0,
            KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON)
}
