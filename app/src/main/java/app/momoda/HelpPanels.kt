package app.momoda

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/** 弹窗里能用键盘操作的按钮：Tab / 方向键移过来时加粗边框，Enter 按下。 */
private fun Context.focusButton(label: String, typeface: Typeface?, onClick: () -> Unit): TextView =
    inkButton(label, typeface, onClick).apply {
        isFocusable = true
        // 系统默认的焦点高亮是一层灰底，墨水屏上和黑框重复，只留黑框
        defaultFocusHighlightEnabled = false
        val normal = background
        val focused = GradientDrawable().apply { setColor(Color.WHITE); setStroke(px(3), Color.BLACK) }
        setOnFocusChangeListener { _, has -> background = if (has) focused else normal }
    }

private fun Context.panelText(s: String, size: Float, color: Int, typeface: Typeface?) = TextView(this).apply {
    text = s
    setTextColor(color)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
    this.typeface = typeface
    setLineSpacing(0f, 1.35f)
}

private fun Context.showPanel(box: LinearLayout, onDismiss: () -> Unit): Dialog {
    val dialog = inkDialog(box, Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0.86f, WindowManager.LayoutParams.WRAP_CONTENT)
    dialog.window?.apply {
        setLayout(panelWidth(), WindowManager.LayoutParams.WRAP_CONTENT)
        attributes = attributes.apply { y = (resources.displayMetrics.heightPixels * 0.08f).toInt() }
    }
    dialog.setOnDismissListener { onDismiss() }
    return dialog
}

/**
 * 帮助：快捷键一览，以及偶尔才用到的工具（导出诊断信息、输入法设置）。
 * Esc 关闭；Tab 在按钮之间移动，Enter 按下。
 */
class HelpPanel(
    private val context: Context,
    private val typeface: Typeface?,
    private val version: String,
    private val onExportDiagnostics: () -> Unit,
    private val onImeSettings: () -> Unit,
    private val onDismiss: () -> Unit,
) {
    private val shortcuts = listOf(
        "Ctrl+K" to "命令面板", "Ctrl+/" to "帮助",
        "Ctrl+N" to "新建文档", "Ctrl+O" to "打开文档",
        "Ctrl+Tab" to "切回上一篇", "Ctrl+," to "设置",
        "Ctrl+." to "Zen 模式", "Ctrl+Shift+F" to "专注模式",
        "Ctrl+Shift+V" to "Vim 模式", "Ctrl+J" to "AI 助手",
        "Ctrl+Shift+S" to "立即同步", "Ctrl+B / I" to "粗体 / 斜体",
        "Ctrl+1 2 3" to "标题，Ctrl+0 正文", "Ctrl+= / -" to "字号",
        "Shift+Enter" to "换行不分段", "PageUp / Down" to "翻页",
        "Ctrl+Shift+K" to "显示 / 收起软键盘", "Ctrl+Shift+D" to "调试面板",
        "Ctrl+Shift+E" to "清除残影（墨水屏）",
    )

    fun show() {
        val c = context
        lateinit var dialog: Dialog
        lateinit var close: TextView
        val box = c.inkBox().apply {
            addView(c.panelText("帮助", 18f, Color.BLACK, typeface).apply { setPadding(0, 0, 0, c.px(8)) })
            addView(c.panelText("快捷键", 13f, Color.rgb(0x77, 0x77, 0x77), typeface))
            // 两列：左键名右说明
            val grid = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
            shortcuts.chunked(2).let { rows ->
                listOf(0, 1).forEach { half ->
                    val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
                    rows.forEach { pair ->
                        val (key, what) = pair.getOrNull(half) ?: return@forEach
                        col.addView(LinearLayout(c).apply {
                            orientation = LinearLayout.HORIZONTAL
                            setPadding(0, c.px(3), 0, c.px(3))
                            addView(c.panelText(key, 14f, Color.BLACK, typeface), LinearLayout.LayoutParams(c.px(116), -2))
                            addView(c.panelText(what, 14f, Color.rgb(0x55, 0x55, 0x55), typeface))
                        })
                    }
                    grid.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
                }
            }
            addView(grid)
            addView(c.panelText("Vim 模式下 Esc 进入普通模式，i 回到插入；dw、dd、yy、p、v、/ 等照常用。", 13f,
                Color.rgb(0x77, 0x77, 0x77), typeface).apply { setPadding(0, c.px(8), 0, 0) })
            addView(LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, c.px(16), 0, 0)
                addView(c.focusButton("导出诊断信息", typeface) { dialog.dismiss(); onExportDiagnostics() })
                addView(c.focusButton("输入法设置", typeface) { dialog.dismiss(); onImeSettings() })
                addView(View(c), LinearLayout.LayoutParams(0, 1, 1f))
                close = c.focusButton("关闭", typeface) { dialog.dismiss() }
                addView(close)
            })
            addView(c.panelText("墨墨打 $version · momoda.zhixian.io", 12f, Color.rgb(0x99, 0x99, 0x99), typeface).apply {
                setPadding(0, c.px(12), 0, 0)
            })
        }
        dialog = c.showPanel(box, onDismiss)
        dialog.show()
        // 默认停在“关闭”上：误按 Enter 不会导出诊断信息
        close.post { close.requestFocus() }
    }
}

/**
 * CapsLock 映射：说明怎么在系统里选用墨墨打提供的键盘布局（App 自己拦 CapsLock 拦不住大写锁定，只能走系统布局），
 * 再一键打开系统的“实体键盘”设置。
 */
class CapsLockGuide(
    private val context: Context,
    private val typeface: Typeface?,
    private val onOpenSystem: () -> Unit,
    private val onDismiss: () -> Unit,
) {
    fun show() {
        val c = context
        lateinit var dialog: Dialog
        lateinit var open: TextView
        val box = c.inkBox().apply {
            addView(c.panelText("CapsLock 映射", 18f, Color.BLACK, typeface).apply { setPadding(0, 0, 0, c.px(8)) })
            addView(c.panelText(
                "墨墨打给系统提供了两种键盘布局：“CapsLock 当 Ctrl”和“CapsLock 与 Ctrl 互换”。" +
                    "在系统里选用后，所有 App 里都生效，按 CapsLock 也不会再切换大写。",
                15f, Color.BLACK, typeface,
            ))
            addView(c.panelText(
                "1. 保持实体键盘连接，点下面的“打开系统设置”\n" +
                    "2. 点你的键盘名字\n" +
                    "3. 在输入法下面点一种输入方式（例如“拼音”）\n" +
                    "4. 在布局列表里选“墨墨打：CapsLock 当 Ctrl”\n" +
                    "常用的几种输入方式（例如双拼和英文）都要各选一次。",
                15f, Color.rgb(0x44, 0x44, 0x44), typeface,
            ).apply { setPadding(0, c.px(10), 0, 0) })
            addView(LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, c.px(16), 0, 0)
                open = c.focusButton("打开系统设置", typeface) { dialog.dismiss(); onOpenSystem() }
                addView(open)
                addView(View(c), LinearLayout.LayoutParams(0, 1, 1f))
                addView(c.focusButton("关闭", typeface) { dialog.dismiss() })
            })
        }
        dialog = c.showPanel(box, onDismiss)
        dialog.show()
        // 默认停在“打开系统设置”上：按 Enter 就去
        open.post { open.requestFocus() }
    }
}
