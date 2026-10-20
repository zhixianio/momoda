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
 * 授权成功但 GitHub App 还没装到任何仓库时的引导：显示安装地址，每 5 秒查一次，
 * 用户在手机或电脑上装好后自动继续，汉王上不用再按任何键。
 */
class InstallGuidePanel(
    private val context: Context,
    private val typeface: Typeface?,
    private val installUrl: String,
    private val check: () -> List<String>,
    private val onRepos: (List<String>) -> Unit,
    private val onDismiss: () -> Unit,
    /** true：已经装过，只是要再加几个仓库（说明文字不同）。 */
    private val adding: Boolean = false,
) {
    fun show() {
        val c = context
        val main = Handler(Looper.getMainLooper())
        fun text(size: Float, color: Int = Color.BLACK) = TextView(c).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(color)
            this.typeface = typeface
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.35f)
        }
        lateinit var dialog: Dialog
        var done = false
        val status = text(14f, Color.rgb(0x66, 0x66, 0x66)).apply { text = if (adding) "等待添加…（每 5 秒检查一次）" else "等待安装…（每 5 秒检查一次）" }
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
            addView(text(18f).apply { text = if (adding) "让墨墨打访问其他仓库" else "还差一步：把墨墨打装到你的笔记仓库" })
            addView(text(15f).apply {
                text = if (adding) "在手机或电脑上打开下面的地址，\n在 Repository access 里勾上要用的仓库，点 Save。"
                else "已经登录成功。现在在手机或电脑上打开下面的地址，\n选择“Only select repositories”，只勾你的笔记仓库，点 Install。"
                setPadding(0, c.px(14), 0, c.px(8))
            })
            addView(text(22f).apply { text = installUrl.removePrefix("https://"); setPadding(0, c.px(6), 0, c.px(10)) })
            addView(text(13f, Color.rgb(0x88, 0x88, 0x88)).apply {
                text = "墨墨打只申请 Contents（读写）和 Metadata（只读）权限；登录凭证只保存在这台设备上。\n还没有笔记仓库的话，先在 GitHub 上新建一个私有仓库（勾选 Add a README）。"
            })
            addView(status.apply { setPadding(0, c.px(12), 0, 0) })
            addView(LinearLayout(c).apply {
                setPadding(0, c.px(16), 0, 0)
                addView(View(c), LinearLayout.LayoutParams(0, 1, 1f))
                addView(c.inkButton("关闭（Esc）", typeface) { dialog.dismiss() })
            })
        }
        dialog = c.inkDialog(box, Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0.84f, WindowManager.LayoutParams.WRAP_CONTENT)
        dialog.window?.attributes = dialog.window?.attributes?.apply { y = (c.resources.displayMetrics.heightPixels * 0.12f).toInt() }
        dialog.setOnDismissListener { done = true; onDismiss() }
        dialog.show()
        box.requestFocus()

        Thread({
            while (!done) {
                Thread.sleep(5000)
                if (done) break
                val repos = runCatching(check).getOrElse { e -> main.post { status.text = "检查失败：${e.message}，稍后重试" }; emptyList() }
                if (repos.isNotEmpty()) {
                    main.post {
                        if (!done) { done = true; dialog.dismiss(); onRepos(repos) }
                    }
                    break
                }
            }
        }, "install-wait").apply { isDaemon = true }.start()
    }
}
