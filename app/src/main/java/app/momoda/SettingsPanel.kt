package app.momoda

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.max

/**
 * 设置页（DESIGN §3.3）：只占屏幕右侧，左边露出正文，调整时立即预览。
 * - 键盘：↑↓ 选择，←→ 调整（开关项也可以按 Enter），Esc 关闭。
 * - 笔：点“−”“＋”调整，点开关项切换。
 * 和其他弹窗一样全部静态绘制：没有动画、没有压暗。
 */
class SettingsPanel(
    private val context: Context,
    private val typeface: Typeface?,
    private val rows: List<Row>,
    private val footnote: String,
    private val onDismiss: () -> Unit,
) {

    /**
     * 一行设置。[adjust] 收到 -1 或 +1；开关项两个方向都是切换。[value] 返回当前值的文字。[hint] 是名字下面的小字（例如快捷键）。
     * [isAction] 是“检查更新”这类动作：只有 Enter 和点击会执行，← → 不会误触。
     */
    class Row(val label: String, val value: () -> String, val adjust: ((Int) -> Unit)?, val isToggle: Boolean = false, val hint: String = "", val isAction: Boolean = false)

    private val dp = context.resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()
    private val frame = GradientDrawable().apply { setColor(Color.TRANSPARENT); setStroke(px(2), Color.BLACK) }
    private var current = 0
    private lateinit var scroll: ScrollView
    private val rowViews = mutableListOf<LinearLayout>()
    private val valueViews = mutableListOf<TextView>()

    fun show() {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val list = object : LinearLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> select(current - 1)
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_TAB -> select(current + if (event.isShiftPressed) -1 else 1)
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MINUS -> if (!rows[current].isAction) adjust(current, -1)
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_PLUS -> if (!rows[current].isAction) adjust(current, +1)
                    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE -> if (rows[current].isToggle || rows[current].isAction) adjust(current, +1)
                    KeyEvent.KEYCODE_ESCAPE -> dialog.dismiss()
                    else -> return super.dispatchKeyEvent(event)
                }
                return true
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            isFocusableInTouchMode = true
            // 上下留白：滚到头时第一行、最后一行不贴着屏幕边
            setPadding(px(12), px(4), px(12), px(20))
        }

        rows.forEachIndexed { i, row -> list.addView(rowView(i, row), LinearLayout.LayoutParams(-1, -2)) }

        list.addView(TextView(context).apply {
            text = footnote
            setTextColor(Color.rgb(0x77, 0x77, 0x77))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            this.typeface = typeface
            setPadding(px(8), px(16), px(8), 0)
            setLineSpacing(0f, 1.4f)
        })

        scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(list)
        }
        // 标题固定在上面，不跟着滚动；下面一条细线，滚动的内容从线下开始，不会被切在屏幕顶上
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = "设置"
                setTextColor(Color.BLACK)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                this.typeface = typeface
                setPadding(px(20), px(16), px(20), px(10))
            })
            addView(View(context).apply { setBackgroundColor(Color.rgb(0xDD, 0xDD, 0xDD)) },
                LinearLayout.LayoutParams(-1, 1).apply { setMargins(px(12), 0, px(12), px(4)) })
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            // 左侧一条黑线，和正文分开
            background = object : ColorDrawable(Color.WHITE) {
                override fun draw(canvas: android.graphics.Canvas) {
                    super.draw(canvas)
                    val p = android.graphics.Paint().apply { color = Color.BLACK }
                    canvas.drawRect(0f, 0f, 2 * dp, bounds.height().toFloat(), p)
                }
            }
        }
        dialog.setContentView(panel)
        dialog.setOnDismissListener { onDismiss() }
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setWindowAnimations(0)
            val w = context.resources.displayMetrics.widthPixels
            // 左边露出大半正文，调整时能看到效果；再窄的话，名字和说明就放不下
            setLayout(max(px(360), (w * 0.36f).toInt()).coerceAtMost(w), WindowManager.LayoutParams.MATCH_PARENT)
            setGravity(Gravity.END or Gravity.TOP)
        }
        dialog.show()
        list.requestFocus()
        select(0)
    }

    private fun rowView(i: Int, row: Row): LinearLayout {
        fun text(s: String, size: Float, color: Int) = TextView(context).apply {
            text = s
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            this.typeface = typeface
            gravity = Gravity.CENTER_VERTICAL
        }
        // 名字和说明各占一行，放不下就省略，不折行把这一行撑高
        fun oneLine(v: TextView) = v.apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        val label: View = if (row.hint.isEmpty()) oneLine(text(row.label, 15f, Color.BLACK)) else LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(oneLine(text(row.label, 15f, Color.BLACK)))
            addView(oneLine(text(row.hint, 11.5f, Color.rgb(0x77, 0x77, 0x77))).apply { setPadding(0, px(1), 0, 0) })
        }
        val value = text(row.value(), 14f, Color.BLACK).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        valueViews += value
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = px(48)
            setPadding(px(8), px(3), px(4), px(3))
            addView(label, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = px(8) })
            if (row.adjust != null && !row.isToggle && !row.isAction) {
                // 多个选项之间切换：左右箭头，和键盘的 ← → 一致
                addView(arrow("‹") { select(i); adjust(i, -1) })
                addView(value, LinearLayout.LayoutParams(px(84), -2))
                addView(arrow("›") { select(i); adjust(i, +1) })
            } else {
                addView(value, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = px(12) })
            }
            setOnClickListener { select(i); if (row.isToggle || row.isAction) adjust(i, +1) }
        }
        rowViews += line
        return line
    }

    /** 笔点的左右箭头：不画框，点的范围还是 36×44dp。 */
    private fun arrow(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        setTextColor(Color.BLACK)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        gravity = Gravity.CENTER
        includeFontPadding = false
        layoutParams = LinearLayout.LayoutParams(px(36), px(44))
        setOnClickListener { onClick() }
    }

    private fun select(i: Int) {
        current = i.coerceIn(0, rows.size - 1)
        rowViews.forEachIndexed { j, v -> v.background = if (j == current) frame else null }
        // 选中的行连同上下各一点空白都滚进来，不贴着边被切掉
        val row = rowViews[current]
        row.post {
            val margin = px(24)
            val top = row.top - margin
            val bottom = row.bottom + margin
            val y = scroll.scrollY
            // 第一行、最后一行：滚到头，标题下面和最后的说明文字都完整露出来
            if (current == 0) scroll.scrollTo(0, 0)
            // 不用 fullScroll：它会把焦点挪到最后一行上，多出一层系统的灰色焦点底
            else if (current == rows.size - 1) scroll.scrollTo(0, (scroll.getChildAt(0).height - scroll.height).coerceAtLeast(0))
            else if (top < y) scroll.scrollTo(0, top.coerceAtLeast(0))
            else if (bottom > y + scroll.height) scroll.scrollTo(0, bottom - scroll.height)
        }
    }

    private fun adjust(i: Int, delta: Int) {
        val row = rows[i]
        row.adjust?.invoke(delta) ?: return
        // 调整一项可能影响别的项（例如字体），全部刷新一下文字
        rows.forEachIndexed { j, r -> valueViews[j].text = r.value() }
    }
}
