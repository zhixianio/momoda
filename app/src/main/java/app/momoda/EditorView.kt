package app.momoda

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.text.LineBreakConfig
import android.os.Build
import android.os.SystemClock
import android.text.Editable
import android.text.SpannableStringBuilder
import android.view.inputmethod.BaseInputConnection
import android.text.InputType
import android.text.Layout
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.CharacterStyle
import android.text.style.LineHeightSpan
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * 正文编辑器（DESIGN §2）。
 * - 版心：按“每行约 [charsPerLine] 个汉字”自动算左右留白；横屏时不变宽。
 * - 分段：回车写入空行，空行只占 1/4 行高；列表和引用自动延续（[EditRules]）。
 * - 光标：自己画，不闪烁；vim normal 模式是方块。
 * - 翻页：写作时翻半页，PageUp/PageDown 整页，笔上下滑动翻半页；没有连续滚动。
 * - [preImeKeyHandler]：在按键到达输入法之前拦截，用于快捷键和 vim normal 模式。
 */
class EditorView(context: Context) : EditText(context) {

    var preImeKeyHandler: ((Int, KeyEvent) -> Boolean)? = null
    /** 输入法没有消费的按键（例如没在拼写时的 Esc）。 */
    var postImeKeyHandler: ((Int, KeyEvent) -> Boolean)? = null
    var onSelectionChange: ((Int, Int) -> Unit)? = null

    var charsPerLine = 30
        set(value) { field = value; updatePadding() }

    /** 载入文档时关掉分段规则，避免把文件里已有的换行再改一遍。 */
    var rulesEnabled = true

    var blockCursor = false
        set(value) { field = value; invalidate() }

    private var pendingKeyTime = 0L
    var lastLatencyMs = -1L
        private set
    var avgLatencyMs = -1.0
        private set
    var onLatency: ((Long) -> Unit)? = null

