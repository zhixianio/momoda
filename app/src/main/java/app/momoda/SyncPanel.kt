package app.momoda

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** GitHub 同步设置：仓库、分支、token、自动同步、测试连接。 */
class SyncConfigPanel(
    private val context: Context,
    private val typeface: Typeface?,
    private val settings: SyncSettings,
    private val makeSync: () -> GitHubSync,
    private val onLogin: () -> Unit,
    /** 列出 GitHub App 能访问的仓库让用户选，选中后回调仓库名。 */
    private val pickRepo: ((String) -> Unit) -> Unit,
    /** 在 GitHub 上增减可访问仓库的地址。 */
    private val manageUrl: String,
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
        val repo = field(settings.repo, "你的用户名/仓库名，例如 frank/notes")
        val branch = field(settings.branch, "main")
        val subdir = field(settings.subdir, "留空表示仓库根目录，例如 notes/momoda")
        val token = field(settings.token, "github_pat_…", password = true)
        var auto = settings.auto
        val autoView = TextView(c).apply {
            this.typeface = typeface
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(Color.BLACK)
            setPadding(0, c.px(12), 0, 0)
        }
        fun paintAuto() { autoView.text = "自动同步：${if (auto) "开" else "关"}（离开 App 和回到 App 时）　点此切换" }
        paintAuto()
        autoView.setOnClickListener { auto = !auto; paintAuto() }
        val result = TextView(c).apply {
            setTextColor(Color.rgb(0x55, 0x55, 0x55))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            this.typeface = typeface
            setPadding(0, c.px(10), 0, 0)
        }
        val help = TextView(c).apply {
            text = "token 的创建方法：GitHub → Settings → Developer settings → Fine-grained tokens → " +
                "Repository access 只选这个仓库 → Permissions 里 Contents 选 Read and write。" +
                "token 加密保存在本机，只用来访问这一个仓库。"
            setTextColor(Color.rgb(0x77, 0x77, 0x77))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            this.typeface = typeface
            setLineSpacing(0f, 1.3f)
            setPadding(0, c.px(10), 0, 0)
        }
        val main = Handler(Looper.getMainLooper())
        lateinit var dialog: android.app.Dialog
        fun save() {
            settings.repo = repo.text.toString()
            settings.branch = branch.text.toString()
            settings.subdir = subdir.text.toString()
            settings.token = token.text.toString()
            settings.auto = auto
        }
        fun test() {
            save()
            if (!settings.configured) { result.text = "请填写“用户名/仓库名”和 token"; return }
            result.text = "正在连接…"
            Thread({
                val r = runCatching { makeSync().check() }
                main.post { result.text = r.fold({ it }, { "连接失败：${it.message}" }) }
            }, "sync-check").apply { isDaemon = true }.start()
        }
        val box = c.inkBox().apply {
            addView(TextView(c).apply {
                text = "GitHub 同步"
                setTextColor(Color.BLACK)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                this.typeface = typeface
            })
            addView(LinearLayout(c).apply {
                setPadding(0, c.px(12), 0, c.px(4))
                addView(c.inkButton("用 GitHub 登录（推荐：在手机或电脑上输入验证码，不用打字）", typeface) {
                    dialog.dismiss(); onLogin()
                })
            })
            addView(label("仓库")); addView(repo)
            addView(LinearLayout(c).apply {
                setPadding(0, c.px(8), 0, 0)
                addView(c.inkButton("从可访问的仓库里选", typeface) { pickRepo { chosen -> repo.setText(chosen) } })
            })
            addView(TextView(c).apply {
                text = "想同步到别的仓库？先在 GitHub 上把它加进可访问列表：${manageUrl.removePrefix("https://")}"
                setTextColor(Color.rgb(0x77, 0x77, 0x77))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                this.typeface = typeface
                setPadding(0, c.px(6), 0, 0)
            })
            addView(label("分支")); addView(branch)
            addView(label("仓库内目录（可选）")); addView(subdir)
            addView(TextView(c).apply {
                text = "只同步这个目录，目录外的文件不会被读取或改动。切换仓库或目录后，第一次同步会合并两边的文件，同名但内容不同的会另存一份，不会丢。"
                setTextColor(Color.rgb(0x77, 0x77, 0x77))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                this.typeface = typeface
                setLineSpacing(0f, 1.3f)
                setPadding(0, c.px(6), 0, 0)
            })
            addView(label("Fine-grained token")); addView(token)
            addView(autoView)
            addView(help)
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
        dialog.window?.attributes = dialog.window?.attributes?.apply { y = (c.resources.displayMetrics.heightPixels * 0.06f).toInt() }
        dialog.setOnDismissListener { onDismiss() }
        dialog.show()
        repo.requestFocus()
    }
}
