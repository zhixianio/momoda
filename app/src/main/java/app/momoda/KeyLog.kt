package app.momoda

import android.util.Log
import android.view.KeyEvent

/** M0 调试用：记录最近的按键及其去向，同时写到 logcat（tag: momoda）。 */
object KeyLog {
    private const val MAX = 10
    val lines = ArrayDeque<String>()

    fun add(event: KeyEvent, outcome: String) {
        if (event.action != KeyEvent.ACTION_DOWN || KeyEvent.isModifierKey(event.keyCode)) return
        val line = "${describe(event)} → $outcome"
        Log.d("momoda", "key $line")
        lines.addFirst(line)
        while (lines.size > MAX) lines.removeLast()
    }

    fun describe(e: KeyEvent): String = buildString {
        if (e.isCtrlPressed) append("Ctrl+")
        if (e.isMetaPressed) append("Meta+")
        if (e.isAltPressed) append("Alt+")
        if (e.isShiftPressed) append("Shift+")
        append(KeyEvent.keyCodeToString(e.keyCode).removePrefix("KEYCODE_"))
    }
}