    private val cursorPaint = Paint().apply { color = Color.BLACK }
    private val invertedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xAA, 0xAA, 0xAA) }
    private val spacing = BlankLineSpacing()

    /** TextView 的构造函数会回调下面的重写方法，那时本类的字段还没初始化。 */
    private var ready = false

    init {
        setBackgroundColor(Color.WHITE)
        setTextColor(Color.BLACK)
        highlightColor = Color.rgb(0xCC, 0xCC, 0xCC)
        setLineSpacing(0f, 1.75f)
        gravity = Gravity.TOP or Gravity.START
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        // 简单断行：打字时不会为了“整段均衡”去改前面几行的断行位置（DESIGN §2.1）
        breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
        if (Build.VERSION.SDK_INT >= 33) {
            // 严格的 CJK 避头尾：句号、逗号、右括号不出现在行首
            lineBreakStyle = LineBreakConfig.LINE_BREAK_STYLE_STRICT
        }
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        // 让引号、破折号等“宽度有歧义”的标点按中文字形（全角）排
        textLocale = Locale.SIMPLIFIED_CHINESE
        // 系统光标会闪烁，每 0.5 秒改变一次画面；改为自己画一个常亮的光标
        isCursorVisible = false
    }

    fun setFont(typeface: Typeface) {
        this.typeface = typeface
        invertedPaint.typeface = typeface
        hintPaint.typeface = typeface
        updatePadding()
        restyleAll()
    }

    fun setTextSizeSp(sp: Float) {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        updatePadding()
        // 列表的悬挂缩进宽度按字号算，字号变了要重算
        restyleAll()
    }

    private val styler = MarkdownStyler(resources.displayMetrics.density) { paint.measureText(it.toString()) }

    /** 行距倍数。 */
    fun setLineSpacingMultiplier(mult: Float) = setLineSpacing(0f, mult)

    /** 段距：段落之间的空行占几分之一行高（0 表示空行不占高度）。 */
    var paragraphSpacing: Float
        get() = spacing.ratio
        set(v) { spacing.ratio = v; attachSpacing() }

    /** 段首缩进两字（只影响显示，不改文件）。 */
    var indentParagraphs: Boolean
        get() = styler.indent
        set(v) { styler.indent = v; restyleAll() }

    /**
     * 整篇重新渲染（字号、字体、缩进变化时）。不能直接在编辑器的文字上逐个改样式：
     * 每改一个 Span 都会触发一次重新排版，长文档会卡死主线程（4 万字实测卡了 25 秒）。
     * 所以先在一份脱离编辑器的副本上改好，再一次性放回去，并保持光标和翻页位置。
     */
    private fun restyleAll() {
        val t = text ?: return
        if (t.isEmpty()) return
        val selStart = selectionStart
        val selEnd = selectionEnd
        val y = scrollY
        val copy = SpannableStringBuilder(t)
        BaseInputConnection.removeComposingSpans(copy)
        styler.restyleAll(copy)
        setStyledText(copy)
        setSelection(selStart.coerceIn(0, copy.length), selEnd.coerceIn(0, copy.length))
        scrollTo(0, y)
    }

    /** 载入整篇文档：先在副本上渲染好 Markdown 和段距，再一次性交给编辑器（原因见 [restyleAll]）。 */
    fun load(content: String) {
        val styled = SpannableStringBuilder(content)
        styler.restyleAll(styled)
        setStyledText(styled)
    }

    private fun setStyledText(styled: SpannableStringBuilder) {
        styled.removeSpan(spacing)
        styled.removeSpan(focusSpan)
        styled.setSpan(spacing, 0, styled.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        rulesEnabled = false
        setText(styled)
        rulesEnabled = true
        focusShown = false
        updateFocus()
    }

    private fun attachSpacing() {
        val t = text ?: return
        t.removeSpan(spacing)
        t.setSpan(spacing, 0, t.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updatePadding()
        if (ready) post(pageCheck)
    }

    private fun updatePadding() {
        if (width == 0) return
        val column = paint.measureText("中") * charsPerLine
        val side = max(dp(40).toFloat(), (width - column) / 2f).toInt()
        // 底部留出约 1/3 屏的空白，最后一行也能被翻到屏幕中上部，不会被输入法的候选栏挡住
        setPadding(side, dp(40), side, max(dp(56), height / 3))
    }

    // ---------- 分段与列表规则 ----------

    private var softBreakPending = false

    private val rules = object : TextWatcher {
        private var deletedNewlineAt = -1
        private var insertedNewlineAt = -1
        private var applying = false
        // 本次编辑（含分段规则补充的编辑）影响到的范围，用来只重新渲染这几行
        private var dirtyFrom = Int.MAX_VALUE
        private var dirtyTo = -1

        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
            deletedNewlineAt = if (count == 1 && after == 0 && s[start] == '\n') start else -1
        }

        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
            insertedNewlineAt = if (before == 0 && count == 1 && s[start] == '\n') start else -1
            dirtyFrom = minOf(dirtyFrom, start)
            dirtyTo = maxOf(dirtyTo, start + count)
        }

        override fun afterTextChanged(s: Editable) {
            browsing = false
            if (dirtyTo >= 0) stripForeignStyles(s, dirtyFrom.coerceIn(0, s.length), dirtyTo.coerceIn(0, s.length))
            if (applying) return
            if (!rulesEnabled) { dirtyFrom = Int.MAX_VALUE; dirtyTo = -1; return }
            val soft = softBreakPending
            softBreakPending = false
            val edit = when {
                insertedNewlineAt >= 0 -> EditRules.afterNewline(s, insertedNewlineAt, soft)
                deletedNewlineAt >= 0 -> EditRules.afterNewlineDeleted(s, deletedNewlineAt)
                else -> null
            }
            if (edit != null) {
                applying = true
                s.replace(edit.start, edit.end, edit.text)
                setSelection(edit.cursor.coerceIn(0, s.length))
                applying = false
            }
            if (dirtyTo >= 0) {
                // 往前多带一个字符：删掉换行后，上一行和这一行合成了一行
                styler.restyle(s, (dirtyFrom - 1).coerceAtLeast(0), (dirtyTo + 1).coerceAtMost(s.length))
            }
            dirtyFrom = Int.MAX_VALUE
            dirtyTo = -1
        }
    }

    init {
        addTextChangedListener(rules)
    }

    /**
     * 纯文本编辑器：粘贴或输入法上屏的文字可能带着别处的样式，全部去掉；
     * 只保留输入法正在拼写的标记（SPAN_COMPOSING）和我们自己的 Markdown 样式（[MdSpan]）。
     */
    private fun stripForeignStyles(s: Editable, from: Int, to: Int) {
        // 只看刚变动的范围：整篇扫描在长文档里每个按键都要分配几千个对象
        for (span in s.getSpans(from, to, CharacterStyle::class.java)) {
            if (span is MdSpan || span is FocusSpan) continue
            if (s.getSpanFlags(span) and Spanned.SPAN_COMPOSING != 0) continue
            s.removeSpan(span)
        }
    }

    // ---------- 按键 ----------

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && !KeyEvent.isModifierKey(keyCode)) {
            pendingKeyTime = event.eventTime
            if (keyCode == KeyEvent.KEYCODE_ENTER) softBreakPending = event.isShiftPressed
        }
        return preImeKeyHandler?.invoke(keyCode, event) ?: false || super.onKeyPreIme(keyCode, event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_PAGE_DOWN -> { page(+1, 1f); return true }
            KeyEvent.KEYCODE_PAGE_UP -> { page(-1, 1f); return true }
        }
        return postImeKeyHandler?.invoke(keyCode, event) == true || super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_PAGE_DOWN || keyCode == KeyEvent.KEYCODE_PAGE_UP) return true
        return postImeKeyHandler?.invoke(keyCode, event) == true || super.onKeyUp(keyCode, event)
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        if (!ready) return
        onSelectionChange?.invoke(selStart, selEnd)
        browsing = false
        updateFocus()
        invalidate()
        removeCallbacks(pageCheck)
        post(pageCheck)
    }

    // ---------- 翻页 ----------

    /** 正在翻页回看：暂停“跟着光标翻页”和专注模式，直到光标移动或开始打字。 */
    var browsing = false
        private set(value) {
            if (field != value) {
                field = value
                onBrowsingChange?.invoke(value)
                updateFocus()
            }
        }

    // ---------- 专注模式（DESIGN §2.7） ----------

    /** 光标所在段落是黑色，其余段落浅灰；翻页回看时暂停。 */
    var focusMode = false
        set(value) { field = value; updateFocus() }

    private val focusSpan = FocusSpan()
    private var focusShown = false

    private fun updateFocus() {
        if (!ready) return
        val t = text ?: return
        val active = focusMode && !browsing
        if (active != focusShown) {
            focusShown = active
            setTextColor(if (active) DIM else Color.BLACK)
            if (!active) t.removeSpan(focusSpan)
        }
        if (!active) return
        val cur = selectionEnd.coerceIn(0, t.length)
        var start = cur
        while (start > 0 && t[start - 1] != '\n') start--
        var end = cur
        while (end < t.length && t[end] != '\n') end++
        if (t.getSpanStart(focusSpan) == start && t.getSpanEnd(focusSpan) == end) return
        // 优先级高于 Markdown 样式：先应用黑色，段内的标记符号再盖成灰色
        t.setSpan(focusSpan, start, end, Spanned.SPAN_INCLUSIVE_INCLUSIVE or (1 shl Spanned.SPAN_PRIORITY_SHIFT))
    }
    var onBrowsingChange: ((Boolean) -> Unit)? = null

    private val pageCheck = Runnable { keepCursorOnPage() }

    /**
     * 关掉 TextView 自带的“让光标可见”滚动，由 [keepCursorOnPage] 独自负责。
     * 两者并存时会拉锯：TextView 不允许滚过文末，每次上屏都把翻半页推上去的位置拉回来，
     * 表现为“上面的文字往上闪一下又回来”。
     */
    override fun bringPointIntoView(offset: Int): Boolean {
        if (ready) post(pageCheck)
        return false
    }

    override fun bringPointIntoView(offset: Int, requestRectWithoutFocus: Boolean): Boolean {
        if (ready) post(pageCheck)
        return false
    }

    /**
     * 翻半页：光标快到可写区域底部时，一次把它所在行推到屏幕上部 1/4 处。
     * 不做逐行滚动——墨水屏上每滚一行都是一次整屏变化。
     */
    private fun keepCursorOnPage() {
        if (browsing) return
        val l = layout ?: return
        val line = l.getLineForOffset(selectionEnd.coerceAtLeast(0))
        val top = l.getLineTop(line) + extendedPaddingTop
        val bottom = l.getLineBottom(line) + extendedPaddingTop
        val writableBottom = scrollY + height - extendedPaddingBottom
        val tooLow = bottom > writableBottom - lineHeight
        val tooHigh = top < scrollY + extendedPaddingTop
        if (tooLow || tooHigh) {
            // 专注模式下把当前行放到屏幕中部，视线停在中间；否则放到上部 1/4，一次能多写半屏
            val anchor = if (focusShown) (height * 0.4f).toInt() else height / 4
            scrollTo(0, max(0, top - anchor))
        }
    }

    /** 翻页回看：[fraction] = 1 整页（保留两行衔接），0.5 半页。不移动光标。 */
    fun page(direction: Int, fraction: Float) {
        val l = layout ?: return
        val visible = height - extendedPaddingTop - extendedPaddingBottom
        val step = ((visible - 2 * lineHeight) * fraction).toInt().coerceAtLeast(lineHeight)
        val maxScroll = max(0, l.height + extendedPaddingTop + extendedPaddingBottom - height)
        var target = (scrollY + direction * step).coerceIn(0, maxScroll)
        // 对齐到行首，避免顶端出现半行字（翻到文末时也要对齐，只会往回退，不会超出）
        if (target > 0) {
            target = l.getLineTop(l.getLineForVertical(target))
        }
        if (target == scrollY) return
        browsing = true
        scrollTo(0, target)
    }

    // ---------- 笔：上下滑动翻半页，不跟手滚动 ----------

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var swiping = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y; swiping = false }
            MotionEvent.ACTION_MOVE -> {
                // 门槛比系统的拖动滚动低：抢在 EditText 自己开始跟手滚动之前接管竖向拖动
                val dy = abs(event.y - downY)
                if (!swiping && dy > touchSlop / 2 && dy > abs(event.x - downX)) {
                    swiping = true
                    val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                    super.onTouchEvent(cancel)
                    cancel.recycle()
                }
                if (swiping) return true
            }
            MotionEvent.ACTION_UP -> if (swiping) {
                // 向上滑看后面，向下滑看前面；滑得太短视为手抖，不翻页
                if (abs(event.y - downY) > touchSlop * 3) page(if (event.y < downY) +1 else -1, 0.5f)
                swiping = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onScrollChanged(horiz: Int, vert: Int, oldHoriz: Int, oldVert: Int) {
        super.onScrollChanged(horiz, vert, oldHoriz, oldVert)
        if (BuildConfig.DEBUG && vert != oldVert) {
            // 诊断用（只在 debug 包里）：记录是谁触发了滚动
            val by = Throwable().stackTrace.drop(1)
                .firstOrNull { !it.className.startsWith("android.view.View") && it.methodName != "scrollTo" }
            Log.d("momoda", "scroll $oldVert → $vert by ${by?.className?.substringAfterLast('.')}.${by?.methodName}")
        }
    }

    // ---------- 绘制：常亮光标、标题占位 ----------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val l = layout
        if (l != null) {
            drawTitleHint(canvas, l)
            if (isFocused) drawCursor(canvas, l)
        }
        if (pendingKeyTime != 0L) {
            val ms = SystemClock.uptimeMillis() - pendingKeyTime
            pendingKeyTime = 0L
            lastLatencyMs = ms
            avgLatencyMs = if (avgLatencyMs < 0) ms.toDouble() else avgLatencyMs * 0.8 + ms * 0.2
            onLatency?.invoke(ms)
        }
    }

    private fun drawCursor(canvas: Canvas, l: Layout) {
        val start = selectionStart
        if (start < 0 || start != selectionEnd) return
        val line = l.getLineForOffset(start)
        val x = compoundPaddingLeft + l.getPrimaryHorizontal(start)
        val baseline = (extendedPaddingTop + l.getLineBaseline(line)).toFloat()
        val top = baseline + paint.ascent()
        val bottom = baseline + paint.descent()
        val t = text
        if (blockCursor) {
            val ch = if (start < t.length && t[start] != '\n') t[start].toString() else null
            val w = ch?.let { paint.measureText(it) } ?: (textSize * 0.6f)
            canvas.drawRect(x, top, x + w, bottom, cursorPaint)
            if (ch != null) {
                invertedPaint.textSize = textSize
                canvas.drawText(ch, x, baseline, invertedPaint)
            }
        } else {
            canvas.drawRect(x, top, x + dp(2), bottom, cursorPaint)
        }
    }

    /** 首行只有 `# ` 时，在后面画浅灰色的“标题”。 */
    private fun drawTitleHint(canvas: Canvas, l: Layout) {
        val t = text
        val firstLineEnd = t.indexOf('\n').let { if (it < 0) t.length else it }
        if (t.subSequence(0, firstLineEnd).toString() != "# ") return
        hintPaint.textSize = textSize
        val x = compoundPaddingLeft + l.getPrimaryHorizontal(2)
        val baseline = (extendedPaddingTop + l.getLineBaseline(0)).toFloat()
        canvas.drawText("标题", x, baseline, hintPaint)
    }

    fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** 专注模式下非当前段落的颜色：墨水屏上明显变浅，但仍然读得清。 */
        val DIM = Color.rgb(0xA0, 0xA0, 0xA0)
    }

    init {
        // 放在类的最后：此时所有字段都已初始化
        ready = true
    }

    /**
     * 空行（段落之间的分隔）只占 [ratio] 倍行高，替代整行空白（DESIGN §2.3）。
     * 真机反馈半行高仍然偏大，改为 1/4；M2 做成设置里可调的“段距”。
     */
    private class BlankLineSpacing(var ratio: Float = 0.25f) : LineHeightSpan {
        override fun chooseHeight(
            text: CharSequence, start: Int, end: Int, spanstartv: Int, lineHeight: Int, fm: Paint.FontMetricsInt,
        ) {
            if (end - start != 1 || text[start] != '\n') return
            if (start == 0 || text[start - 1] != '\n') return
            // 行距倍数是按字体高度乘出来的，所以直接缩字体高度，整行（含行距）就按同样比例缩小
            fm.descent = fm.ascent + ((fm.descent - fm.ascent) * ratio).toInt()
            fm.bottom = fm.descent
            fm.top = fm.ascent
        }
    }
}
