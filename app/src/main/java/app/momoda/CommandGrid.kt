package app.momoda

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.min

/**
 * 命令面板：按分组排成多列，一屏放下全部命令，不用滚动（墨水屏上滚动又慢又闪）。
 * 每格写命令名，下面小字是快捷键（没有快捷键时是补充说明）。搜索框右边是“常用”的几个按钮。
 * - 键盘：方向键在格子之间移动，Tab 依次往后，Enter 执行，Esc 关闭；直接打字筛选（支持拼音首字母），
 *   有筛选词时改成一列结果。
 * - 笔：点格子。
 * 和其他弹窗一样全部静态绘制：白底黑框，当前格是一个黑色方框，没有动画。
 */
object CommandGrid {

    class Item(val id: String, val label: String, val hint: String)

    fun show(
        context: Context,
        columns: List<Pair<String, List<Item>>>,
        /** 用得最多的几条，排在搜索框右边；打开面板时默认选中第一个。 */
        frequent: List<Item>,
        typeface: Typeface?,
        onDismiss: () -> Unit,
        onPick: (Item) -> Unit,
    ) {
        val dp = context.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val frame = GradientDrawable().apply { setColor(Color.TRANSPARENT); setStroke(px(2), Color.BLACK) }

        val all = columns.flatMap { it.second }
        var shown: List<List<Item>> = columns.map { it.second }
        var titles: List<String> = columns.map { it.first }
        var searching = false
        // row == -1 是搜索框右边的“常用”按钮，col 是第几个
        var col = 0
        var row = if (frequent.isNotEmpty()) -1 else 0
        val cells = mutableListOf<MutableList<View>>()
        val frequentViews = mutableListOf<View>()

        fun pick(): Boolean {
            val item = (if (row == -1) frequent.getOrNull(col) else shown.getOrNull(col)?.getOrNull(row)) ?: return true
            dialog.dismiss()
            onPick(item)
            return true
        }

        val body = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

        val frequentBox = GradientDrawable().apply { setColor(Color.WHITE); setStroke(1, Color.rgb(0x99, 0x99, 0x99)) }

        fun highlight() {
            cells.forEachIndexed { c, list -> list.forEachIndexed { r, v -> v.background = if (c == col && r == row) frame else null } }
            frequentViews.forEachIndexed { i, v -> v.background = if (row == -1 && i == col) frame else frequentBox }
        }

        fun cell(item: Item, c: Int, r: Int, wide: Boolean) = LinearLayout(context).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = if (wide) Gravity.CENTER_VERTICAL else Gravity.START
            setPadding(px(12), px(8), px(12), px(8))
            minimumHeight = px(if (wide) 52 else 60)
            addView(TextView(context).apply {
                text = item.label
                setTextColor(Color.BLACK)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                this.typeface = typeface
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            if (item.hint.isNotEmpty()) addView(TextView(context).apply {
                text = item.hint
                setTextColor(Color.rgb(0x77, 0x77, 0x77))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                this.typeface = typeface
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                if (wide) setPadding(px(16), 0, 0, 0) else setPadding(0, px(2), 0, 0)
            })
            setOnClickListener { col = c; row = r; pick() }
        }

        fun render() {
            body.removeAllViews()
            cells.clear()
            val wide = shown.size == 1 && titles.firstOrNull().isNullOrEmpty()
            shown.forEachIndexed { c, items ->
                val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                val title = titles.getOrNull(c).orEmpty()
                if (title.isNotEmpty()) column.addView(TextView(context).apply {
                    text = title
                    setTextColor(Color.rgb(0x77, 0x77, 0x77))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    this.typeface = typeface
                    setPadding(px(12), px(4), px(12), px(4))
                })
                val list = mutableListOf<View>()
                items.forEachIndexed { r, item -> cell(item, c, r, wide).also { list += it; column.addView(it, LinearLayout.LayoutParams(-1, -2)) } }
                if (items.isEmpty() && wide) column.addView(TextView(context).apply {
                    text = "没有匹配"
                    setTextColor(Color.GRAY)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    this.typeface = typeface
                    setPadding(px(12), px(12), px(12), px(12))
                })
                cells += list
                body.addView(column, LinearLayout.LayoutParams(0, -2, 1f))
                // 列之间一条细线
                if (c < shown.size - 1) body.addView(View(context).apply { setBackgroundColor(Color.rgb(0xDD, 0xDD, 0xDD)) },
                    LinearLayout.LayoutParams(1, -1))
            }
            highlight()
        }

        fun moveTo(dCol: Int, dRow: Int) {
            val hasFrequent = frequent.isNotEmpty() && !searching
            when {
                row == -1 && dRow > 0 -> { row = 0; col = col.coerceAtMost(shown.size - 1) }
                row == -1 -> col = (col + dCol).coerceIn(0, frequent.size - 1)
                row == 0 && dRow < 0 && hasFrequent -> { row = -1; col = col.coerceAtMost(frequent.size - 1) }
                else -> { val (c, r) = PaletteLayout.move(shown.map { it.size }, col, row, dCol, dRow); col = c; row = r }
            }
            highlight()
        }

        fun next(delta: Int) {
            // Tab：先“常用”，再按列依次往后（到列尾进下一列）
            val head = if (searching) emptyList() else frequent.indices.map { it to -1 }
            val flat = head + shown.flatMapIndexed { c, items -> items.indices.map { c to it } }
            if (flat.isEmpty()) return
            val i = flat.indexOf(col to row).coerceAtLeast(0)
            val (c, r) = flat[(i + delta).mod(flat.size)]
            col = c; row = r
            highlight()
        }

        fun navigate(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> { moveTo(0, +1); true }
            KeyEvent.KEYCODE_DPAD_UP -> { moveTo(0, -1); true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { moveTo(+1, 0); true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { moveTo(-1, 0); true }
            KeyEvent.KEYCODE_TAB -> { next(if (event.isShiftPressed) -1 else +1); true }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> pick()
            KeyEvent.KEYCODE_ESCAPE -> { dialog.dismiss(); true }
            else -> false
        }

        val search = InkList.SearchField(context, ::navigate).apply {
            hint = "输入筛选，支持拼音首字母"
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
                    searching = s.isNotBlank()
                    if (!searching) {
                        shown = columns.map { it.second }
                        titles = columns.map { it.first }
                    } else {
                        shown = listOf(Pinyin.matcher.filter(s.toString(), all) { "${it.label} ${it.hint}" })
                        titles = listOf("")
                    }
                    col = 0
                    row = if (!searching && frequent.isNotEmpty()) -1 else 0
                    render()
                }
            })
        }

        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Color.WHITE); setStroke(px(2), Color.BLACK) }
            setPadding(px(2), px(2), px(2), px(10))
            addView(TextView(context).apply {
                text = "命令"
                setTextColor(Color.BLACK)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                this.typeface = typeface
                setPadding(px(20), px(16), px(20), px(8))
            })
            // 搜索框和“常用”按钮在同一行
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(search, LinearLayout.LayoutParams(0, -2, 1f))
                // 标明这几个是“最近常用”：会随使用变化，和下面固定的分组不是一回事
                if (frequent.isNotEmpty()) addView(TextView(context).apply {
                    text = "常用"
                    setTextColor(Color.rgb(0x77, 0x77, 0x77))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    this.typeface = typeface
                    setPadding(px(16), 0, px(2), 0)
                }, LinearLayout.LayoutParams(-2, -2))
                frequent.forEachIndexed { i, item ->
                    val v = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        setPadding(px(14), 0, px(14), 0)
                        addView(TextView(context).apply {
                            text = item.label
                            setTextColor(Color.BLACK)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                            this.typeface = typeface
                            maxLines = 1
                        })
                        // 和搜索框一样高，只放得下命令名；快捷键在下面的分组里写着
                        setOnClickListener { row = -1; col = i; pick() }
                    }
                    frequentViews += v
                    addView(v, LinearLayout.LayoutParams(-2, -1).apply { marginStart = px(8) })
                }
            }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(px(18), px(4), px(18), px(10)) })
            addView(body, LinearLayout.LayoutParams(-1, -2).apply { setMargins(px(6), 0, px(6), 0) })
        }

        render()
        dialog.setContentView(box)
        dialog.setOnDismissListener { onDismiss() }
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setWindowAnimations(0)
            val metrics = context.resources.displayMetrics
            setLayout(min(px(960), (metrics.widthPixels * 0.94f).toInt()), WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            attributes = attributes.apply { y = (metrics.heightPixels * 0.08f).toInt() }
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED)
        }
        dialog.show()
        search.requestFocus()
    }
}
