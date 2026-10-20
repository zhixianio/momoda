package app.momoda

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.min

private fun Context.px(v: Int) = (v * resources.displayMetrics.density).toInt()

private fun Context.inkButton(label: String, typeface: Typeface?, onClick: () -> Unit) = TextView(this).apply {
    text = label
    setTextColor(Color.BLACK)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
    this.typeface = typeface
    gravity = Gravity.CENTER
    setPadding(px(14), px(10), px(14), px(10))
    background = GradientDrawable().apply { setColor(Color.WHITE); setStroke(1, Color.rgb(0x99, 0x99, 0x99)) }
    layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = px(8) }
    setOnClickListener { onClick() }
}

private fun Context.inkDialog(box: View, gravity: Int, widthFraction: Float, heightPx: Int): Dialog {
    val d = Dialog(this)
    d.requestWindowFeature(Window.FEATURE_NO_TITLE)
    d.setContentView(box)
    d.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        setWindowAnimations(0)
        val w = resources.displayMetrics.widthPixels
        setLayout(min(px(680), (w * widthFraction).toInt()), heightPx)
        setGravity(gravity)
    }
    return d
}

private fun Context.inkBox() = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = GradientDrawable().apply { setColor(Color.WHITE); setStroke(px(2), Color.BLACK) }
    setPadding(px(18), px(14), px(18), px(14))
}

/**
 * AI 结果面板（DESIGN §6）：固定在屏幕下方，流式显示结果。
 * 为了墨水屏，界面每 0.5 秒才合并刷新一次，而不是每收到一个字就刷新。
 * Enter 执行默认动作（替换或插入），Ctrl+Enter 执行另一个，R 重试，Esc 放弃。AI 永远不会直接改动正文。
 */
class AiResultPanel(
    private val context: Context,
    private val typeface: Typeface?,
    private val title: String,
    private val defaultInsert: Boolean,
    private val run: (Llm.Call, onDelta: (String) -> Unit) -> String,
    private val onReplace: (String) -> Unit,
    private val onInsert: (String) -> Unit,
    private val onDismiss: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var dialog: Dialog
    private lateinit var resultView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var status: TextView
    private var call: Llm.Call? = null
    private val buffer = StringBuilder()
    @Volatile private var finished = false
    private var lastPaint = 0L

    fun show() {
        val c = context
        status = TextView(c).apply {
            setTextColor(Color.rgb(0x77, 0x77, 0x77))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            this.typeface = typeface
        }
        val head = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(c).apply {
                text = title
                setTextColor(Color.BLACK)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                this.typeface = typeface
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = c.px(12) })
            addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        }
        resultView = TextView(c).apply {
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            this.typeface = typeface
            setLineSpacing(0f, 1.5f)
            setTextIsSelectable(false)
        }
        scroll = ScrollView(c).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(resultView)
        }
        val (primary, secondary) = if (defaultInsert) "插入到后面（Enter）" to "替换原文（Ctrl+Enter）" else "替换原文（Enter）" to "插入到后面（Ctrl+Enter）"
        val buttons = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(c.inkButton(primary, typeface) { accept(defaultInsert) })
            addView(c.inkButton(secondary, typeface) { accept(!defaultInsert) })
            addView(c.inkButton("重试（R）", typeface) { start() })
            addView(View(c), LinearLayout.LayoutParams(0, 1, 1f))
            addView(c.inkButton("放弃（Esc）", typeface) { dialog.dismiss() })
        }
        val box = object : LinearLayout(c) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
                when (event.keyCode) {
                    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                        accept(if (event.isCtrlPressed || event.isMetaPressed) !defaultInsert else defaultInsert)
                    KeyEvent.KEYCODE_R -> start()
                    KeyEvent.KEYCODE_ESCAPE -> dialog.dismiss()
                    KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_DPAD_DOWN -> scroll.scrollBy(0, scroll.height - c.px(40))
                    KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_DPAD_UP -> scroll.scrollBy(0, -(scroll.height - c.px(40)))
                    else -> return super.dispatchKeyEvent(event)
                }
                return true
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Color.WHITE); setStroke(c.px(2), Color.BLACK) }
            setPadding(c.px(18), c.px(14), c.px(18), c.px(14))
            isFocusable = true
            isFocusableInTouchMode = true
            addView(head)
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = c.px(10); bottomMargin = c.px(10) })
            addView(buttons)
        }
        val h = (c.resources.displayMetrics.heightPixels * 0.42f).toInt()
        dialog = c.inkDialog(box, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0.9f, h)
        dialog.setOnDismissListener { call?.cancel(); onDismiss() }
        dialog.show()
        box.requestFocus()
        start()
    }

    private fun start() {
        call?.cancel()
        val myCall = Llm.Call()
        call = myCall
        buffer.setLength(0)
        finished = false
        resultView.text = ""
        status.text = "生成中…"
        val t0 = SystemClock.uptimeMillis()
        Thread({
            val result = runCatching {
                run(myCall) { delta ->
                    synchronized(buffer) { buffer.append(delta) }
                    // 合并刷新：距离上次刷新超过 0.5 秒才更新界面
                    val now = SystemClock.uptimeMillis()
                    if (now - lastPaint > 500) { lastPaint = now; main.post { paint() } }
                }
            }
            main.post {
                if (myCall !== call) return@post
                finished = true
                paint()
                result.onSuccess { status.text = "完成 · ${(SystemClock.uptimeMillis() - t0) / 1000.0}秒 · ${it.length} 字" }
                    .onFailure { status.text = if (myCall.cancelled) "已取消" else "出错：${it.message ?: it.javaClass.simpleName}" }
            }
        }, "llm").apply { isDaemon = true }.start()
    }

    private fun paint() {
        val text = synchronized(buffer) { buffer.toString() }
        resultView.text = text
        // 内容超出时整屏跳到末尾，而不是逐行滚动
        scroll.post { scroll.scrollTo(0, (resultView.height - scroll.height).coerceAtLeast(0)) }
    }

    private fun accept(insert: Boolean) {
        val text = synchronized(buffer) { buffer.toString() }.trim()
        if (!finished || text.isEmpty()) {
            status.text = if (!finished) "还在生成，稍等或按 Esc 放弃" else "没有可用的结果"
            return
        }
        dialog.dismiss()
        if (insert) onInsert(text) else onReplace(text)
    }
}

