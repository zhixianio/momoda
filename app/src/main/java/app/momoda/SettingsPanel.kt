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

    /** 一行设置。[adjust] 收到 -1 或 +1；开关项两个方向都是切换。[value] 返回当前值的文字。[hint] 是名字下面的小字（例如快捷键）。 */
    class Row(val label: String, val value: () -> String, val adjust: ((Int) -> Unit)?, val isToggle: Boolean = false, val hint: String = "")

    private val dp = context.resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()
    private val frame = GradientDrawable().apply { setColor(Color.TRANSPARENT); setStroke(px(2), Color.BLACK) }
    private var current = 0
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
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MINUS -> adjust(current, -1)
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_PLUS -> adjust(current, +1)
                    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE -> if (rows[current].isToggle) adjust(current, +1)
                    KeyEvent.KEYCODE_ESCAPE -> dialog.dismiss()
                    else -> return super.dispatchKeyEvent(event)
                }
                return true
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            isFocusableInTouchMode = true
            setPadding(px(16), px(20), px(16), px(16))
        }

        list.addView(TextView(context).apply {
            text = "设置"
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            this.typeface = typeface
            setPadding(px(8), 0, px(8), px(12))
        })

        rows.forEachIndexed { i, row -> list.addView(rowView(i, row), LinearLayout.LayoutParams(-1, -2)) }

        list.addView(TextView(context).apply {
            text = footnote
            setTextColor(Color.rgb(0x77, 0x77, 0x77))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            this.typeface = typeface
            setPadding(px(8), px(16), px(8), 0)
            setLineSpacing(0f, 1.4f)
        })

        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(list)
            // 左侧一条黑线，和正文分开
            background = object : ColorDrawable(Color.WHITE) {
                override fun draw(canvas: android.graphics.Canvas) {
                    super.draw(canvas)
                    val p = android.graphics.Paint().apply { color = Color.BLACK }
                    canvas.drawRect(0f, 0f, 2 * dp, bounds.height().toFloat(), p)
                }
            }
        }
        dialog.setContentView(scroll)
        dialog.setOnDismissListener { onDismiss() }
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setWindowAnimations(0)
            val w = context.resources.displayMetrics.widthPixels
            setLayout(max(px(380), (w * 0.42f).toInt()).coerceAtMost(w), WindowManager.LayoutParams.MATCH_PARENT)
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
        val label: View = if (row.hint.isEmpty()) text(row.label, 17f, Color.BLACK) else LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(row.label, 17f, Color.BLACK))
            addView(text(row.hint, 12f, Color.rgb(0x77, 0x77, 0x77)))
        }
        val value = text(row.value(), 16f, Color.BLACK).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        valueViews += value
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = px(56)
            setPadding(px(8), 0, px(4), 0)
            addView(label, LinearLayout.LayoutParams(0, -2, 1f))
            if (row.adjust != null && !row.isToggle) {
                addView(button("−") { select(i); adjust(i, -1) })
                addView(value, LinearLayout.LayoutParams(px(132), -2))
                addView(button("＋") { select(i); adjust(i, +1) })
            } else {
                addView(value, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = px(12) })
            }
            setOnClickListener { select(i); if (row.isToggle) adjust(i, +1) }
        }
        rowViews += line
        return line
    }

    private fun button(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        setTextColor(Color.BLACK)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        typeface = this@SettingsPanel.typeface
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(px(48), px(48))
        background = GradientDrawable().apply { setColor(Color.WHITE); setStroke(1, Color.rgb(0x99, 0x99, 0x99)) }
        setOnClickListener { onClick() }
    }

    private fun select(i: Int) {
        current = i.coerceIn(0, rows.size - 1)
        rowViews.forEachIndexed { j, v -> v.background = if (j == current) frame else null }
        rowViews[current].requestRectangleOnScreen(android.graphics.Rect(0, 0, rowViews[current].width, rowViews[current].height), true)
    }

    private fun adjust(i: Int, delta: Int) {
        val row = rows[i]
        row.adjust?.invoke(delta) ?: return
        // 调整一项可能影响别的项（例如字体），全部刷新一下文字
        rows.forEachIndexed { j, r -> valueViews[j].text = r.value() }
    }
}
