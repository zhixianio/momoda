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

/**
 * “用 GitHub 登录”：大字显示验证码，用户在手机或电脑上输入；汉王这边只需等待。
 * 成功后回调 token 和可访问的仓库列表。
 */
class DeviceLoginPanel(
    private val context: Context,
    private val typeface: Typeface?,
    private val auth: GitHubAuth,
    private val onSuccess: (token: String, repos: List<String>) -> Unit,
    private val onDismiss: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var done = false

    fun show() {
        val c = context
        fun text(size: Float, color: Int = Color.BLACK) = TextView(c).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(color)
            this.typeface = typeface
            gravity = Gravity.CENTER
        }
        val code = text(44f).apply { letterSpacing = 0.12f; text = "……"; setPadding(0, c.px(18), 0, c.px(18)) }
        val hint = text(16f).apply { setLineSpacing(0f, 1.4f) }
        val status = text(14f, Color.rgb(0x66, 0x66, 0x66)).apply { text = "正在向 GitHub 申请验证码…"; setPadding(0, c.px(12), 0, 0) }
        lateinit var dialog: Dialog
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
            addView(text(18f).apply { text = "用 GitHub 登录" })
            addView(code)
            addView(hint)
            addView(status)
            addView(LinearLayout(c).apply {
                gravity = Gravity.END
                setPadding(0, c.px(16), 0, 0)
                addView(View(c), LinearLayout.LayoutParams(0, 1, 1f))
                addView(c.inkButton("取消（Esc）", typeface) { dialog.dismiss() })
            })
        }
        dialog = c.inkDialog(box, Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0.8f, WindowManager.LayoutParams.WRAP_CONTENT)
        dialog.window?.attributes = dialog.window?.attributes?.apply { y = (c.resources.displayMetrics.heightPixels * 0.15f).toInt() }
        dialog.setOnDismissListener { auth.cancelled = true; onDismiss() }
        dialog.show()
        box.requestFocus()

        Thread({
            val result = runCatching {
                val dc = auth.requestCode()
                main.post {
                    code.text = dc.userCode
                    val site = dc.verificationUri.removePrefix("https://")
                    hint.text = "在手机或电脑上打开\n$site\n输入上面的验证码并点“Authorize”"
                    status.text = "等待授权…（${dc.expiresIn / 60} 分钟内有效）"
                }
                val token = auth.waitForToken(dc)
                main.post { status.text = "授权成功，正在读取可访问的仓库…" }
                token to auth.repositories(token)
            }
            main.post {
                result.onSuccess { (token, repos) ->
                    done = true
                    dialog.dismiss()
                    onSuccess(token, repos)
                }.onFailure {
                    if (it !is GitHubAuth.Cancelled) status.text = "登录失败：${it.message}"
                }
            }
        }, "github-login").apply { isDaemon = true }.start()
    }
}
