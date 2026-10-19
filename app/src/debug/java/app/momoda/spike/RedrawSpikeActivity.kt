package app.momoda.spike

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * M1 技术验证：比较两种结构下，输入一个字时 HWUI 上报的重绘区域。
 *
 *   adb shell setprop debug.hwui.show_dirty_regions true
 *   adb shell am start -n app.momoda/.spike.RedrawSpikeActivity --es mode paragraph
 *   adb shell input text abc        # 然后截图
 *
 * mode=paragraph：每段一个 TextView（各自独立的 RenderNode），光标是一个只移动位置的小 View。
 * mode=nocursor ：同 paragraph，但不移动光标，只看段落本身的重绘区域。
 * mode=edittext ：全屏单个 EditText（M0 的结构），作为对照组。
 */
class RedrawSpikeActivity : Activity() {

    private val paragraphs = mutableListOf<TextView>()
    private lateinit var cursor: View
    private val active = 3

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val font = Typeface.createFromAsset(assets, "fonts/LXGWWenKaiScreen.ttf")
        val sample = "写作这件事，说到底就是和自己待在一起。没有通知，没有红点，没有“再看一眼”的诱惑。光标在那里，字一个一个落下来。"
        val dp = resources.displayMetrics.density
        val side = (120 * dp).toInt()

        if (intent.getStringExtra("mode") == "edittext") {
            setContentView(EditText(this).apply {
                typeface = font
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setLineSpacing(0f, 1.75f)
                setBackgroundColor(Color.WHITE)
                setPadding(side, (56 * dp).toInt(), side, 0)
                setText((1..12).joinToString("\n\n") { "第${it}段。$sample" })
            })
            return
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(side, (56 * dp).toInt(), side, 0)
        }
        for (i in 1..12) {
            val tv = TextView(this).apply {
                typeface = font
                setTextColor(Color.BLACK)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setLineSpacing(0f, 1.75f)
                text = "第${i}段。$sample"
                setPadding(0, 0, 0, (14 * dp).toInt())
            }
            paragraphs += tv
            column.addView(tv, LinearLayout.LayoutParams(-1, -2))
        }
        cursor = View(this).apply { setBackgroundColor(Color.BLACK) }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            isFocusable = true
            isFocusableInTouchMode = true
            addView(column, FrameLayout.LayoutParams(-1, -1))
            addView(cursor, FrameLayout.LayoutParams((2 * dp).toInt(), (26 * dp).toInt()))
        }
        setContentView(root)
        root.requestFocus()
        paragraphs[active].addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeCursor() }
    }

    /** 光标只改 translationX/Y：HWUI 只把新旧两个小矩形算作重绘区域。 */
    private fun placeCursor() {
        val tv = paragraphs[active]
        val layout = tv.layout ?: return
        val off = tv.text.length
        val line = layout.getLineForOffset(off)
        val parent = tv.parent as View
        cursor.translationX = parent.left + tv.left + layout.getPrimaryHorizontal(off)
        cursor.translationY = parent.top + tv.top + tv.paddingTop + layout.getLineTop(line).toFloat()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (paragraphs.isEmpty()) return super.onKeyDown(keyCode, event)
        val ch = event.unicodeChar
        if (ch == 0) return super.onKeyDown(keyCode, event)
        val tv = paragraphs[active]
        tv.text = tv.text.toString() + "墨"
        if (intent.getStringExtra("mode") != "nocursor") tv.post { placeCursor() }
        return true
    }
}
