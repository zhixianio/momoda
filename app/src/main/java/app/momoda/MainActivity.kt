package app.momoda

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var store: DocStore
    private lateinit var editor: EditorView
    private lateinit var header: LinearLayout
    private lateinit var headerTitle: TextView
    private lateinit var headerClock: TextView
    private lateinit var footer: TextView
    private lateinit var debug: TextView
    private lateinit var menuButton: TextView
    private lateinit var font: Typeface
    private lateinit var vim: Vim
    private lateinit var doc: File

    private val ui by lazy { getSharedPreferences("ui", Context.MODE_PRIVATE) }
    private val settings by lazy { UserSettings(this) }
    private val llm by lazy { LlmSettings(this) }
    private val sync by lazy { SyncSettings(this) }
    private var syncing = false
    private var syncStatus = ""
    private val fonts by lazy { Fonts(assets) { store.dir } }
    private val updates by lazy {
        // debug 包可以用 `--es updateApi <url>` 指向本地模拟的发布地址做测试
        val override = if (BuildConfig.DEBUG) ui.getString("updateApiOverride", null) else null
        Updates(this, override ?: BuildConfig.UPDATE_API)
    }
    private val versionName by lazy { packageManager.getPackageInfo(packageName, 0).versionName ?: "0" }
    private val handler = Handler(Looper.getMainLooper())
    private val saveRunnable = Runnable { save() }
    private val footerRunnable = Runnable { refreshChrome() }
    private val clockRunnable = object : Runnable {
        override fun run() {
            refreshClock()
            val now = Calendar.getInstance()
            handler.postDelayed(this, (60 - now.get(Calendar.SECOND)) * 1000L + 50)
        }
    }
    private var dirty = false
    /** 最近一次保存失败的原因；保存成功后清空。页脚会一直提示，直到保存成功。 */
    private var saveError: String? = null

    private var fontLoadMs = 0L
    private var coldStartMs = -1L

    /** Ctrl 组合键按下时被消费的键码，抬起时也要一并消费。 */
    private val consumedDowns = mutableSetOf<Int>()

    private val gray = Color.rgb(0x77, 0x77, 0x77)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tStart = SystemClock.uptimeMillis()
        Diagnostics.install(this) {
            // 崩溃前尽量把正在写的内容存下来
            if (::doc.isInitialized && dirty) store.write(doc, editor.text.toString())
        }
        if (BuildConfig.DEBUG) intent.getStringExtra("githubApi")?.let {
            // debug 包：用本地模拟的 GitHub API 测试同步
            SyncSettings(this).apply {
                apiBase = it
                intent.getStringExtra("githubRepo")?.let { r -> repo = r }
                intent.getStringExtra("githubToken")?.let { t -> token = t }
                intent.getStringExtra("githubWeb")?.let { w -> webBase = w }
                intent.getStringExtra("githubClientId")?.let { id -> clientId = id }
            }
        }
        if (BuildConfig.DEBUG) intent.getStringExtra("updateApi")?.let {
            getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putString("updateApiOverride", it).apply()
            getSharedPreferences("updates", Context.MODE_PRIVATE).edit().clear().apply()
        }
        store = DocStore(this)
        val tStore = SystemClock.uptimeMillis()

        editor = EditorView(this)
        val t0 = SystemClock.uptimeMillis()
        font = fonts.load(settings.font)
        fontLoadMs = SystemClock.uptimeMillis() - t0

        buildLayout()

        vim = Vim(editor) { }
        applySettings()

        editor.preImeKeyHandler = ::onKeyPreIme
        editor.postImeKeyHandler = ::onKeyPostIme
        editor.onLatency = { if (debug.visibility == View.VISIBLE) refreshDebug() }
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (!editor.rulesEnabled) return // 正在载入文档
                dirty = true
                handler.removeCallbacks(saveRunnable)
                handler.postDelayed(saveRunnable, 800)
                // 页脚字数和页眉标题在停止打字 2 秒后才更新，避免每个字都让页眉页脚变化
                handler.removeCallbacks(footerRunnable)
                handler.postDelayed(footerRunnable, 2000)
            }
        })

        val tViews = SystemClock.uptimeMillis()
        open(store.openLastOrCreate())
        val tOpen = SystemClock.uptimeMillis()
        Log.i("momoda", "startup: sinceProcess=${tStart - Process.getStartUptimeMillis()}ms store=${tStore - tStart}ms views=${tViews - tStore}ms open=${tOpen - tViews}ms")

        editor.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                editor.viewTreeObserver.removeOnPreDrawListener(this)
                coldStartMs = SystemClock.uptimeMillis() - Process.getStartUptimeMillis()
                Log.i("momoda", "coldStart=${coldStartMs}ms font=${fontLoadMs}ms dir=${store.dir}")
                // 拼音表只给搜索用，等第一帧画完再在后台读，不和冷启动抢 CPU
                handler.postDelayed({ Pinyin.warmUp(assets) }, 300)
                // 检查更新同样推迟，而且每天最多联网一次
                handler.postDelayed({ checkForUpdates(force = false) }, 3000)
                Thread { Diagnostics.recordExitReasons(this@MainActivity) }.start()
                return true
            }
        })
        editor.requestFocus()

        if (!store.isPublic && !store.askedForAccess) handler.post { askForStorageAccess() }
    }

    private fun buildLayout() {
        val dp = { v: Int -> editor.dp(v) }
        headerTitle = chromeText().apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        headerClock = chromeText()
        header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(28), dp(14), dp(28), 0)
            addView(headerTitle, LinearLayout.LayoutParams(0, -2, 1f))
            addView(headerClock, LinearLayout.LayoutParams(-2, -2))
        }
        footer = chromeText().apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(14))
            setOnClickListener { showPalette() }
        }
        // 笔/触控入口：右下角的“⋯”常驻（zen 模式下也在），点页脚也能打开命令面板。
        // 用软键盘或笔的时候没有 Ctrl+K，这是唯一入口。
        menuButton = TextView(this).apply {
            text = "⋯"
            setTextColor(Color.rgb(0x88, 0x88, 0x88))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            gravity = Gravity.CENTER
            contentDescription = "命令"
            isFocusable = false
            setOnClickListener { showPalette() }
        }
        debug = TextView(this).apply {
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
            setBackgroundColor(Color.WHITE)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            visibility = View.GONE
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header, LinearLayout.LayoutParams(-1, -2))
            addView(editor, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(footer, LinearLayout.LayoutParams(-1, -2))
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(column, FrameLayout.LayoutParams(-1, -1))
            addView(menuButton, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM or Gravity.END))
            addView(debug, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                topMargin = dp(8); rightMargin = dp(8)
            })
        }
        setContentView(root)
    }

    private fun chromeText() = TextView(this).apply {
        setTextColor(gray)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = font
        includeFontPadding = false
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        handler.post(clockRunnable)
        // 刚从系统设置授权回来：把文档搬到 Documents/momoda，并重新打开
        if (store.refreshLocation()) {
            loadDoc(store.openLastOrCreate())
            toast("文档已移到 ${store.dir.absolutePath.removePrefix("/storage/emulated/0/")}")
        } else if (::doc.isInitialized && !dirty && store.changedOnDisk(doc)) {
            // 离开期间文档被别的程序改过，而我们没有未保存的修改：直接载入新版本
            if (doc.exists()) loadDoc(doc) else loadDoc(store.openLastOrCreate())
        }
        if (::doc.isInitialized) autoSync(minIntervalMs = 10 * 60_000L)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(clockRunnable)
        save()
        // 离开 App 也算“离开文档”：文件名跟上标题
        doc = store.syncNameWithTitle(doc, editor.text.toString())
        store.lastDocName = doc.name
        autoSync(minIntervalMs = 2 * 60_000L)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /** 系统状态栏一直隐藏；页眉页脚由 App 自己画（DESIGN §3.1）。 */
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    // ---------- 文档 ----------

    /** 切换到另一篇文档：先“离开”当前文档（保存、改名、清理空文档）。 */
    private fun open(file: File) {
        if (::doc.isInitialized && file != doc) {
            val left = leaveCurrent()
            if (left != null) store.previousDocName = left.name
        }
        loadDoc(file)
    }

    private fun loadDoc(file: File) {
        doc = file
        store.lastDocName = file.name
        editor.load(store.read(file))
        dirty = false
        val c = store.cursorOf(file)
        editor.setSelection(if (c in 0..editor.text.length) c else editor.text.length)
        refreshChrome()
    }

    /** 返回离开后的文件；空文档会被删掉，返回 null。 */
    private fun leaveCurrent(): File? {
        save()
        val text = editor.text.toString()
        if (store.isEmptyDoc(text) && store.list().size > 1) {
            store.delete(doc)
            return null
        }
        return store.syncNameWithTitle(doc, text)
    }

    private fun save() {
        handler.removeCallbacks(saveRunnable)
        if (!::doc.isInitialized) return
        if (dirty) {
            val text = editor.text.toString()
            try {
                if (store.changedOnDisk(doc)) {
                    // 文档被别的程序改过（例如通过 USB 拷进了新版本）：不覆盖对方，把我们的版本另存一份
                    val copy = store.saveConflictCopy(doc, text)
                    toast("《${doc.nameWithoutExtension}》被其他程序修改过，你的版本另存为《${copy.nameWithoutExtension}》")
                    doc = copy
                    store.lastDocName = copy.name
                } else {
                    store.write(doc, text)
                }
                dirty = false
                if (saveError != null) { saveError = null; refreshChrome() }
            } catch (e: Exception) {
                Log.e("momoda", "保存失败 ${doc.absolutePath}", e)
                val first = saveError == null
                saveError = e.message ?: e.javaClass.simpleName
                refreshChrome()
                if (first) toast("保存失败：$saveError")
                // 稍后自动重试；dirty 保持为 true
                handler.postDelayed(saveRunnable, 5000)
            }
        }
        store.saveCursor(doc, editor.selectionStart)
    }

    private fun newDoc() = open(store.create())

    private fun switchToPrevious() {
        val prev = store.previousDocName?.let { File(store.dir, it) }?.takeIf { it.exists() && it != doc }
        if (prev == null) { toast("没有上一篇"); return }
        open(prev)
    }

    private val dateFmt = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)

    /** 快速切换（Ctrl+O）：标题 + 修改时间 · 字数，支持拼音首字母搜索。 */
    private fun showSwitcher() {
        save()
        val files = store.list()
        val items = files.map { f ->
            val text = if (f == doc) editor.text.toString() else store.read(f)
            val title = Titles.titleOf(text) ?: f.nameWithoutExtension
            val detail = buildString {
                append(dateFmt.format(Date(f.lastModified())))
                append(" · ").append(TextStats.format(TextStats.wordCount(text))).append(" 字")
                if (f == doc) append(" · 当前")
            }
            InkItem(title, detail)
        }
        InkList.show(this, "文档（${files.size}）", items, font, onDismiss = ::returnToEditor) { i ->
            if (files[i] != doc) open(files[i])
        }
    }

    /** 修改标题：改的是首行，文件名随之更新。 */
    private fun renameTitle() {
        val t = editor.text
        val firstLineEnd = t.indexOf('\n').let { if (it < 0) t.length else it }
        val firstLine = t.subSequence(0, firstLineEnd).toString()
        val marker = Regex("^#{1,6}\\s*").find(firstLine)?.value ?: ""
        InkList.prompt(this, "修改标题", Titles.titleOf(t) ?: "", font, onDismiss = ::returnToEditor) { newTitle ->
            val prefix = marker.ifEmpty { "# " }.trimEnd() + " "
            t.replace(0, firstLineEnd, prefix + newTitle.trim())
            save()
            doc = store.syncNameWithTitle(doc, editor.text.toString())
            store.lastDocName = doc.name
            refreshChrome()
        }
    }

    /** 全屏文档管理页（含回收站）。 */
    private fun showManager() {
        save()
        DocManagerPanel(this, store, font, current = { doc }, actions = object : DocManagerPanel.Actions {
            override fun open(file: File) { if (file != doc) this@MainActivity.open(file) }

            override fun retitle(file: File, onDone: () -> Unit) {
                val text = if (file == doc) editor.text.toString() else store.read(file)
                InkList.prompt(this@MainActivity, "修改标题", Titles.titleOf(text) ?: "", font) { newTitle ->
                    if (file == doc) {
                        editor.text.replace(0, editor.text.length, Titles.withTitle(editor.text.toString(), newTitle))
                        save()
                        doc = store.syncNameWithTitle(doc, editor.text.toString())
                        store.lastDocName = doc.name
                        refreshChrome()
                    } else {
                        store.retitle(file, newTitle)
                    }
                    onDone()
                }
            }

            override fun changed() {
                // 当前文档被移进回收站：不要再往原路径保存，换一篇显示
                if (!doc.exists()) {
                    dirty = false
                    handler.removeCallbacks(saveRunnable)
                    loadDoc(store.list().firstOrNull() ?: store.create())
                }
            }

            override fun dismissed() = returnToEditor()
        }).show()
    }

    // ---------- 存储权限 ----------

    private fun askForStorageAccess() {
        store.askedForAccess = true
        val options = listOf(
            InkItem("去授权", "推荐：卸载 App 也不会丢文档"),
            InkItem("暂不", "先存在 App 内部，之后可在命令面板里授权"),
        )
        InkList.show(this, "把文档存到 Documents/momoda？", options, font, searchHint = null, onDismiss = ::returnToEditor) { i ->
            if (i == 0) requestStorageAccess()
        }
    }

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            val intents = listOf(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")),
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
            )
            for (intent in intents) {
                try { startActivity(intent); return } catch (e: Exception) { Log.w("momoda", "无法打开 $intent", e) }
            }
            toast("这台设备打不开授权页面")
        } else {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (store.refreshLocation()) loadDoc(store.openLastOrCreate())
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

        KeyLog.add(event, if (vim.enabled) "输入法/编辑器（vim ${vim.mode}）" else "输入法/编辑器")
        refreshDebug()
        return false
    }

    /** 输入法放行的按键。Esc 必须在这里吞掉，否则系统会把它回退成“返回”键直接退出 App。 */
    private fun onKeyPostIme(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode != KeyEvent.KEYCODE_ESCAPE) return false
        if (event.action == KeyEvent.ACTION_DOWN) {
            KeyLog.add(event, "吞掉 Esc（输入法未使用）")
            refreshDebug()
        }
        return true
    }

    /** 返回动作名；null 表示不是我们的快捷键，交给系统（撤销、复制、全选等）。 */
    private fun runShortcut(keyCode: Int, e: KeyEvent): String? {
        val shift = e.isShiftPressed
        return when {
            keyCode == KeyEvent.KEYCODE_N && !shift -> { newDoc(); "新建" }
            keyCode == KeyEvent.KEYCODE_O && !shift -> { showSwitcher(); "快速切换" }
            keyCode == KeyEvent.KEYCODE_TAB -> { switchToPrevious(); "上一篇" }
            keyCode == KeyEvent.KEYCODE_K && !shift -> { showPalette(); "命令面板" }
            keyCode == KeyEvent.KEYCODE_S && !shift -> { save(); "保存" }
            keyCode == KeyEvent.KEYCODE_S && shift -> { syncNow(force = true); "同步" }
            keyCode == KeyEvent.KEYCODE_B && !shift -> { wrap("**"); "粗体" }
            keyCode == KeyEvent.KEYCODE_I && !shift -> { wrap("*"); "斜体" }
            keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_3 && !shift -> {
                val level = keyCode - KeyEvent.KEYCODE_0
                setHeading(level); if (level == 0) "正文" else "标题 $level"
            }
            keyCode == KeyEvent.KEYCODE_PERIOD -> { toggleZen(); "zen" }
            keyCode == KeyEvent.KEYCODE_COMMA -> { showSettings(); "设置" }
            keyCode == KeyEvent.KEYCODE_J && !shift -> { showAi(); "AI" }
            keyCode == KeyEvent.KEYCODE_EQUALS || keyCode == KeyEvent.KEYCODE_PLUS -> { changeTextSize(+1f); "字号+" }
            keyCode == KeyEvent.KEYCODE_MINUS -> { changeTextSize(-1f); "字号-" }
            keyCode == KeyEvent.KEYCODE_D && shift -> { toggleDebug(); "调试面板" }
            keyCode == KeyEvent.KEYCODE_V && shift -> { toggleVim(); "vim 开关" }
            keyCode == KeyEvent.KEYCODE_F && shift -> { toggleFocus(); "专注模式" }
            keyCode == KeyEvent.KEYCODE_R && vim.enabled && vim.mode == Vim.Mode.NORMAL -> {
                vim.handle(keyCode, e); "vim 重做"
            }
            else -> null
        }
    }

    private fun isShortcutKey(keyCode: Int) =
        keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ||
            keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z ||
            keyCode == KeyEvent.KEYCODE_TAB ||
            keyCode == KeyEvent.KEYCODE_PERIOD || keyCode == KeyEvent.KEYCODE_COMMA ||
            keyCode == KeyEvent.KEYCODE_EQUALS || keyCode == KeyEvent.KEYCODE_MINUS ||
            keyCode == KeyEvent.KEYCODE_PLUS || keyCode == KeyEvent.KEYCODE_SLASH

    private fun asCtrl(e: KeyEvent): KeyEvent {
        if (!e.isMetaPressed) return e
        val meta = (e.metaState and KeyEvent.META_META_MASK.inv()) or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        return KeyEvent(e.downTime, e.eventTime, e.action, e.keyCode, e.repeatCount, meta)
    }

    // ---------- 命令面板 ----------

    private class Command(val id: String, val label: String, val shortcut: String = "", val run: () -> Unit)

    private fun commands(): List<Command> = buildList {
        add(Command("new", "新建文档", "Ctrl+N", ::newDoc))
        add(Command("switch", "打开文档", "Ctrl+O", ::showSwitcher))
        add(Command("previous", "切回上一篇", "Ctrl+Tab", ::switchToPrevious))
        add(Command("rename", "修改标题", "", ::renameTitle))
        add(Command("manage", "管理文档", "回收站、排序、批量查看", ::showManager))
        add(Command("settings", "设置", "Ctrl+,", ::showSettings))
        add(Command("sync", "立即同步", if (sync.configured) "${sync.repo} · Ctrl+Shift+S" else "未配置", { syncNow(force = true) }))
        add(Command("github-login", "用 GitHub 登录（同步）", "手机或电脑上输入验证码，不用打 token", ::loginGitHub))
        add(Command("sync-settings", "GitHub 同步设置", if (sync.configured) sync.repo else "未配置", ::showSyncConfig))
        add(Command("ai", "AI 助手", "Ctrl+J", ::showAi))
        add(Command("ai-settings", "AI 设置", if (llm.config.configured) llm.model else "未配置", ::showAiConfig))
        add(Command("ai-prompts", "编辑 AI 指令", Prompts.FILE_NAME, ::editPrompts))
        add(Command("zen", "Zen 模式", "Ctrl+.", ::toggleZen))
        add(Command("focus", "专注模式开关", "Ctrl+Shift+F", ::toggleFocus))
        add(Command("bigger", "字号放大", "Ctrl+=") { changeTextSize(+1f) })
        add(Command("smaller", "字号缩小", "Ctrl+-") { changeTextSize(-1f) })
        add(Command("vim", "Vim 模式开关", "Ctrl+Shift+V", ::toggleVim))
        add(Command("trash", "删除当前文档", "进回收站", ::deleteDoc))
        if (!store.isPublic) add(Command("access", "授权：把文档存到 Documents/momoda", "", ::requestStorageAccess))
        updates.latest?.let { r -> add(Command("install-update", "安装新版本 ${r.version}", "从 GitHub 下载") { installUpdate(r) }) }
        add(Command("check-update", "检查更新", if (updates.configured) "" else "尚未配置发布地址") { checkForUpdates(force = true) })
        add(Command("diagnostics", "导出诊断信息", "崩溃记录等，不含文档内容", ::exportDiagnostics))
        add(Command("debug", "调试面板", "Ctrl+Shift+D", ::toggleDebug))
        add(Command("about", "系统设置 · 关于本机", "连点版本号开启开发者选项") {
            openSystemSettings(Settings.ACTION_DEVICE_INFO_SETTINGS, "Settings\$MyDeviceInfoActivity")
        })
        add(Command("devopts", "系统设置 · 开发者选项") {
            openSystemSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, "Settings\$DevelopmentSettingsDashboardActivity")
        })
    }

    /** 命令面板：最近用过的排在前面（DESIGN §3.2）。 */
    private fun showPalette() {
        val recent = ui.getString("recentCommands", "")!!.split(',').filter { it.isNotEmpty() }
        val cmds = commands().sortedBy { c -> recent.indexOf(c.id).let { if (it < 0) Int.MAX_VALUE else it } }
        InkList.show(this, "命令", cmds.map { InkItem(it.label, it.shortcut) }, font, onDismiss = ::returnToEditor) { i ->
            val c = cmds[i]
            ui.edit().putString("recentCommands", (listOf(c.id) + recent.filter { it != c.id }).take(8).joinToString(",")).apply()
            c.run()
        }
    }

    /** 有些墨水屏厂商的定制设置里藏掉了这些页面，直接拉起系统原生设置。 */
    private fun openSystemSettings(action: String, fallbackActivity: String) {
        val intents = listOf(
            Intent(action),
            Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.$fallbackActivity")),
        )
        for (intent in intents) {
            try {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: Exception) {
                Log.w("momoda", "无法打开 $intent", e)
            }
        }
        toast("这台设备打不开该设置页")
    }

    /**
     * 弹窗关闭后把焦点和输入法连接还给编辑器。
     * 否则有的输入法（汉王）会继续往已经关掉的弹窗里上屏，表现为“光标消失、字不知去向”。
     */
    private fun returnToEditor() {
        editor.requestFocus()
        getSystemService(InputMethodManager::class.java).restartInput(editor)
    }

    private fun deleteDoc() {
        val victim = doc
        val title = Titles.titleOf(editor.text) ?: victim.nameWithoutExtension
        dirty = false
        store.moveToTrash(victim)
        loadDoc(store.list().firstOrNull() ?: store.create())
        toast("已移到回收站：$title")
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

    // ---------- 设置 ----------

    /** 把 [settings] 全部应用到界面上。启动时和设置变化后调用。 */
    private fun applySettings() {
        applyFont(fonts.load(settings.font))
        editor.setTextSizeSp(settings.textSize)
        editor.setLineSpacingMultiplier(settings.lineSpacing)
        editor.paragraphSpacing = settings.paragraphSpacing
        editor.charsPerLine = settings.charsPerLine
        editor.indentParagraphs = settings.indent
        editor.focusMode = settings.focus
        if (vim.enabled != settings.vim) vim.enabled = settings.vim
        val v = if (settings.chrome) View.VISIBLE else View.GONE
        header.visibility = v
        footer.visibility = v
    }

    /** 全局统一一种字体：正文、页眉页脚、弹窗（DESIGN §2.8）。 */
    private fun applyFont(tf: Typeface) {
        font = tf
        editor.setFont(tf)
        headerTitle.typeface = tf
        headerClock.typeface = tf
        footer.typeface = tf
    }

    private fun showSettings() {
        save()
        fun <T> step(list: List<T>, now: T, d: Int): T = list[(list.indexOf(now).coerceAtLeast(0) + d).coerceIn(0, list.size - 1)]
        fun onOff(b: Boolean) = if (b) "开" else "关"
        val rows = listOf(
            SettingsPanel.Row("字体", { fonts.available().firstOrNull { it.key == settings.font }?.label ?: fonts.builtIn.label }, { d ->
                val all = fonts.available()
                settings.font = step(all, all.firstOrNull { it.key == settings.font } ?: fonts.builtIn, d).key
                applySettings()
            }),
            SettingsPanel.Row("字号", { "%.0f".format(settings.textSize) }, { d -> settings.textSize += d; applySettings() }),
            SettingsPanel.Row("行距", { "%.2f".format(settings.lineSpacing).trimEnd('0').trimEnd('.') }, { d ->
                settings.lineSpacing = step(UserSettings.LINE_SPACINGS, settings.lineSpacing, d); applySettings()
            }),
            SettingsPanel.Row("段距", { paragraphLabel(settings.paragraphSpacing) }, { d ->
                settings.paragraphSpacing = step(UserSettings.PARAGRAPH_SPACINGS, settings.paragraphSpacing, d); applySettings()
            }),
            SettingsPanel.Row("版心宽度", { "每行 ${settings.charsPerLine} 字" }, { d -> settings.charsPerLine += 2 * d; applySettings() }),
            SettingsPanel.Row("段首缩进两字", { onOff(settings.indent) }, { settings.indent = !settings.indent; applySettings() }, isToggle = true),
            SettingsPanel.Row("页眉页脚", { onOff(settings.chrome) }, { settings.chrome = !settings.chrome; applySettings() }, isToggle = true),
            SettingsPanel.Row("专注模式", { onOff(settings.focus) }, { settings.focus = !settings.focus; applySettings() }, isToggle = true),
            SettingsPanel.Row("Vim 模式", { onOff(settings.vim) }, { settings.vim = !settings.vim; applySettings() }, isToggle = true),
            SettingsPanel.Row("自动检查更新", { onOff(settings.autoUpdate) }, { settings.autoUpdate = !settings.autoUpdate }, isToggle = true),
        )
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        val footnote = buildString {
            appendLine("文档目录：${store.dir.absolutePath.removePrefix("/storage/emulated/0/")}")
            appendLine("外部字体：把 .ttf / .otf 放进 ${fonts.folder().absolutePath.removePrefix("/storage/emulated/0/")}")
            appendLine()
            appendLine("墨墨打 $version")
            appendLine("字体：霞鹜文楷屏幕阅读版（SIL Open Font License 1.1）")
            append("拼音数据：Unicode Unihan")
        }
        SettingsPanel(this, font, rows, footnote, onDismiss = ::returnToEditor).show()
    }

    private fun paragraphLabel(r: Float) = when (r) {
        0f -> "无"
        0.25f -> "1/4 行"
        0.5f -> "1/2 行"
        0.75f -> "3/4 行"
        else -> "1 行"
    }

    private fun exportDiagnostics() {
        val desc = with(settings) {
            "字体=${font.ifEmpty { "内置" }} 字号=$textSize 行距=$lineSpacing 段距=$paragraphSpacing 版心=$charsPerLine " +
                "缩进=$indent 页眉页脚=$chrome 专注=$focus vim=$vim 自动更新=$autoUpdate 存储=${if (store.isPublic) "Documents" else "私有目录"}"
        }
        runCatching { Diagnostics.export(this, store.dir, desc) }
            .onSuccess { toast("已导出：${it.absolutePath.removePrefix("/storage/emulated/0/")}") }
            .onFailure { toast("导出失败：${it.message}") }
    }

    // ---------- AI（DESIGN §6） ----------

    private fun promptsFile(): File = File(store.dir, Prompts.FILE_NAME).apply {
        if (!exists()) writeText(Prompts.DEFAULT)
    }

    private fun editPrompts() = open(promptsFile())

    private fun showAiConfig() {
        save()
        AiConfigPanel(this, font, llm, onDismiss = ::returnToEditor).show()
    }

    /** Ctrl+J：选指令 → 对选区（没有选区时是光标所在段落）调用模型 → 结果面板里确认。 */
    private fun showAi() {
        if (!llm.config.configured) {
            toast("先填写 AI 接口地址和模型")
            showAiConfig()
            return
        }
        save()
        val prompts = Prompts.parse(promptsFile().readText())
        if (prompts.isEmpty()) { toast("prompts.md 里没有指令，用“## 名称”开头写一条"); editPrompts(); return }

        val t = editor.text
        val selStart = minOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(0)
        val selEnd = maxOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(0)
        val hasSelection = selEnd > selStart
        // 没有选区时作用于光标所在的段落
        var start = selStart
        var end = selEnd
        if (!hasSelection) {
            while (start > 0 && t[start - 1] != '\n') start--
            while (end < t.length && t[end] != '\n') end++
        }
        val target = t.subSequence(start, end).toString()
        val context = t.subSequence((selEnd - 2000).coerceAtLeast(0), selEnd).toString()

        val items = prompts.map { InkItem(it.name, if (it.insert) "插入" else "替换") }
        InkList.show(this, "AI · ${llm.model}", items, font, onDismiss = ::returnToEditor) { i ->
            val p = prompts[i]
            if (target.isBlank() && !p.insert) { toast("光标所在段落是空的，先选中要处理的文字"); return@show }
            val user = Prompts.render(p, target, context)
            val cfg = llm.config
            handler.post {
                AiResultPanel(
                    this, font, "${p.name} · ${cfg.model}", p.insert,
                    run = { call, onDelta -> Llm.stream(cfg, Prompts.SYSTEM, user, call, onDelta) },
                    onReplace = { result ->
                        if (editor.text.subSequence(start, end).toString() == target) {
                            editor.text.replace(start, end, result)
                            editor.setSelection((start + result.length).coerceAtMost(editor.text.length))
                        } else toast("原文已经变了，没有替换")
                    },
                    onInsert = { result ->
                        val at = end.coerceAtMost(editor.text.length)
                        val insert = if (hasSelection) result else "\n\n$result"
                        editor.text.insert(at, insert)
                        editor.setSelection((at + insert.length).coerceAtMost(editor.text.length))
                    },
                    onDismiss = ::returnToEditor,
                ).show()
            }
        }
    }

    // ---------- GitHub 同步（DESIGN §5） ----------

    private fun makeSync() = GitHubSync(sync, store.dir, File(filesDir, "sync-state.json")) { f -> store.moveToTrash(f) }

    private fun showSyncConfig() {
        save()
        SyncConfigPanel(this, font, sync, ::makeSync, onLogin = ::loginGitHub, onDismiss = ::returnToEditor).show()
    }

    /** 设备授权登录：验证码在手机或电脑上输入；成功后自动选仓库并开始第一次同步。 */
    private fun loginGitHub() {
        if (sync.clientId.isBlank()) {
            toast("还没有配置 GitHub App 的 Client ID，见 docs/M3.md；也可以在同步设置里手动填 token")
            showSyncConfig()
            return
        }
        save()
        val auth = GitHubAuth(sync.clientId, sync.webBase, sync.apiBase)
        DeviceLoginPanel(this, font, auth, onSuccess = { token, repos ->
            sync.token = token
            fun finish(repo: String) {
                sync.repo = repo
                toast("已登录，同步到 $repo")
                syncNow(force = true)
            }
            when {
                repos.isEmpty() -> toast("授权成功，但没有可访问的仓库：请在 GitHub 上把这个 App 安装到你的笔记仓库")
                repos.size == 1 -> finish(repos.single())
                else -> InkList.show(this, "同步到哪个仓库？", repos.map { InkItem(it) }, font, searchHint = null, onDismiss = ::returnToEditor) { i ->
                    finish(repos[i])
                }
            }
        }, onDismiss = ::returnToEditor).show()
    }

    private val syncPrefs by lazy { getSharedPreferences("sync", Context.MODE_PRIVATE) }

    /** 自动同步：离开 App 时（距上次超过 2 分钟）和回到 App 时（距上次超过 10 分钟）。 */
    private fun autoSync(minIntervalMs: Long) {
        if (!sync.auto || !sync.configured) return
        if (System.currentTimeMillis() - syncPrefs.getLong("lastSync", 0) < minIntervalMs) return
        syncNow(force = false)
    }

    private fun syncNow(force: Boolean) {
        if (!sync.configured) { if (force) showSyncConfig(); return }
        if (syncing) return
        syncing = true
        save()
        syncStatus = "同步中"
        refreshChrome()
        val engine = makeSync()
        Thread({
            val r = runCatching { engine.run() }
            handler.post {
                syncing = false
                r.onSuccess { res ->
                    syncPrefs.edit().putLong("lastSync", System.currentTimeMillis()).apply()
                    syncStatus = "已同步 " + SimpleDateFormat("HH:mm", Locale.CHINA).format(Date())
                    if (force || res.conflicts.isNotEmpty() || res.trashed > 0) toast("同步：${res.summary()}")
                    if (res.changedLocal) afterExternalChange()
                }.onFailure {
                    Log.w("momoda", "同步失败", it)
                    syncStatus = "同步失败"
                    if (force) toast("同步失败：${it.message ?: it.javaClass.simpleName}")
                }
                refreshChrome()
            }
        }, "github-sync").apply { isDaemon = true }.start()
    }

    /** 同步改动了本地文件：当前文档被删就换一篇；没有未保存修改就载入新版本。 */
    private fun afterExternalChange() {
        if (!doc.exists()) { dirty = false; loadDoc(store.openLastOrCreate()); return }
        if (!dirty && store.changedOnDisk(doc)) loadDoc(doc)
    }

    // ---------- 更新 ----------

    private fun checkForUpdates(force: Boolean) {
        if (!force && !settings.autoUpdate) return
        updates.check(versionName, force) { release, error ->
            refreshChrome()
            if (force) toast(error ?: release?.let { "有新版本 ${it.version}，在命令面板里选择“安装新版本”" } ?: "已经是最新版本 $versionName")
        }
    }

    private fun installUpdate(release: Updates.Release) {
        save()
        updates.install(release) { status -> toast(status) }
    }

    private fun toggleZen() {
        settings.chrome = !settings.chrome
        applySettings()
    }

    private fun changeTextSize(delta: Float) {
        settings.textSize += delta
        editor.setTextSizeSp(settings.textSize)
    }

    private fun toggleVim() {
        settings.vim = !settings.vim
        applySettings()
        toast(if (vim.enabled) "Vim 模式：开（Esc 进入 normal，光标变成方块）" else "Vim 模式：关")
    }

    private fun toggleFocus() {
        settings.focus = !settings.focus
        applySettings()
    }

    private fun toggleDebug() {
        debug.visibility = if (debug.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        refreshDebug()
    }

    // ---------- 页眉页脚 ----------

    /** 页眉：标题；页脚：字数 · 保存状态。 */
    private fun refreshChrome() {
        headerTitle.text = Titles.titleOf(editor.text) ?: "未命名"
        val words = TextStats.format(TextStats.wordCount(editor.text))
        val where = if (store.isPublic) "" else " · 未授权存储"
        val update = (updates.latest?.let { " · 有新版本 ${it.version}" } ?: "") + (if (syncStatus.isNotEmpty()) " · $syncStatus" else "")
        val state = when {
            saveError != null -> "保存失败，正在重试"
            dirty -> "保存中"
            else -> "已保存"
        }
        footer.text = "$words 字 · $state$where$update"
    }

    /** 页眉右侧：时间，电量低于 20% 时附带电量。按分钟更新。 */
    private fun refreshClock() {
        val time = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date())
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.let {
            val l = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val s = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (l >= 0 && s > 0) l * 100 / s else null
        }
        headerClock.text = if (level != null && level < 20) "电量 $level%　$time" else time
    }

    private fun refreshDebug() {
        if (debug.visibility != View.VISIBLE) return
        val ime = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/') ?: "?"
        val hardKb = resources.configuration.keyboard == Configuration.KEYBOARD_QWERTY
        debug.text = buildString {
            appendLine("输入法  $ime")
            appendLine("实体键盘 ${if (hardKb) "是" else "否"}　vim ${if (vim.enabled) vim.mode else "关"}")
            appendLine("冷启动 ${coldStartMs}ms　字体加载 ${fontLoadMs}ms")
            appendLine("按键→重绘 ${editor.lastLatencyMs}ms（均值 ${"%.0f".format(editor.avgLatencyMs)}ms）")
            appendLine("文件 ${doc.absolutePath}")
            appendLine("──")
            KeyLog.lines.forEach { appendLine(it) }
        }.trimEnd()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
