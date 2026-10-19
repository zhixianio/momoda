package app.momoda

import android.icu.text.BreakIterator
import android.view.KeyEvent
import java.util.Locale

/**
 * M0 的 vim 最小实现：验证 normal 模式能在输入法之前截获按键。
 * 支持 hjkl w b 0 $ gg G x dd u Ctrl+R i a A I o O，Esc 回到 normal。
 * j/k/0/$ 按屏幕上的视觉行移动（相当于 vim 的 gj/gk），更适合不分行的散文。
 */
class Vim(private val editor: EditorView, private val onModeChange: (Mode) -> Unit) {

    enum class Mode { INSERT, NORMAL }

    var enabled = false
        set(value) {
            field = value
            setMode(Mode.INSERT)
        }

    var mode = Mode.INSERT
        private set

    private var pending: Char? = null
    private val words = BreakIterator.getWordInstance(Locale.CHINA)

    /** 返回 true 表示按键已被消费，不再交给输入法和编辑器。 */
    fun handle(keyCode: Int, event: KeyEvent): Boolean {
        if (!enabled) return false
        // INSERT 模式的按键全部交给输入法；Esc 由 [escapeAfterIme] 在输入法之后处理
        if (mode == Mode.INSERT) return false
        // NORMAL：吞掉所有按键（Ctrl 组合键已经在上游处理过）
        if (event.action != KeyEvent.ACTION_DOWN) return true
        if (event.isCtrlPressed && keyCode == KeyEvent.KEYCODE_R) {
            editor.onKeyShortcut(KeyEvent.KEYCODE_Z, ctrlShift(event, KeyEvent.KEYCODE_Z))
            return true
        }
        val ch = event.getUnicodeChar(event.metaState).toChar()
        val p = pending
        pending = null
        when {
            p == 'g' && ch == 'g' -> move(0)
            p == 'd' && ch == 'd' -> deleteParagraph()
            ch == 'g' || ch == 'd' -> pending = ch
            ch == 'h' -> move(cursor - 1)
            ch == 'l' -> move(cursor + 1)
            ch == 'j' -> moveLine(+1)
            ch == 'k' -> moveLine(-1)
            ch == 'w' -> move(nextWord(cursor))
            ch == 'b' -> move(prevWord(cursor))
            ch == '0' -> layout?.let { move(it.getLineStart(it.getLineForOffset(cursor))) }
            ch == '$' -> layout?.let { move(lineEndExclusiveNewline(it.getLineForOffset(cursor))) }
            ch == 'G' -> move(text.length)
            ch == 'x' -> if (cursor < text.length) text.delete(cursor, cursor + 1)
            ch == 'u' -> editor.onKeyShortcut(KeyEvent.KEYCODE_Z, ctrl(event, KeyEvent.KEYCODE_Z))
            ch == 'i' -> setMode(Mode.INSERT)
            ch == 'a' -> { move(cursor + 1); setMode(Mode.INSERT) }
            ch == 'A' -> { move(paragraphEnd(cursor)); setMode(Mode.INSERT) }
            ch == 'I' -> { move(paragraphStart(cursor)); setMode(Mode.INSERT) }
            ch == 'o' -> { val e = paragraphEnd(cursor); text.insert(e, "\n"); move(e + 1); setMode(Mode.INSERT) }
            ch == 'O' -> { val s = paragraphStart(cursor); text.insert(s, "\n"); move(s); setMode(Mode.INSERT) }
        }
        return true
    }

    /**
     * 输入法没有消费的 Esc 才会走到这里。很多输入法（如 fcitx5）的拼音只显示在候选框里，
     * App 无从判断“是否在拼写”，所以先让输入法处理 Esc（取消拼写），它不要才切到 normal。
     */
    fun escapeAfterIme(): Boolean {
        if (!enabled || mode != Mode.INSERT) return false
        setMode(Mode.NORMAL)
        return true
    }

    private fun setMode(m: Mode) {
        mode = m
        pending = null
        editor.setBlockCursor(m == Mode.NORMAL)
        onModeChange(m)
    }

    private val text get() = editor.text
    private val cursor get() = editor.selectionStart.coerceAtLeast(0)
    private val layout get() = editor.layout

    private fun move(offset: Int) = editor.setSelection(offset.coerceIn(0, text.length))

    private fun moveLine(delta: Int) {
        val l = layout ?: return
        val line = l.getLineForOffset(cursor)
        val target = (line + delta).coerceIn(0, l.lineCount - 1)
        if (target == line) return
        move(l.getOffsetForHorizontal(target, l.getPrimaryHorizontal(cursor)))
    }

    private fun lineEndExclusiveNewline(line: Int): Int {
        val l = layout ?: return cursor
        val end = l.getLineEnd(line)
        return if (end > 0 && end <= text.length && text[end - 1] == '\n') end - 1 else end
    }

    private fun nextWord(from: Int): Int {
        words.setText(text.toString())
        var pos = words.following(from.coerceAtMost(text.length))
        while (pos != BreakIterator.DONE && pos < text.length && !isWordChar(text[pos])) {
            pos = words.next()
        }
        return if (pos == BreakIterator.DONE) text.length else pos
    }

    private fun prevWord(from: Int): Int {
        if (from <= 0) return 0
        words.setText(text.toString())
        var pos = words.preceding(from)
        while (pos != BreakIterator.DONE && pos > 0 && !isWordChar(text[pos])) {
            pos = words.previous()
        }
        return if (pos == BreakIterator.DONE) 0 else pos
    }

    private fun isWordChar(c: Char) = c.isLetterOrDigit()

    private fun paragraphStart(at: Int): Int = text.lastIndexOf('\n', (at - 1).coerceAtLeast(-1)).let {
        if (at == 0) 0 else it + 1
    }

    private fun paragraphEnd(at: Int): Int = text.indexOf('\n', at).let { if (it < 0) text.length else it }

    private fun deleteParagraph() {
        val s = paragraphStart(cursor)
        val e = paragraphEnd(cursor)
        val end = if (e < text.length) e + 1 else e
        val start = if (end == e && s > 0) s - 1 else s
        text.delete(start, end)
        move(start)
    }

    private fun ctrl(src: KeyEvent, code: Int) =
        KeyEvent(src.downTime, src.eventTime, KeyEvent.ACTION_DOWN, code, 0, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)

    private fun ctrlShift(src: KeyEvent, code: Int) =
        KeyEvent(src.downTime, src.eventTime, KeyEvent.ACTION_DOWN, code, 0,
            KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON)
}
