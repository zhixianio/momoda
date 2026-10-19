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
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import kotlin.math.min

/**
 * 墨水屏友好的列表弹窗，用于命令面板和文档列表。
 * 不用 AlertDialog：它的选中态有水波纹和渐变动画，背景还会压暗，墨水屏上会拖影、闪烁。
 * 这里全部是静态绘制：白底、黑边框，当前项是一个黑色方框，没有任何动画。
 * 键盘（方向键 + Enter，Esc 关闭）和笔点击都能操作。
 */
object InkList {

    fun show(
        context: Context,
        title: String,
        items: List<String>,
        typeface: Typeface?,
        onDismiss: () -> Unit,
        onPick: (Int) -> Unit,
    ) {
        val dp = context.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val titleView = TextView(context).apply {
            text = title
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            this.typeface = typeface
            setPadding(px(20), px(16), px(20), px(10))
        }

        // 自己维护“当前项”并画框：ListView 自带的选中框在触控模式（笔点过之后）下不显示
        var current = 0
        val frame = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(px(2), Color.BLACK)
        }
        lateinit var adapter: ArrayAdapter<String>
        val list = ListView(context).apply {
            adapter = object : ArrayAdapter<String>(context, 0, items) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val tv = (convertView as? TextView) ?: TextView(context).apply {
                        setTextColor(Color.BLACK)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                        this.typeface = typeface
                        setPadding(px(20), px(12), px(20), px(12))
                        minHeight = px(48)
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    tv.text = getItem(position)
                    tv.background = if (position == current) frame else null
                    return tv
                }
            }
            this.adapter = adapter
            selector = ColorDrawable(Color.TRANSPARENT)
            divider = ColorDrawable(Color.rgb(0xDD, 0xDD, 0xDD))
            dividerHeight = 1
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            itemsCanFocus = false
            setOnItemClickListener { _, _, position, _ ->
                dialog.dismiss()
                onPick(position)
            }
            setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_DPAD_UP -> {
                        val up = keyCode == KeyEvent.KEYCODE_DPAD_UP || event.isShiftPressed
                        current = (current + if (up) -1 else 1).coerceIn(0, items.size - 1)
                        adapter.notifyDataSetChanged()
                        setSelection(current)
                        true
                    }
                    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                        dialog.dismiss()
                        onPick(current)
                        true
                    }
                    else -> false
                }
            }
        }

        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(px(2), Color.BLACK)
            }
            setPadding(px(2), px(2), px(2), px(8))
            addView(titleView)
            addView(list, LinearLayout.LayoutParams(-1, -2))
        }
        dialog.setContentView(box)
        dialog.setOnDismissListener { onDismiss() }

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setWindowAnimations(0)
            val w = context.resources.displayMetrics.widthPixels
            setLayout(min(px(560), (w * 0.86f).toInt()), WindowManager.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
        // 笔点开的也要显示选中框，方便接着用键盘
        list.isFocusableInTouchMode = true
        list.requestFocus()
    }
}
