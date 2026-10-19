package app.momoda

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.text.LineBreakConfig
import android.os.Build
import android.os.SystemClock
import android.text.InputType
import android.text.Layout
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import java.util.Locale
import kotlin.math.max

/**
 * 正文编辑器。
 * - 版心：按“每行约 [charsPerLine] 个汉字”自动算左右留白。
 * - [preImeKeyHandler]：在按键到达输入法之前拦截，用于快捷键和 vim normal 模式。
 * - 记录“按键 → 重绘”的延迟，M0 用来评估墨水屏上的打字手感。
 */
class EditorView(context: Context) : EditText(context) {

    var preImeKeyHandler: ((Int, KeyEvent) -> Boolean)? = null
    /** 输入法没有消费的按键（例如没在拼写时的 Esc）。 */
    var postImeKeyHandler: ((Int, KeyEvent) -> Boolean)? = null
    var onSelectionChange: ((Int, Int) -> Unit)? = null

    var charsPerLine = 34
        set(value) { field = value; updatePadding() }

    private var pendingKeyTime = 0L
    var lastLatencyMs = -1L
        private set
    var avgLatencyMs = -1.0
        private set
    var onLatency: ((Long) -> Unit)? = null

    init {
        setBackgroundColor(Color.WHITE)
        setTextColor(Color.BLACK)
        setLineSpacing(0f, 1.75f)
        gravity = Gravity.TOP or Gravity.START
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        breakStrategy = Layout.BREAK_STRATEGY_HIGH_QUALITY
        if (Build.VERSION.SDK_INT >= 33) {
            // 严格的 CJK 避头尾：句号、逗号、右括号不出现在行首
            lineBreakStyle = LineBreakConfig.LINE_BREAK_STYLE_STRICT
        }
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        // 让引号、破折号等“宽度有歧义”的标点按中文字形（全角）排
        textLocale = Locale.SIMPLIFIED_CHINESE
        setBlockCursor(false)
    }

    fun setFont(typeface: Typeface) {
        this.typeface = typeface
        updatePadding()
    }

    fun setTextSizeSp(sp: Float) {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        updatePadding()
    }

    /** normal 模式用方块光标，insert 模式用细线光标。 */
    fun setBlockCursor(block: Boolean) {
        if (Build.VERSION.SDK_INT < 29) return
        val w = if (block) (textSize * 0.6f).toInt() else dp(2)
        textCursorDrawable = GradientDrawable().apply {
            setColor(Color.BLACK)
            setSize(w, textSize.toInt())
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updatePadding()
    }

    private fun updatePadding() {
        if (width == 0) return
        val column = paint.measureText("中") * charsPerLine
        val side = max(dp(24).toFloat(), (width - column) / 2f).toInt()
        setPadding(side, dp(56), side, dp(56))
    }

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && !KeyEvent.isModifierKey(keyCode)) {
            pendingKeyTime = event.eventTime
        }
        return preImeKeyHandler?.invoke(keyCode, event) ?: false || super.onKeyPreIme(keyCode, event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        postImeKeyHandler?.invoke(keyCode, event) == true || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        postImeKeyHandler?.invoke(keyCode, event) == true || super.onKeyUp(keyCode, event)

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        onSelectionChange?.invoke(selStart, selEnd)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (pendingKeyTime != 0L) {
            val ms = SystemClock.uptimeMillis() - pendingKeyTime
            pendingKeyTime = 0L
            lastLatencyMs = ms
            avgLatencyMs = if (avgLatencyMs < 0) ms.toDouble() else avgLatencyMs * 0.8 + ms * 0.2
            onLatency?.invoke(ms)
        }
    }

    fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
