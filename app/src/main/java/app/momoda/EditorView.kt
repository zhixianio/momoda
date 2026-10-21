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
import android.text.Spannable
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
        set(value) { if (field != value) { field = value; updatePadding() } }

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

    // ---------- 延迟测量（Ctrl+Shift+L 打开，结果写进日志 momoda-latency） ----------

    /** 一个按键各阶段的时刻（SystemClock.uptimeMillis 的时间轴）。 */
    class KeyTiming(val key: String, val event: Long, val arrive: Long, val change: Long, val draw: Long)

    var measuring = false
    private var mKey = ""
    private var mEvent = 0L
    private var mArrive = 0L
    private var mChange = 0L
    private var mDraw = 0L

    /** 这个按键画完了就交出去（等这一帧交给屏幕后由 MainActivity 记录），否则返回 null。 */
    fun takeTiming(): KeyTiming? {
        if (mArrive == 0L || mDraw == 0L) return null
        val t = KeyTiming(mKey, mEvent, mArrive, mChange, mDraw)
        mArrive = 0L
        return t
    }

    private fun markChange() {
        if (measuring && mArrive != 0L && mChange == 0L) mChange = SystemClock.uptimeMillis()
    }

    private val cursorPaint = Paint().apply { color = Color.BLACK }
    private val invertPaint = Paint().apply {
        color = Color.WHITE
        if (Build.VERSION.SDK_INT >= 29) blendMode = android.graphics.BlendMode.DIFFERENCE
    }
    private val styledPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG)

    /** [i] 处那个字实际用的画笔：正文画笔加上这里的字号、字体样式（标题、粗体等）。 */
    private fun styledPaintAt(t: CharSequence, i: Int): android.text.TextPaint {
        styledPaint.set(paint)
        if (t is Spanned && i < t.length) {
            for (span in t.getSpans(i, i + 1, android.text.style.MetricAffectingSpan::class.java)) span.updateMeasureState(styledPaint)
        }
        return styledPaint
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xAA, 0xAA, 0xAA) }
    private val spacing = BlankLineSpacing()

    /** TextView 的构造函数会回调下面的重写方法，那时本类的字段还没初始化。 */
    private var ready = false

    init {
        // 长文档前部打字时不把后面全文重排一遍（见 QuietEditable）
        setEditableFactory(QuietEditable.Factory)
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

    // 下面这些设置没变时什么都不做：切换 Zen、专注、Vim 时会把全部设置重新应用一遍，
    // 而整篇重新渲染会替换编辑器里的文字，打断正在拼写的输入法（候选窗消失），长文档还要卡一下
    fun setFont(typeface: Typeface) {
        if (typeface === this.typeface) return
        this.typeface = typeface
        hintPaint.typeface = typeface
        updatePadding()
        restyleAll()
    }

    fun setTextSizeSp(sp: Float) {
        val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
        if (px == textSize) return
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
        set(v) { if (spacing.ratio != v) { spacing.ratio = v; attachSpacing() } }

    /** 段首缩进两字（只影响显示，不改文件）。 */
    var indentParagraphs: Boolean
        get() = styler.indent
        set(v) { if (styler.indent != v) { styler.indent = v; restyleAll() } }

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
        pendingFrom = -1
        text?.let { it.setSpan(composingWatcher, 0, it.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE) }
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
        // 底部内边距不能大：TextView 不在内边距里画字，大了下面那块就永远是空白，像文档已经到头。
        // “光标别写到最底下”和“文末能翻到屏幕上部”分别由 [writingMargin] 和 [overscroll] 负责。
        setPadding(side, dp(40), side, dp(12))
    }

    /** 光标进入屏幕底部这一段就翻页：留给输入法候选栏，也让视线停在中上部。 */
    private val writingMargin get() = max(dp(56), height / 4)

    /** 文末之后还能再往下翻这么多，最后几行可以停在屏幕中上部。 */
    private val overscroll get() = height / 3

    // ---------- 分段与列表规则 ----------

    private var softBreakPending = false

    // 等预编辑结束再整理的那一段（见 rules 里的说明）
    private var pendingFrom = -1
    private var pendingTo = -1
    private var pendingText = ""
    /** 正在由我们自己改文字（整理引号、空格），这时的改动不再触发整理。 */
    private var typingApplying = false

    /** 对 [from, to) 套用 [TypingRules]：方括号键输入「」、中英文之间加空格。 */
    private fun applyTyping(s: Editable, from: Int, to: Int) {
        val typed = TypingRules.afterInsert(s, from, to.coerceAtMost(s.length), cornerQuotes, autoSpace) ?: return
        val cursorAtEnd = selectionStart == to
        typingApplying = true
        s.replace(typed.start, typed.end, typed.text)
        if (cursorAtEnd) setSelection(typed.cursor.coerceIn(0, s.length))
        typingApplying = false
    }

    /** 输入法去掉预编辑标记时（文字不变，只是结束拼写），整理刚才那一段。 */
    private val composingWatcher = object : android.text.SpanWatcher {
        override fun onSpanAdded(text: Spannable, what: Any, start: Int, end: Int) = Unit
        override fun onSpanChanged(text: Spannable, what: Any, ostart: Int, oend: Int, nstart: Int, nend: Int) = Unit
        override fun onSpanRemoved(text: Spannable, what: Any, start: Int, end: Int) {
            if (pendingFrom < 0) return
            post {
                val t = this@EditorView.text ?: return@post
                val from = pendingFrom
                val to = pendingTo
                if (from < 0 || BaseInputConnection.getComposingSpanStart(t) >= 0) return@post
                pendingFrom = -1
                if (to <= t.length && t.subSequence(from, to).toString() == pendingText) applyTyping(t, from, to)
            }
        }
    }

    private val rules = object : TextWatcher {
        private var deletedNewlineAt = -1
        private var insertedNewlineAt = -1
        // 这次插入的文字（输入法上屏、粘贴等），用来套 [TypingRules]
        private var insertedFrom = -1
        private var insertedTo = -1
        private var applying = false
        // 本次编辑（含分段规则补充的编辑）影响到的范围，用来只重新渲染这几行
        private var dirtyFrom = Int.MAX_VALUE
        private var dirtyTo = -1

        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
            deletedNewlineAt = if (count == 1 && after == 0 && s[start] == '\n') start else -1
        }

        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
            markChange()
            insertedNewlineAt = if (before == 0 && count == 1 && s[start] == '\n') start else -1
            if (!applying && !typingApplying && count > 0) { insertedFrom = start; insertedTo = start + count }
            else if (!applying && !typingApplying) insertedFrom = -1
            // 还在等预编辑结束的那段：改动和它重叠就作废，改动在它前面就跟着平移
            if (pendingFrom >= 0) {
                if (start < pendingTo && start + before > pendingFrom) pendingFrom = -1
                else if (start <= pendingFrom) { pendingFrom += count - before; pendingTo += count - before }
            }
            dirtyFrom = minOf(dirtyFrom, start)
            dirtyTo = maxOf(dirtyTo, start + count)
        }

        override fun afterTextChanged(s: Editable) {
            browsing = false
            if (dirtyTo >= 0) stripForeignStyles(s, dirtyFrom.coerceIn(0, s.length), dirtyTo.coerceIn(0, s.length))
            if (applying) return
            if (typingApplying) {
                // 我们自己整理引号、空格时的改动：只重新渲染，不再套规则
                if (dirtyTo >= 0) styler.restyle(s, (dirtyFrom - 1).coerceAtLeast(0), (dirtyTo + 1).coerceAtMost(s.length))
                dirtyFrom = Int.MAX_VALUE
                dirtyTo = -1
                return
            }
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
            } else if (insertedFrom >= 0 && (cornerQuotes || autoSpace)) {
                if (BaseInputConnection.getComposingSpanStart(s) >= 0) {
                    // 拼写中（预编辑）先不动，记下来：输入法结束预编辑时（见 [composingWatcher]）再整理。
                    // fcitx 的中文标点（例如 [ 打出的【）就是先作为预编辑放进来，之后只去掉预编辑标记、文字不再变
                    pendingFrom = insertedFrom
                    pendingTo = insertedTo
                    pendingText = s.subSequence(insertedFrom, insertedTo.coerceAtMost(s.length)).toString()
                } else {
                    applyTyping(s, insertedFrom, insertedTo)
                }
            }
            insertedFrom = -1
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
            if (measuring) {
                mKey = KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
                mEvent = event.eventTime
                mArrive = SystemClock.uptimeMillis()
                mChange = 0L
                mDraw = 0L
            }
            if (keyCode == KeyEvent.KEYCODE_ENTER) softBreakPending = event.isShiftPressed
        }
        return preImeKeyHandler?.invoke(keyCode, event) ?: false || super.onKeyPreIme(keyCode, event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_PAGE_DOWN -> { page(+1, 1f); return true }
            KeyEvent.KEYCODE_PAGE_UP -> { page(-1, 1f); return true }
        }
        val vertical = (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) &&
            !event.isShiftPressed && !event.isCtrlPressed && !event.isAltPressed
        val x = if (vertical) layout?.let { it.getPrimaryHorizontal(selectionStart.coerceAtLeast(0)) } ?: 0f else 0f
        val handled = postImeKeyHandler?.invoke(keyCode, event) == true || super.onKeyDown(keyCode, event)
        if (handled && vertical) skipParagraphGap(if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) +1 else -1, x)
        return handled
    }

    /**
     * 上下移动后光标落在段距空行上（只有 1/4 行高，光标画在那里看起来飘在两段之间）：再往同一方向移一行。
     * [x] 是移动前光标的横坐标，尽量保持在同一列。
     */
    private var lastCursor = 0

    /**
     * 光标不停在段距空行上：那一行只有 1/4 行高，是“回车空一行”在屏幕上的样子，不是能写字的地方。
     * 不管是方向键、vim、笔点还是删除让光标落到这里，都按移动方向挪到相邻的文字行：
     * 往前（往上、往左）挪到上一段末尾，往后挪到下一段开头。
     */
    private val gapGuard = Runnable {
        val s = selectionStart
        val t = text
        if (s < 0 || s != selectionEnd || t == null) return@Runnable
        if (BaseInputConnection.getComposingSpanStart(t) >= 0) { lastCursor = s; return@Runnable }
        val l = layout ?: return@Runnable
        if (onParagraphGap()) {
            val start = l.getLineStart(l.getLineForOffset(s))
            val target = if (s < lastCursor) start - 1 else start + 1
            lastCursor = target.coerceIn(0, t.length)
            setSelection(lastCursor)
        } else {
            lastCursor = s
        }
    }

    /** 光标是不是停在段距空行上。 */
    fun onParagraphGap(): Boolean {
        val l = layout ?: return false
        val t = text ?: return false
        val s = selectionStart
        if (s < 0) return false
        val line = l.getLineForOffset(s)
        return BlankLineSpacing.isParagraphGap(t, l.getLineStart(line), l.getLineEnd(line))
    }

    fun skipParagraphGap(direction: Int, x: Float) {
        val l = layout ?: return
        val t = text ?: return
        val s = selectionStart
        if (s < 0 || s != selectionEnd) return
        val line = l.getLineForOffset(s)
        if (!BlankLineSpacing.isParagraphGap(t, l.getLineStart(line), l.getLineEnd(line))) return
        val target = line + direction
        if (target !in 0 until l.lineCount) return
        setSelection(l.getOffsetForHorizontal(target, x))
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_PAGE_DOWN || keyCode == KeyEvent.KEYCODE_PAGE_UP) return true
        return postImeKeyHandler?.invoke(keyCode, event) == true || super.onKeyUp(keyCode, event)
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        if (!ready) return
        markChange()
        // 等这一轮编辑（含分段规则补的那个换行）做完再检查，不在中途挪光标
        removeCallbacks(gapGuard)
        post(gapGuard)
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

    /** 光标走到屏幕边缘时怎么翻。 */
    enum class ScrollMode(val label: String) {
        /** 一行一行跟着光标走，笔拖动时跟手滚动。 */
        CONTINUOUS("连续"),
        /** 光标到底部 1/4 时，把当前行推到屏幕上部 1/4 处；笔滑动翻半页。 */
        HALF("半页"),
        /** 光标到底部时，把当前行推到屏幕顶端；笔滑动翻整页。 */
        FULL("整页"),
    }

    var scrollMode = ScrollMode.CONTINUOUS

    /** 方括号键输入直角引号、中英文之间自动加空格（见 [TypingRules]）。 */
    var cornerQuotes = false
    var autoSpace = false

    /**
     * 让光标留在屏幕上。连续模式下只滚到刚好露出光标所在行和下一行（往上时是上一行），一次一行；
     * 半页、整页模式下一次跳一大段，墨水屏整屏刷新的次数少。滚动位置都对齐到行首，屏幕顶端不出现半行字。
     */
    private fun keepCursorOnPage() {
        if (browsing) return
        val l = layout ?: return
        val line = l.getLineForOffset(selectionEnd.coerceAtLeast(0))
        val top = l.getLineTop(line) + extendedPaddingTop
        val bottom = l.getLineBottom(line) + extendedPaddingTop
        if (scrollMode == ScrollMode.CONTINUOUS) {
            // 多露出一行：往下写时看得到下一行的位置，往上移时看得到上一行
            // 相邻的是段距空行时再多算一行，露出的是真正的文字行
            val t = text
            fun gap(n: Int) = n in 0 until l.lineCount && BlankLineSpacing.isParagraphGap(t, l.getLineStart(n), l.getLineEnd(n))
            val next = (if (gap(line + 1)) line + 2 else line + 1).coerceAtMost(l.lineCount - 1)
            val prev = (if (gap(line - 1)) line - 2 else line - 1).coerceAtLeast(0)
            val below = l.getLineBottom(next) - l.getLineBottom(line)
            val above = l.getLineTop(line) - l.getLineTop(prev)
            val visibleBottom = scrollY + height - extendedPaddingBottom
            val target = when {
                bottom + below > visibleBottom -> {
                    val min = bottom + below - (height - extendedPaddingBottom)
                    var ln = l.getLineForVertical(min)
                    if (l.getLineTop(ln) < min && ln + 1 < l.lineCount) ln++
                    // 屏幕顶端不停在段距空行上：否则下一次只滚这 1/4 行
                    if (gap(ln) && ln + 1 < l.lineCount) ln++
                    l.getLineTop(ln)
                }
                top - above < scrollY + extendedPaddingTop -> l.getLineTop(l.getLineForVertical(max(0, top - above - extendedPaddingTop)))
                else -> return
            }
            if (target != scrollY) scrollTo(0, max(0, target))
            return
        }
        val writableBottom = scrollY + height - (if (scrollMode == ScrollMode.FULL) extendedPaddingBottom else writingMargin)
        val tooLow = bottom > writableBottom - lineHeight
        val tooHigh = top < scrollY + extendedPaddingTop
        if (tooLow || tooHigh) {
            // 整页：放到顶端（留一行衔接）。半页：专注模式下放到屏幕中部，视线停在中间；否则放到上部 1/4
            val anchor = when {
                scrollMode == ScrollMode.FULL -> extendedPaddingTop + lineHeight
                focusShown -> (height * 0.4f).toInt()
                else -> height / 4
            }
            // 对齐到行首：屏幕顶端不出现半行字（和 [page] 一样）
            val y = max(0, top - anchor)
            scrollTo(0, if (y > 0) l.getLineTop(l.getLineForVertical(y)) else 0)
        }
    }

    /** 翻页回看：[fraction] = 1 整页（保留两行衔接），0.5 半页。不移动光标。 */
    fun page(direction: Int, fraction: Float) {
        val l = layout ?: return
        val visible = height - extendedPaddingTop - extendedPaddingBottom
        val step = ((visible - 2 * lineHeight) * fraction).toInt().coerceAtLeast(lineHeight)
        val maxScroll = max(0, l.height + extendedPaddingTop + extendedPaddingBottom + overscroll - height)
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
                // 连续模式：交给 EditText 跟手滚动；拖走之后不把视图拉回光标处，直到再打字或移动光标
                if (scrollMode == ScrollMode.CONTINUOUS) {
                    if (abs(event.y - downY) > touchSlop) browsing = true
                    return super.onTouchEvent(event)
                }
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
                if (abs(event.y - downY) > touchSlop * 3) page(if (event.y < downY) +1 else -1, if (scrollMode == ScrollMode.FULL) 1f else 0.5f)
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
        // 底部只画完整的行：被切掉一半的行在墨水屏上很难看，留白到下一次翻页
        val saved = canvas.save()
        layout?.let { l ->
            val limit = scrollY + height - extendedPaddingBottom
            val line = l.getLineForVertical(limit - extendedPaddingTop)
            if (l.getLineBottom(line) + extendedPaddingTop > limit) {
                canvas.clipRect(0, scrollY, width, l.getLineTop(line) + extendedPaddingTop)
            }
        }
        super.onDraw(canvas)
        canvas.restoreToCount(saved)
        val l = layout
        if (l != null) {
            drawTitleHint(canvas, l)
            if (isFocused) drawCursor(canvas, l)
        }
        if (measuring && mArrive != 0L && mChange != 0L && mDraw == 0L) mDraw = SystemClock.uptimeMillis()
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
        var top = baseline + paint.ascent()
        var bottom = baseline + paint.descent()
        val t0 = text
        if (t0 != null && BlankLineSpacing.isParagraphGap(t0, l.getLineStart(line), l.getLineEnd(line))) {
            // 段距空行只有 1/4 行高：光标按这一行的实际高度画，不伸进上下两段的文字里
            top = (extendedPaddingTop + l.getLineTop(line)).toFloat()
            bottom = (extendedPaddingTop + l.getLineBottom(line)).toFloat()
        }
        val t = text
        if (blockCursor) {
            val ch = if (start < t.length && t[start] != '\n') t[start].toString() else null
            // 按这个字实际的样式量宽高：标题是大号粗体，不能按正文的字号画
            val sp = styledPaintAt(t, start)
            val w = ch?.let { sp.measureText(it) } ?: (textSize * 0.6f)
            if (ch != null) { top = baseline + sp.ascent(); bottom = baseline + sp.descent() }
            if (Build.VERSION.SDK_INT >= 29) {
                // 把已经画好的字反色：样式（标题、粗体、淡显的标记）原样保留，只是黑白对调
                canvas.drawRect(x, top, x + w, bottom, invertPaint)
            } else {
                canvas.drawRect(x, top, x + w, bottom, cursorPaint)
                if (ch != null) {
                    sp.color = Color.WHITE
                    canvas.drawText(ch, x, baseline, sp)
                }
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
    internal class BlankLineSpacing(var ratio: Float = 0.25f) : LineHeightSpan {
        companion object {
            /**
             * 这一行是不是“段距”：紧跟在文字行后面的第一个空行。之后连续的空行是用户有意留的空白，按正常行高显示，
             * 否则光标在几个空行里上下移动、删除时每次只挪四分之一行，看起来像行距突然变得很小。
             */
            fun isParagraphGap(text: CharSequence, start: Int, end: Int): Boolean =
                end - start == 1 && text[start] == '\n' &&
                    start >= 2 && text[start - 1] == '\n' && text[start - 2] != '\n'
        }

        override fun chooseHeight(
            text: CharSequence, start: Int, end: Int, spanstartv: Int, lineHeight: Int, fm: Paint.FontMetricsInt,
        ) {
            if (!isParagraphGap(text, start, end)) return
            // 行距倍数是按字体高度乘出来的，所以直接缩字体高度，整行（含行距）就按同样比例缩小
            fm.descent = fm.ascent + ((fm.descent - fm.ascent) * ratio).toInt()
            fm.bottom = fm.descent
            fm.top = fm.ascent
        }
    }
}