/** AI 设置：接口地址、API key、模型名，以及“测试连接”。 */
class AiConfigPanel(
    private val context: Context,
    private val typeface: Typeface?,
    private val settings: LlmSettings,
    private val onDismiss: () -> Unit,
) {
    fun show() {
        val c = context
        fun label(s: String) = TextView(c).apply {
            text = s
            setTextColor(Color.rgb(0x55, 0x55, 0x55))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            this.typeface = typeface
            setPadding(0, c.px(10), 0, c.px(4))
        }
        fun field(value: String, hint: String, password: Boolean = false) = EditText(c).apply {
            setText(value)
            this.hint = hint
            isSingleLine = true
            this.typeface = typeface
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(Color.BLACK)
            setHintTextColor(Color.rgb(0xAA, 0xAA, 0xAA))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                (if (password) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_URI)
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            background = GradientDrawable().apply { setColor(Color.WHITE); setStroke(1, Color.rgb(0x99, 0x99, 0x99)) }
            setPadding(c.px(12), c.px(8), c.px(12), c.px(8))
        }
        val url = field(settings.baseUrl, "例如 http://100.x.x.x:11434/v1 或 https://api.openai.com/v1")
        val key = field(settings.apiKey, "可以为空（本地模型通常不需要）", password = true)
        val model = field(settings.model, "例如 qwen2.5:14b 或 gpt-4o-mini")
        val result = TextView(c).apply {
            setTextColor(Color.rgb(0x55, 0x55, 0x55))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            this.typeface = typeface
            setPadding(0, c.px(10), 0, 0)
        }
        val main = Handler(Looper.getMainLooper())
        lateinit var dialog: Dialog
        fun save() {
            settings.baseUrl = url.text.toString()
            settings.apiKey = key.text.toString()
            settings.model = model.text.toString()
        }
        fun test() {
            save()
            val cfg = settings.config
            if (!cfg.configured) { result.text = "请先填写接口地址和模型名"; return }
            result.text = "正在测试…"
            Thread({
                val r = runCatching { Llm.stream(cfg, "只回复两个字：你好", "测试", Llm.Call()) {} }
                main.post { result.text = r.fold({ "连接成功，模型回复：${it.take(40)}" }, { "连接失败：${it.message}" }) }
            }, "llm-test").apply { isDaemon = true }.start()
        }
        val box = c.inkBox().apply {
            addView(TextView(c).apply {
                text = "AI 设置（OpenAI 兼容接口）"
                setTextColor(Color.BLACK)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                this.typeface = typeface
            })
            addView(label("接口地址（base_url）")); addView(url)
            addView(label("API key（加密保存在本机）")); addView(key)
            addView(label("模型")); addView(model)
            addView(result)
            addView(LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, c.px(14), 0, 0)
                addView(c.inkButton("测试连接", typeface) { test() })
                addView(View(c), LinearLayout.LayoutParams(0, 1, 1f))
                addView(c.inkButton("取消", typeface) { dialog.dismiss() })
                addView(c.inkButton("保存", typeface) { save(); dialog.dismiss() })
            })
        }
        dialog = c.inkDialog(box, Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0.86f, WindowManager.LayoutParams.WRAP_CONTENT)
        dialog.window?.attributes = dialog.window?.attributes?.apply { y = (c.resources.displayMetrics.heightPixels * 0.08f).toInt() }
        dialog.setOnDismissListener { onDismiss() }
        dialog.show()
        url.requestFocus()
    }
}
