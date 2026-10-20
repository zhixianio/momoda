package app.momoda

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/** “从手机或电脑粘贴”的弹窗：显示地址和配对码，收到内容后回调并自动关闭。 */
class PastePanel(
    private val context: Context,
    private val typeface: Typeface?,
    private val onReceived: (Map<String, String>) -> Unit,
    private val onDismiss: () -> Unit,
) {
    fun show() {
        val c = context
        val main = Handler(Looper.getMainLooper())
        fun text(size: Float, color: Int = Color.BLACK) = TextView(c).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(color)
            this.typeface = typeface
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.3f)
        }
        lateinit var dialog: Dialog
        val status = text(14f, Color.rgb(0x66, 0x66, 0x66)).apply { text = "等待手机或电脑提交…" }
        val server = PasteServer(
            onSubmit = { fields -> main.post { dialog.dismiss(); onReceived(fields) } },
            onClosed = { reason -> main.post { if (reason.isNotEmpty()) status.text = reason } },
        )
        val url = server.start()
        // 汉王的 Wi-Fi 省电模式会让局域网请求时通时断；窗口打开期间保持 Wi-Fi 全速，关闭时释放
        val wifi = c.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
        @Suppress("DEPRECATION")
        val lock = wifi?.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "momoda-paste")
        runCatching { lock?.acquire() }
        val box = object : LinearLayout(c) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_ESCAPE) { dialog.dismiss(); return true }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply { setColor(Color.WHITE); setStroke(c.px(2), Color.BLACK) }
            setPadding(c.px(24), c.px(20), c.px(24), c.px(16))
            isFocusable = true
            isFocusableInTouchMode = true
            addView(text(18f).apply { text = "从手机或电脑粘贴" })
            if (url == null) {
                addView(text(16f).apply { text = "没有连上 Wi-Fi，找不到局域网地址。"; setPadding(0, c.px(16), 0, 0) })
            } else {
                addView(text(15f).apply { text = "在同一 Wi-Fi 下的手机或电脑浏览器里打开"; setPadding(0, c.px(14), 0, 0) })
                addView(text(26f).apply { text = url.removePrefix("http://"); setPadding(0, c.px(6), 0, c.px(6)) })
                addView(text(15f).apply { text = "配对码" })
                addView(text(40f).apply { text = server.code; letterSpacing = 0.3f })
                addView(status.apply { setPadding(0, c.px(10), 0, 0) })
                addView(text(12f, Color.rgb(0x88, 0x88, 0x88)).apply {
                    text = "提交一次或 10 分钟后自动关闭。局域网内明文传输，请只在自己信任的 Wi-Fi 下使用。"
                    setPadding(0, c.px(10), 0, 0)
                })
            }
            addView(LinearLayout(c).apply {
                setPadding(0, c.px(16), 0, 0)
                addView(View(c), LinearLayout.LayoutParams(0, 1, 1f))
                addView(c.inkButton("关闭（Esc）", typeface) { dialog.dismiss() })
            })
        }
        dialog = c.inkDialog(box, Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0.8f, WindowManager.LayoutParams.WRAP_CONTENT)
        dialog.window?.attributes = dialog.window?.attributes?.apply { y = (c.resources.displayMetrics.heightPixels * 0.12f).toInt() }
        dialog.setOnDismissListener {
            server.close()
            runCatching { if (lock?.isHeld == true) lock.release() }
            onDismiss()
        }
        dialog.show()
        box.requestFocus()
    }
}
