package app.momoda

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {

    private lateinit var store: DocStore
    private lateinit var editor: EditorView
    private lateinit var status: TextView
    private lateinit var debug: TextView
    private lateinit var vim: Vim
    private lateinit var doc: File

    private val handler = Handler(Looper.getMainLooper())
    private val saveRunnable = Runnable { save() }
    private var dirty = false
    private var loading = false

    private var zen = false
    private var textSizeSp = 20f
    private var fontLoadMs = 0L
    private var coldStartMs = -1L

    /** Ctrl 组合键按下时被消费的键码，抬起时也要一并消费。 */
    private val consumedDowns = mutableSetOf<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = DocStore(this)
        val ui = getSharedPreferences("ui", Context.MODE_PRIVATE)
        textSizeSp = ui.getFloat("textSize", 20f)

        editor = EditorView(this)
        val t0 = SystemClock.uptimeMillis()
        val font = Typeface.createFromAsset(assets, "fonts/LXGWWenKaiScreen.ttf")
        fontLoadMs = SystemClock.uptimeMillis() - t0
        editor.setFont(font)
        editor.setTextSizeSp(textSizeSp)

        status = TextView(this).apply {
            setTextColor(Color.rgb(0x55, 0x55, 0x55))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = font
            gravity = Gravity.CENTER
            setPadding(0, editor.dp(6), 0, editor.dp(10))
            setBackgroundColor(Color.WHITE)
        }
        debug = TextView(this).apply {
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
            setBackgroundColor(Color.WHITE)
            setPadding(editor.dp(10), editor.dp(8), editor.dp(10), editor.dp(8))
            visibility = TextView.GONE
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(editor, FrameLayout.LayoutParams(-1, -1).apply { bottomMargin = editor.dp(36) })
            addView(status, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            addView(debug, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                topMargin = editor.dp(8); rightMargin = editor.dp(8)
            })
        }
        setContentView(root)

        vim = Vim(editor) { refreshStatus() }
        vim.enabled = ui.getBoolean("vim", false)

        editor.preImeKeyHandler = ::onKeyPreIme
        editor.postImeKeyHandler = ::onKeyPostIme
        editor.onSelectionChange = { _, _ -> if (vim.enabled) refreshStatus() }
        editor.onLatency = { if (debug.visibility == TextView.VISIBLE) refreshDebug() }
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (loading) return
                dirty = true
                handler.removeCallbacks(saveRunnable)
                handler.postDelayed(saveRunnable, 800)
                refreshStatus()
            }
        })

        open(store.openLastOrCreate())

        editor.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                editor.viewTreeObserver.removeOnPreDrawListener(this)
                coldStartMs = SystemClock.uptimeMillis() - Process.getStartUptimeMillis()
                Log.i("momoda", "coldStart=${coldStartMs}ms font=${fontLoadMs}ms")
                return true
            }
        })
        editor.requestFocus()
    }

    override fun onPause() {
        super.onPause()
        save()
    }

    // ---------- 文档 ----------

    private fun open(file: File) {
        if (::doc.isInitialized) save()
        doc = file
        store.lastDocName = file.name
        loading = true
        editor.setText(store.read(file))
        loading = false
        dirty = false
        val c = store.cursorOf(file)
        editor.setSelection(if (c in 0..editor.text.length) c else editor.text.length)
        refreshStatus()
    }

    private fun save() {
        handler.removeCallbacks(saveRunnable)
        if (!::doc.isInitialized) return
        if (dirty) {
            store.write(doc, editor.text.toString())
            dirty = false
        }
        store.saveCursor(doc, editor.selectionStart)
        refreshStatus()
    }

    private fun newDoc() = open(store.create())

    private fun showDocList() {
        val files = store.list()
        AlertDialog.Builder(this)
            .setTitle("文档（${files.size}）")
            .setItems(files.map { it.nameWithoutExtension }.toTypedArray()) { _, i -> open(files[i]) }
            .show()
    }

    // ---------- 按键 ----------

    private fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP && consumedDowns.remove(keyCode)) return true

        // Ctrl/Cmd + 字母数字符号：在输入法之前处理，避免被输入法吞掉。
        // Ctrl + 方向键、Home/End、退格等编辑键不拦截，走系统默认行为（按词跳转、跳到文首文末等）。
        val ctrl = event.isCtrlPressed || event.isMetaPressed
        if (ctrl && isShortcutKey(keyCode)) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val name = runShortcut(keyCode, event)
                KeyLog.add(event, name ?: "系统快捷键")
                if (name == null) editor.onKeyShortcut(keyCode, asCtrl(event))
                consumedDowns += keyCode
                refreshDebug()
            }
            return true
        }

        if (vim.handle(keyCode, event)) {
            KeyLog.add(event, "vim ${vim.mode}")
            if (event.action == KeyEvent.ACTION_DOWN) consumedDowns += keyCode
            refreshDebug()
            return true
        }

        KeyLog.add(event, "输入法/编辑器")
        refreshDebug()
        return false
    }

    /** 输入法放行的按键。Esc 必须在这里吞掉，否则系统会把它回退成“返回”键直接退出 App。 */
    private fun onKeyPostIme(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode != KeyEvent.KEYCODE_ESCAPE) return false
        if (event.action == KeyEvent.ACTION_DOWN) {
            val toNormal = vim.escapeAfterIme()
            KeyLog.add(event, if (toNormal) "vim → NORMAL" else "吞掉 Esc（输入法未使用）")
            refreshDebug()
        }
        return true
    }

    /** 返回动作名；null 表示不是我们的快捷键，交给系统（撤销、复制、全选等）。 */
    private fun runShortcut(keyCode: Int, e: KeyEvent): String? {
        val shift = e.isShiftPressed
        return when {
            keyCode == KeyEvent.KEYCODE_N && !shift -> { newDoc(); "新建" }
            keyCode == KeyEvent.KEYCODE_O && !shift -> { showDocList(); "文档列表" }
            keyCode == KeyEvent.KEYCODE_K && !shift -> { showPalette(); "命令面板" }
            keyCode == KeyEvent.KEYCODE_S && !shift -> { save(); toast("已保存"); "保存" }
            keyCode == KeyEvent.KEYCODE_B && !shift -> { wrap("**"); "粗体" }
            keyCode == KeyEvent.KEYCODE_I && !shift -> { wrap("*"); "斜体" }
            keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_3 && !shift -> {
                val level = keyCode - KeyEvent.KEYCODE_0
                setHeading(level); if (level == 0) "正文" else "标题 $level"
            }
            keyCode == KeyEvent.KEYCODE_PERIOD -> { toggleZen(); "zen" }
            keyCode == KeyEvent.KEYCODE_EQUALS || keyCode == KeyEvent.KEYCODE_PLUS -> { changeTextSize(+1f); "字号+" }
            keyCode == KeyEvent.KEYCODE_MINUS -> { changeTextSize(-1f); "字号-" }
            keyCode == KeyEvent.KEYCODE_D && shift -> { toggleDebug(); "调试面板" }
            keyCode == KeyEvent.KEYCODE_V && shift -> { toggleVim(); "vim 开关" }
            keyCode == KeyEvent.KEYCODE_R && vim.enabled && vim.mode == Vim.Mode.NORMAL -> {
                vim.handle(keyCode, e); "vim 重做"
            }
            else -> null
        }
    }

    private fun isShortcutKey(keyCode: Int) =
        keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ||
            keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z ||
            keyCode == KeyEvent.KEYCODE_PERIOD || keyCode == KeyEvent.KEYCODE_COMMA ||
            keyCode == KeyEvent.KEYCODE_EQUALS || keyCode == KeyEvent.KEYCODE_MINUS ||
            keyCode == KeyEvent.KEYCODE_PLUS || keyCode == KeyEvent.KEYCODE_SLASH

    private fun asCtrl(e: KeyEvent): KeyEvent {
        if (!e.isMetaPressed) return e
        val meta = (e.metaState and KeyEvent.META_META_MASK.inv()) or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        return KeyEvent(e.downTime, e.eventTime, e.action, e.keyCode, e.repeatCount, meta)
    }

    // ---------- 命令 ----------

    private fun showPalette() {
        val cmds = listOf<Pair<String, () -> Unit>>(
            "新建文档　Ctrl+N" to ::newDoc,
            "打开文档　Ctrl+O" to ::showDocList,
            "Zen 模式　Ctrl+." to ::toggleZen,
            "字号放大　Ctrl+=" to { changeTextSize(+1f) },
            "字号缩小　Ctrl+-" to { changeTextSize(-1f) },
            "Vim 模式开关　Ctrl+Shift+V" to ::toggleVim,
            "调试面板　Ctrl+Shift+D" to ::toggleDebug,
            "删除当前文档（进回收站）" to ::deleteDoc,
        )
        AlertDialog.Builder(this)
            .setTitle("命令")
            .setItems(cmds.map { it.first }.toTypedArray()) { _, i -> cmds[i].second() }
            .show()
    }

    private fun deleteDoc() {
        val victim = doc
        dirty = false
        store.moveToTrash(victim)
        open(store.list().firstOrNull() ?: store.create())
        toast("已移到回收站：${victim.nameWithoutExtension}")
    }

    private fun wrap(marker: String) {
        val s = editor.selectionStart
        val e = editor.selectionEnd
        editor.text.insert(e, marker)
        editor.text.insert(s, marker)
        if (s == e) editor.setSelection(s + marker.length) else editor.setSelection(s + marker.length, e + marker.length)
    }

    private fun setHeading(level: Int) {
        val text = editor.text
        val cur = editor.selectionStart
        val start = if (cur == 0) 0 else text.lastIndexOf('\n', cur - 1) + 1
        val existing = Regex("^#{1,6} ").find(text.subSequence(start, text.length))?.value?.length ?: 0
        val prefix = if (level == 0) "" else "#".repeat(level) + " "
        text.replace(start, start + existing, prefix)
    }

    private fun toggleZen() {
        zen = !zen
        status.visibility = if (zen) TextView.GONE else TextView.VISIBLE
        (editor.layoutParams as FrameLayout.LayoutParams).bottomMargin = if (zen) 0 else editor.dp(36)
        editor.requestLayout()
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                if (zen) {
                    it.hide(WindowInsets.Type.systemBars())
                    it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else {
                    it.show(WindowInsets.Type.systemBars())
                }
            }
        }
    }

    private fun changeTextSize(delta: Float) {
        textSizeSp = (textSizeSp + delta).coerceIn(14f, 36f)
        editor.setTextSizeSp(textSizeSp)
        getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putFloat("textSize", textSizeSp).apply()
        refreshStatus()
    }

    private fun toggleVim() {
        vim.enabled = !vim.enabled
        getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putBoolean("vim", vim.enabled).apply()
        toast(if (vim.enabled) "Vim 模式：开（Esc 进入 normal）" else "Vim 模式：关")
    }

    private fun toggleDebug() {
        debug.visibility = if (debug.visibility == TextView.VISIBLE) TextView.GONE else TextView.VISIBLE
        refreshDebug()
    }

    // ---------- 状态 ----------

    private fun refreshStatus() {
        val parts = mutableListOf("${TextStats.format(TextStats.wordCount(editor.text))} 字")
        if (vim.enabled) parts += if (vim.mode == Vim.Mode.NORMAL) "NORMAL" else "INSERT"
        parts += if (dirty) "编辑中" else "已保存"
        status.text = parts.joinToString(" · ")
    }

    private fun refreshDebug() {
        if (debug.visibility != TextView.VISIBLE) return
        val ime = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/') ?: "?"
        val hardKb = resources.configuration.keyboard == Configuration.KEYBOARD_QWERTY
        val composing = android.view.inputmethod.BaseInputConnection.getComposingSpanStart(editor.text) >= 0
        debug.text = buildString {
            appendLine("输入法  $ime")
            appendLine("实体键盘 ${if (hardKb) "是" else "否"}　拼写中 ${if (composing) "是" else "否"}")
            appendLine("冷启动 ${coldStartMs}ms　字体加载 ${fontLoadMs}ms")
            appendLine("按键→重绘 ${editor.lastLatencyMs}ms（均值 ${"%.0f".format(editor.avgLatencyMs)}ms）")
            appendLine("文件 ${doc.absolutePath}")
            appendLine("──")
            KeyLog.lines.forEach { appendLine(it) }
        }.trimEnd()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
