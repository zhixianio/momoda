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

    var charsPerLine = 30
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
        post(pageCheck)
    }

    private fun updatePadding() {
        if (width == 0) return
        val column = paint.measureText("中") * charsPerLine
        val side = max(dp(40).toFloat(), (width - column) / 2f).toInt()
        // 底部留出约 1/3 屏的空白，最后一行也能被翻到屏幕中上部，不会被输入法的候选栏挡住
        setPadding(side, dp(56), side, max(dp(56), height / 3))
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
        removeCallbacks(pageCheck)
        post(pageCheck)
    }

    private val pageCheck = Runnable { keepCursorOnPage() }

    /**
     * 关掉 TextView 自带的“让光标可见”滚动，由 [keepCursorOnPage] 独自负责。
     * 两者并存时会拉锯：TextView 不允许滚过文末，每次上屏都把翻半页推上去的位置拉回来，
     * 表现为“上面的文字往上闪一下又回来”。
     */
    override fun bringPointIntoView(offset: Int): Boolean {
        post(pageCheck)
        return false
    }

    override fun bringPointIntoView(offset: Int, requestRectWithoutFocus: Boolean): Boolean {
        post(pageCheck)
        return false
    }

    /**
     * 翻半页：光标快到可写区域底部时，一次把它所在行推到屏幕上部 1/4 处。
     * 不做逐行滚动——墨水屏上每滚一行都是一次整屏刷新。
     */
    private fun keepCursorOnPage() {
        val l = layout ?: return
        val line = l.getLineForOffset(selectionEnd.coerceAtLeast(0))
        val top = l.getLineTop(line) + extendedPaddingTop
        val bottom = l.getLineBottom(line) + extendedPaddingTop
        val writableBottom = scrollY + height - extendedPaddingBottom
        val tooLow = bottom > writableBottom - lineHeight
        val tooHigh = top < scrollY + extendedPaddingTop
        if (tooLow || tooHigh) {
            scrollTo(0, max(0, top - height / 4))
        }
    }

    override fun onScrollChanged(horiz: Int, vert: Int, oldHoriz: Int, oldVert: Int) {
        super.onScrollChanged(horiz, vert, oldHoriz, oldVert)
        if (vert != oldVert) {
            // 诊断“往上滚一下又回来”：记录是谁触发了滚动
            val by = Throwable().stackTrace.drop(1)
                .firstOrNull { !it.className.startsWith("android.view.View") && it.methodName != "scrollTo" }
            android.util.Log.d("momoda", "scroll $oldVert → $vert by ${by?.className?.substringAfterLast('.')}.${by?.methodName}")
        }
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
