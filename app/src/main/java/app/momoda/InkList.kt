package app.momoda

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import kotlin.math.min

/**
 * 列表里的一项：[label] 是主文字，[detail] 是右侧浅灰的补充信息，[search] 用于搜索匹配。
 * [header] 为 true 的是分组标题：不能选中，搜索时隐藏。[searchable] 为 false 的项只在没有搜索词时出现。
 */
data class InkItem(
    val label: String,
    val detail: String = "",
    val search: String = label,
    val header: Boolean = false,
    val searchable: Boolean = true,
)

/**
 * 墨水屏友好的列表弹窗，用于命令面板和文档切换。
 * 不用 AlertDialog：它的选中态有水波纹和渐变动画，背景还会压暗，墨水屏上会拖影、闪烁。
 * 这里全部是静态绘制：白底、黑边框，当前项是一个黑色方框，没有任何动画。
 * - 键盘：直接打字筛选（支持拼音首字母），方向键或 Tab 移动，Enter 选择，Esc 关闭。
 * - 笔：点选；点搜索框可以用软键盘输入。
 * 弹窗固定在屏幕上部，筛选时只有列表的下沿变化，标题和搜索框不跳动。
 */
object InkList {

    fun show(
        context: Context,
        title: String,
        items: List<InkItem>,
        typeface: Typeface?,
        searchHint: String? = "输入筛选，支持拼音首字母",
        onDismiss: () -> Unit = {},
        /** 叠在另一个面板上：比面板窄一些，见 [panelWidth]。 */
        nested: Boolean = false,
        onPick: (Int) -> Unit,
    ) {
        val dp = context.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val rowHeight = px(52)
        val headerHeight = px(34)
        val maxHeight = (context.resources.displayMetrics.heightPixels * 0.55f).toInt().coerceAtLeast(rowHeight * 4)

        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        var shown: List<Int> = items.indices.toList()
        fun selectable(position: Int) = shown.getOrNull(position)?.let { !items[it].header } ?: false
        var current = shown.indices.firstOrNull(::selectable) ?: 0
        val frame = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(px(2), Color.BLACK)
        }

        fun pick(): Boolean {
            val i = shown.getOrNull(current) ?: return true
            dialog.dismiss()
            onPick(i)
            return true
        }

        val adapter = object : BaseAdapter() {
            override fun getCount() = if (shown.isEmpty()) 1 else shown.size
            override fun getItem(position: Int) = shown.getOrNull(position)
            override fun getItemId(position: Int) = position.toLong()
            override fun isEnabled(position: Int) = selectable(position)
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val tv = (convertView as? TextView) ?: TextView(context).apply {
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                    this.typeface = typeface
                    setPadding(px(20), 0, px(20), 0)
                    minHeight = rowHeight
                    gravity = Gravity.CENTER_VERTICAL
                    maxLines = 1
                }
                if (shown.isEmpty()) {
                    tv.text = "没有匹配"
                    tv.setTextColor(Color.GRAY)
                    tv.background = null
                    return tv
                }
                val item = items[shown[position]]
                if (item.header) {
                    tv.text = SpannableStringBuilder(item.label).apply {
                        setSpan(RelativeSizeSpan(0.8f), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    tv.setTextColor(Color.rgb(0x77, 0x77, 0x77))
                    tv.minHeight = headerHeight
                    tv.gravity = Gravity.BOTTOM
                    tv.setPadding(px(20), 0, px(20), px(4))
                    tv.background = null
                    return tv
                }
                tv.minHeight = rowHeight
                tv.gravity = Gravity.CENTER_VERTICAL
                tv.setPadding(px(20), 0, px(20), 0)
                tv.setTextColor(Color.BLACK)
                tv.text = SpannableStringBuilder(item.label).apply {
                    if (item.detail.isNotEmpty()) {
                        val s = length
                        append("　　").append(item.detail)
                        setSpan(ForegroundColorSpan(Color.rgb(0x77, 0x77, 0x77)), s, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        setSpan(RelativeSizeSpan(0.8f), s, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                tv.background = if (position == current) frame else null
                return tv
            }
        }

        val list = ListView(context).apply {
            this.adapter = adapter
            selector = ColorDrawable(Color.TRANSPARENT)
            divider = ColorDrawable(Color.rgb(0xDD, 0xDD, 0xDD))
            dividerHeight = 1
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            setOnItemClickListener { _, _, position, _ ->
                current = position
                pick()
            }
        }

        fun heightOf(position: Int) = (if (shown.isNotEmpty() && items[shown[position]].header) headerHeight else rowHeight) + 1

        fun refresh() {
            adapter.notifyDataSetChanged()
            var total = 0
            for (p in 0 until adapter.count) {
                val h = heightOf(p)
                if (total + h > maxHeight) break
                total += h
            }
            list.layoutParams = list.layoutParams.apply { height = total }
            // 当前项上面紧挨着分组标题时，连标题一起露出来
            list.setSelection(if (current > 0 && !selectable(current - 1) && shown.isNotEmpty()) current - 1 else current)
        }

        fun move(delta: Int) {
            if (shown.isEmpty()) return
            var next = current + delta
            while (next in shown.indices && !selectable(next)) next += delta
            if (next in shown.indices) current = next
            refresh()
        }

        /** 方向键、Tab、Enter、Esc 的统一处理；返回 true 表示已消费。 */
        fun navigate(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> { move(+1); true }
            KeyEvent.KEYCODE_DPAD_UP -> { move(-1); true }
            KeyEvent.KEYCODE_TAB -> { move(if (event.isShiftPressed) -1 else +1); true }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> pick()
            KeyEvent.KEYCODE_ESCAPE -> { dialog.dismiss(); true }
            else -> false
        }

        val titleView = TextView(context).apply {
            text = title
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            this.typeface = typeface
            setPadding(px(20), px(16), px(20), px(8))
        }

        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(px(2), Color.BLACK)
            }
            setPadding(px(2), px(2), px(2), px(8))
            addView(titleView)
        }

        var search: SearchField? = null
        if (searchHint != null) {
            search = SearchField(context, ::navigate).apply {
                hint = searchHint
                this.typeface = typeface
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setTextColor(Color.BLACK)
                setHintTextColor(Color.rgb(0xAA, 0xAA, 0xAA))
                background = GradientDrawable().apply { setColor(Color.WHITE); setStroke(1, Color.rgb(0x99, 0x99, 0x99)) }
                setPadding(px(14), px(10), px(14), px(10))
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun afterTextChanged(s: Editable) {
                        shown = if (s.isBlank()) items.indices.toList()
                        else Pinyin.matcher.filter(s.toString(), items.indices.filter { !items[it].header && items[it].searchable }) { items[it].search }
                        current = shown.indices.firstOrNull(::selectable) ?: 0
                        refresh()
                    }
                })
            }
            box.addView(search, LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(px(18), px(4), px(18), px(10))
            })
        } else {
            list.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN) navigate(keyCode, event) else keyCode != KeyEvent.KEYCODE_BACK
            }
        }
        box.addView(list, LinearLayout.LayoutParams(-1, rowHeight))

        dialog.setContentView(box)
        dialog.setOnDismissListener { onDismiss() }
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setWindowAnimations(0)
            val metrics = context.resources.displayMetrics
            val width = if (nested) context.panelWidth(nested = true) else min(px(600), (metrics.widthPixels * 0.86f).toInt())
            setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            attributes = attributes.apply { y = (metrics.heightPixels * 0.12f).toInt() }
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED)
        }
        dialog.show()
        refresh()
        if (search != null) search.requestFocus() else {
            list.isFocusableInTouchMode = true
            list.requestFocus()
        }
    }

    /** 单行输入弹窗（修改标题等）。Enter 确定，Esc 取消；文字照常经过输入法，可以输入中文。 */
    fun prompt(
        context: Context,
        title: String,
        initial: String,
        typeface: Typeface?,
        onDismiss: () -> Unit = {},
        onDone: (String) -> Unit,
    ) {
        val dp = context.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        lateinit var field: EditText
        fun done() {
            val v = field.text.toString()
            dialog.dismiss()
            if (v.isNotBlank()) onDone(v)
        }
        field = object : EditText(context) {
            // Enter 走完输入法之后才到这里：拼写中的 Enter 仍由输入法处理
            override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
                if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) { done(); return true }
                return super.onKeyDown(keyCode, event)
            }
        }.apply {
            setText(initial)
            setSelection(initial.length)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            setOnEditorActionListener { _, _, _ -> done(); true }
            this.typeface = typeface
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(Color.BLACK)
            isCursorVisible = true
            background = GradientDrawable().apply { setColor(Color.WHITE); setStroke(1, Color.rgb(0x99, 0x99, 0x99)) }
            setPadding(px(14), px(10), px(14), px(10))
        }
        fun button(label: String, onClick: () -> Unit) = TextView(context).apply {
            text = label
            this.typeface = typeface
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(Color.BLACK)
            setPadding(px(20), px(12), px(20), px(12))
            setOnClickListener { onClick() }
        }
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(button("取消") { dialog.dismiss() })
            addView(button("确定（Enter）") { done() })
        }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Color.WHITE); setStroke(px(2), Color.BLACK) }
            setPadding(px(2), px(2), px(2), px(4))
            addView(TextView(context).apply {
                text = title
                setTextColor(Color.BLACK)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                this.typeface = typeface
                setPadding(px(20), px(16), px(20), px(8))
            })
            addView(field, LinearLayout.LayoutParams(-1, -2).apply { setMargins(px(18), px(4), px(18), px(4)) })
            addView(buttons, LinearLayout.LayoutParams(-1, -2))
        }
        dialog.setContentView(box)
        dialog.setOnDismissListener { onDismiss() }
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setWindowAnimations(0)
            val metrics = context.resources.displayMetrics
            setLayout(min(px(600), (metrics.widthPixels * 0.86f).toInt()), WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            attributes = attributes.apply { y = (metrics.heightPixels * 0.12f).toInt() }
        }
        dialog.show()
        field.requestFocus()
    }

    /**
     * 搜索框：实体键盘打的字母直接进框，不经过输入法——这样即使输入法处在中文状态，
     * 输入 `xj` 也能立即筛出“新建文档”。软键盘提交的文字照常走输入法。
     */
    private class SearchField(
        context: Context,
        private val navigate: (Int, KeyEvent) -> Boolean,
    ) : EditText(context) {

        init {
            isSingleLine = true
            // 系统光标会闪烁；搜索框里的字总是从左往右追加，不需要光标
            isCursorVisible = false
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        }

        private val consumed = mutableSetOf<Int>()
        private val pressed = KeyPairs()

        override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_UP) {
                val stray = !pressed.up(keyCode)
                return consumed.remove(keyCode) || stray
            }
            if (event.action != KeyEvent.ACTION_DOWN) return false
            pressed.down(keyCode)
            val handled = navigate(keyCode, event) || when {
                keyCode == KeyEvent.KEYCODE_DEL -> {
                    val t = text
                    if (t.isNotEmpty()) t.delete(t.length - 1, t.length)
                    true
                }
                event.isCtrlPressed || event.isAltPressed || event.isMetaPressed -> false
                event.unicodeChar != 0 && keyCode != KeyEvent.KEYCODE_SPACE -> {
                    append(event.unicodeChar.toChar().toString())
                    true
                }
                else -> false
            }
            if (handled) consumed += keyCode
            return handled
        }
    }
}

/**
 * 记录一个输入框里按下过、还没抬起的键。弹窗常常是在别处按下某个键时打开的（命令面板里按 Enter 选“打开文档”），
 * 在 Android 10 及更早的系统上，这个键的抬起会送到新弹窗：单行输入框收到 Enter 抬起会把焦点移到下面的列表，
 * 之后打的字、方向键和 Enter 都没有反应。没在这里按下过的键，抬起应当吞掉。
 */
class KeyPairs {
    private val down = mutableSetOf<Int>()

    fun down(keyCode: Int) { down += keyCode }

    /** 返回这次抬起是否和本输入框里的一次按下配对；false 表示是别处按下的键，应当吞掉。 */
    fun up(keyCode: Int): Boolean = down.remove(keyCode)
}
