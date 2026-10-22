package app.momoda

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
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
    /** 页脚中间：当前的输入状态（Vim 普通/输入、大写锁定）。 */
    private lateinit var footerMode: TextView
    /** 页脚右侧的占位（“⋯”在这里），让中间的状态居中。提示类文字放在页眉时间左边。 */
    private lateinit var footerNotes: TextView
    /** 页眉右侧的时间（低电量时带电量），由 [refreshClock] 按分钟更新。 */
    private var clockText = ""
    private lateinit var footerBar: LinearLayout
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
    /** 同步的状态：同步中、离线、同步失败；成功后为空，页眉按 [syncedAt] 显示“n 分钟前同步”。 */
    private var syncStatus = ""
    private var syncedAt = 0L
    private val fonts by lazy { Fonts(assets) { store.dir } }
    private val updatesLazy = lazy {
        // debug 包可以用 `--es updateApi <url>` 指向本地模拟的发布地址做测试
        val override = if (BuildConfig.DEBUG) ui.getString("updateApiOverride", null) else null
        Updates(this, override ?: BuildConfig.UPDATE_API)
    }
    private val updates by updatesLazy
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
    /** 实体键盘的大写锁定是否打开（从最近一次按键的状态得知）。 */
    private var capsLockOn = false

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
                intent.getStringExtra("githubSubdir")?.let { d -> subdir = d }
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

        vim = Vim(editor, onModeChange = { refreshChrome() },
            prompt = { title, onDone -> InkList.prompt(this, title, "", font, onDismiss = ::returnToEditor, onDone = onDone) },
            toast = ::toast)
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

        // 先拿焦点再载入文档：编辑器还没排版时获得焦点，系统会把光标挪到文末（ArrowKeyMovementMethod.onTakeFocus），
        // 冷启动就回不到上次的光标位置
        editor.requestFocus()
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
                // 文档列表的标题和字数也在后台先读好，打开 Ctrl+O 和文档管理页时不用在主线程读每篇全文
                handler.postDelayed({
                    val files = store.list()
                    Thread({ store.summaries.warmUp(files) }, "doc-summaries").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
                }, 1000)
                // 检查更新同样推迟，而且每天最多联网一次
                handler.postDelayed({ checkForUpdates(force = false) }, 3000)
                Thread { Diagnostics.recordExitReasons(this@MainActivity) }.start()
                return true
            }
        })

        if (!store.isPublic && !store.askedForAccess) handler.post { askForStorageAccess() }
        runCatching { getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback) }
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
        // 页脚分左中右：左边字数和保存状态（和页眉标题对齐），中间输入状态，右边其他提示，最右留给“⋯”
        footer = chromeText().apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        footerMode = chromeText().apply {
            gravity = Gravity.CENTER
            maxLines = 1
        }
        footerNotes = chromeText().apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.START
        }
        footerBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(28), dp(6), dp(64), dp(14))
            setOnClickListener { showPalette() }
            addView(footer, LinearLayout.LayoutParams(0, -2, 1f))
            addView(footerMode, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(16); marginEnd = dp(16) })
            addView(footerNotes, LinearLayout.LayoutParams(0, -2, 1f))
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
            addView(footerBar, LinearLayout.LayoutParams(-1, -2))
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
        hideSoftKeyboardIfHardware()
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

    override fun onDestroy() {
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) }
        if (updatesLazy.isInitialized()) updates.close()
        super.onDestroy()
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
        if (::vim.isInitialized) vim.afterLoad()
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
            // 保存有意留在主线程、写完并 fsync 才算存好：顺序简单，崩溃或断电都不丢字。
            // 存储很慢时会卡住界面，记下来，看实际设备上是否需要改成后台写
            val t0 = SystemClock.uptimeMillis()
            try {
                // 后台同步写本地文件时也拿这把锁：检查和写入之间不会被同步插进来
                val copy = synchronized(store) {
                    if (store.changedOnDisk(doc)) {
                        // 文档被别的程序改过（例如通过 USB 拷进了新版本）：不覆盖对方，把我们的版本另存一份
                        store.saveConflictCopy(doc, text)
                    } else {
                        store.write(doc, text)
                        null
                    }
                }
                if (copy != null) {
                    toast("《${doc.nameWithoutExtension}》被其他程序修改过，你的版本另存为《${copy.nameWithoutExtension}》")
                    doc = copy
                    store.lastDocName = copy.name
                }
                dirty = false
                if (saveError != null) { saveError = null; refreshChrome() }
                val ms = SystemClock.uptimeMillis() - t0
                if (ms > 1000) {
                    Log.w("momoda", "保存用了 ${ms}ms")
                    Diagnostics.note(this, "slow-save", "保存用了 ${ms}ms（${text.length} 字符）")
                }
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
            val s = if (f == doc) DocSummaries.summarize(editor.text.toString()) else store.summaries.of(f)
            val title = s.title ?: f.nameWithoutExtension
            val detail = buildString {
                append(dateFmt.format(Date(f.lastModified())))
                append(" · ").append(TextStats.format(s.words)).append(" 字")
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
                    loadDoc(docAfterRemoving(doc))
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
        val rationale = Build.VERSION.SDK_INT < 30 && shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val intents = when (DocStore.accessRequest(Build.VERSION.SDK_INT, store.accessDeniedForever, rationale)) {
            DocStore.AccessRequest.PERMISSION_DIALOG -> {
                requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
                return
            }
            DocStore.AccessRequest.ALL_FILES_SETTINGS -> listOf(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")),
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
            )
            DocStore.AccessRequest.APP_SETTINGS -> {
                toast("请在“权限”里打开“存储”")
                listOf(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }
        for (intent in intents) {
            try { startActivity(intent); return } catch (e: Exception) { Log.w("momoda", "无法打开 $intent", e) }
        }
        toast("这台设备打不开授权页面")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // 拒绝且系统不再给解释机会：用户勾了“不再询问”，下次点“授权”要去应用信息页
        // （已经勾过时系统不弹窗、直接返回拒绝，所以这里也要提示，否则点了“授权”毫无反应）
        if (grantResults.isNotEmpty()) {
            store.accessDeniedForever = grantResults[0] != PackageManager.PERMISSION_GRANTED &&
                !shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            if (store.accessDeniedForever) toast("存储权限已被拒绝；再次选择“授权”会打开应用信息页")
        }
        if (store.refreshLocation()) loadDoc(store.openLastOrCreate())
    }

    // ---------- 按键 ----------

    private fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP && consumedDowns.remove(keyCode)) return true
        // 大写锁定的状态跟着每个按键带过来：变了就在页脚标出来，并提示一次（Zen 模式下看不到页脚）
        if (event.isCapsLockOn != capsLockOn) {
            capsLockOn = event.isCapsLockOn
            refreshChrome()
            toast(if (capsLockOn) "大写锁定已打开" else "大写锁定已关闭")
        }

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
            keyCode == KeyEvent.KEYCODE_L && shift -> { toggleMeasuring(); "延迟测量" }
            keyCode == KeyEvent.KEYCODE_K && shift -> { toggleSoftKeyboard(); "软键盘" }
            keyCode == KeyEvent.KEYCODE_SLASH -> { showHelp(); "帮助" }
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
        val meta = (e.metaState and KeyEvent.META_META_MASK.inv()) or
            KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        return KeyEvent(e.downTime, e.eventTime, e.action, e.keyCode, e.repeatCount, meta)
    }

    // ---------- 命令面板 ----------

    private class Command(val id: String, val label: String, val shortcut: String = "", val run: () -> Unit) {
        var group = ""
    }

    /** 命令面板里的命令，按分组固定排列（见 [PaletteLayout]）。 */
    private fun commands(): List<Command> = buildList {
        var group = ""
        fun add(c: Command) { c.group = group; this.add(c) }
        fun state(on: Boolean) = if (on) "开" else "关"
        group = "文档"
        add(Command("new", "新建文档", "Ctrl+N", ::newDoc))
        add(Command("switch", "打开文档", "Ctrl+O", ::showSwitcher))
        add(Command("manage", "管理文档", "回收站、排序", ::showManager))
        add(Command("trash", "删除当前文档", "进回收站", ::deleteDoc))
        group = "写作"
        add(Command("zen", "Zen 模式", "Ctrl+. · ${state(!settings.chrome)}", ::toggleZen))
        add(Command("focus", "专注模式", "Ctrl+Shift+F · ${state(settings.focus)}", ::toggleFocus))
        add(Command("vim", "Vim 模式", "Ctrl+Shift+V · ${state(settings.vim)}", ::toggleVim))
        add(Command("ai", "AI 助手", "Ctrl+J", ::showAi))
        group = "同步"
        add(Command("sync", "立即同步", "Ctrl+Shift+S", { syncNow(force = true) }))
        add(Command("sync-settings", "同步设置", if (sync.configured) sync.repo.substringAfter('/') else "GitHub 登录", ::showSyncConfig))
        add(Command("paste", "从其他设备粘贴", "手机或电脑上填写", ::showPaste))
        group = "其他"
        add(Command("settings", "设置", "Ctrl+,", ::showSettings))
        add(Command("capslock", "CapsLock 映射", "改成 Ctrl", ::showCapsLockGuide))
        add(Command("help", "帮助", "Ctrl+/ · 快捷键、诊断", ::showHelp))
        if (!store.isPublic) add(Command("access", "授权存储", "存到 Documents/momoda", ::requestStorageAccess))
        updates.latest?.let { r -> add(Command("install-update", "安装 ${r.version}", "从 GitHub 下载") { installUpdate(r) }) }
        // 软键盘（Ctrl+Shift+K）、调试面板（Ctrl+Shift+D）、延迟测量（Ctrl+Shift+L）只留快捷键，见帮助
    }

    /** 命令面板：最近用过的排在前面（DESIGN §3.2）。 */
    private fun showPalette() {
        val cmds = commands()
        val byId = cmds.associateBy { it.id }
        val groups = cmds.groupBy { it.group }.map { (g, list) -> g to list.map { it.id } }
        val uses = PaletteLayout.parseUses(ui.getString("commandUses", "")!!)
        fun item(id: String) = byId.getValue(id).let { CommandGrid.Item(it.id, it.label, it.shortcut) }
        val all = PaletteLayout.columns(groups, uses, neverFrequent = setOf("trash"))
        val frequent = all.firstOrNull { it.first == PaletteLayout.FREQUENT }?.second.orEmpty().map(::item)
        val columns = all.filter { it.first != PaletteLayout.FREQUENT }.map { (title, ids) -> title to ids.map(::item) }
        CommandGrid.show(this, columns, frequent, font, onDismiss = ::returnToEditor) { item ->
            ui.edit().putString("commandUses", PaletteLayout.formatUses(uses + (item.id to (uses[item.id] ?: 0) + 1))).apply()
            byId.getValue(item.id).run()
        }
    }

    /** 有些墨水屏厂商的定制设置里藏掉了这些页面，直接拉起系统原生设置。 */
    private fun showHelp() {
        save()
        HelpPanel(this, font, versionName, onExportDiagnostics = ::exportDiagnostics, onImeSettings = ::openImeSettings,
            onDismiss = ::returnToEditor).show()
    }

    private fun showCapsLockGuide() {
        CapsLockGuide(this, font, onOpenSystem = {
            openSystemSettings(Settings.ACTION_HARD_KEYBOARD_SETTINGS, "Settings\$PhysicalKeyboardActivity")
        }, onDismiss = ::returnToEditor).show()
    }

    /** 打开当前输入法自己的设置页（fcitx5 的候选窗大小、预编辑等都在那里）；找不到就打开系统的输入法设置。 */
    private fun openImeSettings() {
        val imm = getSystemService(InputMethodManager::class.java)
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val info = imm.enabledInputMethodList.firstOrNull { it.id == current }
        val activity = info?.settingsActivity
        val intent = if (info != null && !activity.isNullOrEmpty()) {
            Intent(Intent.ACTION_MAIN).setClassName(info.packageName, activity)
        } else {
            Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
        }
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.w("momoda", "无法打开输入法设置", e)
            toast("打不开输入法设置")
        }
    }

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
        hideSoftKeyboardIfHardware()
    }

    /** 接着实体键盘：用不到软键盘。系统有时在重新连接输入法时把它叫出来，这里收起。 */
    private fun hasHardKeyboard(): Boolean = resources.configuration.let {
        it.keyboard == android.content.res.Configuration.KEYBOARD_QWERTY &&
            it.hardKeyboardHidden == android.content.res.Configuration.HARDKEYBOARDHIDDEN_NO
    }

    private fun hideSoftKeyboardIfHardware() {
        if (hasHardKeyboard()) editor.post {
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(editor.windowToken, 0)
        }
    }

    private var softKeyboardShown = false

    /** Ctrl+Shift+K：显示或收起软键盘（实体键盘上没有别的办法收起它）。 */
    private fun toggleSoftKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java)
        // Android 11 起可以直接问系统软键盘在不在；更早的系统按上次自己切换的结果来
        val shown = if (Build.VERSION.SDK_INT >= 30) editor.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) ?: false
        else softKeyboardShown
        softKeyboardShown = !shown
        if (shown) imm.hideSoftInputFromWindow(editor.windowToken, 0)
        else { editor.requestFocus(); imm.showSoftInput(editor, InputMethodManager.SHOW_FORCED) }
    }

    private fun deleteDoc() {
        // 先保存：回收站里应当是删除时屏幕上的样子，包括最后 800ms 内打的字
        save()
        if (dirty) { toast("保存失败，暂不删除"); return }
        val victim = doc
        val title = Titles.titleOf(editor.text) ?: victim.nameWithoutExtension
        store.moveToTrash(victim)
        loadDoc(docAfterRemoving(victim))
        toast("已移到回收站：$title")
    }

    /** 当前文档被删掉之后打开哪篇：删除前看的上一篇；没有的话最近修改的正文（不是 prompts.md）。 */
    private fun docAfterRemoving(removed: File): File =
        store.previousDocName?.let { File(store.dir, it) }?.takeIf { it.exists() && it != removed }
            ?: store.mostRecent() ?: store.create()

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
        editor.scrollMode = settings.scrollMode
        editor.cornerQuotes = settings.cornerQuotes
        editor.autoSpace = settings.autoSpace
        if (vim.enabled != settings.vim) vim.enabled = settings.vim
        val v = if (settings.chrome) View.VISIBLE else View.GONE
        header.visibility = v
        footerBar.visibility = v
    }

    /** 全局统一一种字体：正文、页眉页脚、弹窗（DESIGN §2.8）。 */
    private fun applyFont(tf: Typeface) {
        font = tf
        editor.setFont(tf)
        headerTitle.typeface = tf
        headerClock.typeface = tf
        footer.typeface = tf
        footerMode.typeface = tf
        footerNotes.typeface = tf
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
            SettingsPanel.Row("字号", { "%.0f".format(settings.textSize) }, { d -> settings.textSize += d; applySettings() }, hint = "Ctrl+=  Ctrl+-"),
            SettingsPanel.Row("行距", { "%.2f".format(settings.lineSpacing).trimEnd('0').trimEnd('.') }, { d ->
                settings.lineSpacing = step(UserSettings.LINE_SPACINGS, settings.lineSpacing, d); applySettings()
            }),
            SettingsPanel.Row("段距", { paragraphLabel(settings.paragraphSpacing) }, { d ->
                settings.paragraphSpacing = step(UserSettings.PARAGRAPH_SPACINGS, settings.paragraphSpacing, d); applySettings()
            }),
            SettingsPanel.Row("版心宽度", { "每行 ${settings.charsPerLine} 字" }, { d -> settings.charsPerLine += 2 * d; applySettings() }),
            SettingsPanel.Row("翻页方式", { settings.scrollMode.label }, { d ->
                settings.scrollMode = step(EditorView.ScrollMode.entries, settings.scrollMode, d); applySettings()
            }),
            SettingsPanel.Row("方括号键输入「」", { onOff(settings.cornerQuotes) }, { settings.cornerQuotes = !settings.cornerQuotes; applySettings() }, isToggle = true),
            SettingsPanel.Row("中英文间加空格", { onOff(settings.autoSpace) }, { settings.autoSpace = !settings.autoSpace; applySettings() }, isToggle = true),
            SettingsPanel.Row("段首缩进两字", { onOff(settings.indent) }, { settings.indent = !settings.indent; applySettings() }, isToggle = true),
            SettingsPanel.Row("Zen 模式", { onOff(!settings.chrome) }, { settings.chrome = !settings.chrome; applySettings() }, isToggle = true, hint = "隐藏页眉页脚 · Ctrl+."),
            SettingsPanel.Row("专注模式", { onOff(settings.focus) }, { settings.focus = !settings.focus; applySettings() }, isToggle = true, hint = "只突出当前段落 · Ctrl+Shift+F"),
            SettingsPanel.Row("Vim 模式", { onOff(settings.vim) }, { settings.vim = !settings.vim; applySettings() }, isToggle = true, hint = "Ctrl+Shift+V"),
            SettingsPanel.Row("自动检查更新", { onOff(settings.autoUpdate) }, { settings.autoUpdate = !settings.autoUpdate }, isToggle = true),
            SettingsPanel.Row("检查更新", { updates.latest?.let { "安装 ${it.version}" } ?: "当前 $versionName" }, {
                val r = updates.latest
                if (r != null) installUpdate(r) else checkForUpdates(force = true)
            }, isToggle = true, hint = "按 Enter 或点这里"),
        )
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        val footnote = buildString {
            // 字体（OFL）和拼音数据（Unicode）的许可文件随 App 打包（assets/fonts/OFL.txt、assets/licenses），这里不再逐条列出
            appendLine("文档目录：${store.dir.absolutePath.removePrefix("/storage/emulated/0/")}")
            appendLine("外部字体：把 .ttf / .otf 放进 ${fonts.folder().absolutePath.removePrefix("/storage/emulated/0/")}")
            append("墨墨打 $version · GPL-3.0 开源 · momoda.zhixian.io")
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
        AiConfigPanel(this, font, llm, onPaste = ::showPaste, onEditPrompts = ::editPrompts, onDismiss = ::returnToEditor).show()
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

        // 指令列表最后是“AI 设置…”“编辑指令…”：AI 只有这一个入口
        val items = prompts.map { InkItem(it.name, if (it.insert) "插入" else "替换") } +
            InkItem("AI 设置…", llm.model) + InkItem("编辑指令…", Prompts.FILE_NAME)
        InkList.show(this, "AI · ${llm.model}", items, font, onDismiss = ::returnToEditor) { i ->
            if (i == prompts.size) { handler.post { showAiConfig() }; return@show }
            if (i == prompts.size + 1) { editPrompts(); return@show }
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

    // ---------- 从其他设备粘贴 ----------

    private fun showPaste() {
        save()
        PastePanel(this, font, onReceived = { f ->
            val applied = mutableListOf<String>()
            f["ai_url"]?.let { llm.baseUrl = it; applied += "AI 接口地址" }
            f["ai_key"]?.let { llm.apiKey = it; applied += "AI 密钥" }
            f["ai_model"]?.let { llm.model = it; applied += "AI 模型" }
            f["gh_repo"]?.let { sync.repo = it; applied += "GitHub 仓库" }
            f["gh_token"]?.let { sync.token = it; applied += "GitHub token" }
            f["text"]?.let {
                val text = it.replace("\r\n", "\n")
                val at = editor.selectionEnd.coerceIn(0, editor.text.length)
                editor.text.insert(at, text)
                editor.setSelection((at + text.length).coerceAtMost(editor.text.length))
                applied += "正文 ${TextStats.format(TextStats.wordCount(text))} 字"
            }
            toast("已收到：${applied.joinToString("、")}")
            returnToEditor()
        }, onDismiss = ::returnToEditor).show()
    }

    // ---------- GitHub 同步（DESIGN §5） ----------

    private fun makeSync() = GitHubSync(sync.target(), store.dir, File(filesDir, "sync-state.json"), moveToTrash = { f -> store.moveToTrash(f) }, lock = store)

    private fun showSyncConfig() {
        save()
        SyncConfigPanel(this, font, sync, ::makeSync, onLogin = ::loginGitHub, pickRepo = ::pickRepo,
            manageUrl = installUrl(), onPaste = ::showPaste, onDismiss = ::returnToEditor).show()
    }

    private fun installUrl() = "${sync.webBase.trimEnd('/')}/apps/${BuildConfig.GITHUB_APP_SLUG}/installations/new"

    /** 列出当前 token 能访问的仓库（GitHub App 安装到的仓库）供选择。 */
    private fun pickRepo(onPicked: (String) -> Unit) {
        val token = sync.token
        if (token.isBlank()) { toast("先用 GitHub 登录"); return }
        val auth = GitHubAuth(sync.clientId, sync.webBase, sync.apiBase)
        Thread({
            val r = runCatching { auth.repositories(token) }
            handler.post {
                r.onSuccess { repos ->
                    // 最后一项去 GitHub 上让墨墨打访问更多仓库，加好后回到这个列表（不用重新登录）
                    fun show(list: List<String>) {
                        val items = list.map { InkItem(it, if (it == sync.repo) "当前" else "") } +
                            InkItem("让墨墨打访问其他仓库…", "在手机或电脑上添加")
                        InkList.show(this, "选择仓库（${list.size}）", items, font, nested = true) { i ->
                            if (i < list.size) onPicked(list[i])
                            else InstallGuidePanel(this, font, installUrl(),
                                check = { auth.repositories(token).takeIf { it.size > list.size }.orEmpty() },
                                onRepos = ::show, onDismiss = {}, adding = list.isNotEmpty(), nested = true).show()
                        }
                    }
                    show(repos)
                }.onFailure { toast("读取仓库列表失败：${it.message}") }
            }
        }, "list-repos").apply { isDaemon = true }.start()
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
                toast("已登录，同步到 $repo（可在“GitHub 同步设置”里换仓库或指定仓库内目录）")
                syncNow(force = true)
            }
            // 只有一个仓库也让用户看一眼再确认，并告诉他还可以加别的仓库
            fun choose(list: List<String>) {
                val items = list.map { InkItem(it) } + InkItem("让墨墨打访问其他仓库…", "在手机或电脑上添加")
                InkList.show(this, "同步到哪个仓库？", items, font, searchHint = null, onDismiss = ::returnToEditor) { i ->
                    if (i < list.size) finish(list[i])
                    else InstallGuidePanel(this, font, installUrl(),
                        // 仓库变多了才继续：已经能访问的那几个不算
                        check = { auth.repositories(token).takeIf { it.size > list.size }.orEmpty() },
                        onRepos = ::choose, onDismiss = ::returnToEditor, adding = true).show()
                }
            }
            if (repos.isNotEmpty()) choose(repos)
            else {
                // 还没把 App 装到任何仓库：显示安装地址，装好后自动继续
                InstallGuidePanel(this, font, installUrl(), check = { auth.repositories(token) }, onRepos = ::choose, onDismiss = ::returnToEditor).show()
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
        if (!isOnline()) {
            // 没网时不去试：显示“离线”，联网后 [networkCallback] 会自动再同步
            syncStatus = "离线"
            refreshChrome()
            if (force) toast("没有网络，联网后会自动同步")
            return
        }
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
                    syncStatus = ""
                    syncedAt = System.currentTimeMillis()
                    if (force || res.conflicts.isNotEmpty() || res.trashed > 0 || res.skipped.isNotEmpty()) toast("同步：${res.summary()}")
                    if (res.changedLocal) afterExternalChange()
                }.onFailure {
                    if (it is GitHubSync.Busy) {
                        // Activity 重建前开始的同步还在跑：它结束后会照常记录，这里什么都不改
                        if (force) toast(it.message!!)
                        return@onFailure
                    }
                    Log.w("momoda", "同步失败", it)
                    // 连不上服务器多半是网络问题（Wi-Fi 断了、DNS 失败、超时），显示“离线”，联网后自动重试
                    val offline = !isOnline() || it is java.net.UnknownHostException || it is java.net.ConnectException ||
                        it is java.net.SocketTimeoutException || it is java.net.NoRouteToHostException
                    syncStatus = if (offline) "离线" else "同步失败"
                    if (force) toast(if (offline) "连不上网络，联网后会自动同步" else "同步失败：${it.message ?: it.javaClass.simpleName}")
                }
                refreshChrome()
            }
        }, "github-sync").apply { isDaemon = true }.start()
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** 联网后：上次因为离线没同步成，就补一次。 */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handler.post { if (syncStatus == "离线") autoSync(minIntervalMs = 0) }
        }
    }

    /** 同步改动了本地文件：当前文档被删就换一篇；没有未保存修改就载入新版本。 */
    private fun afterExternalChange() {
        // 还有没保存的字：先保存。文件被同步改过或移走时，save() 会把编辑器里的版本另存一份
        if (dirty) save()
        if (dirty) return // 保存失败：编辑器里的内容是唯一的一份，留着它，save() 会自动重试
        if (!doc.exists()) { loadDoc(store.openLastOrCreate()); return }
        if (store.changedOnDisk(doc)) loadDoc(doc)
    }

    // ---------- 更新 ----------

    private fun checkForUpdates(force: Boolean) {
        if (!force && !settings.autoUpdate) return
        updates.check(versionName, force) { release, error ->
            refreshChrome()
            if (force) toast(error ?: release?.let { "有新版本 ${it.version}，在设置或命令面板里安装" } ?: "已经是最新版本 $versionName")
        }
    }

    private fun installUpdate(release: Updates.Release) {
        save()
        updates.install(release) { status -> toast(status) }
    }

    private fun toggleZen() {
        settings.chrome = !settings.chrome
        applySettings()
        toast(if (settings.chrome) "已退出 Zen 模式" else "Zen 模式：隐藏页眉页脚，Ctrl+. 退出")
    }

    private fun changeTextSize(delta: Float) {
        settings.textSize += delta
        editor.setTextSizeSp(settings.textSize)
    }

    private fun toggleVim() {
        settings.vim = !settings.vim
        applySettings()
        toast(if (vim.enabled) "Vim 模式：开（Esc 进入普通模式，光标变成方块）" else "Vim 模式：关")
    }

    private fun toggleFocus() {
        settings.focus = !settings.focus
        applySettings()
        toast(if (settings.focus) "专注模式：只有光标所在的段落是黑色，Ctrl+Shift+F 关闭" else "已关闭专注模式")
    }

    /**
     * 延迟测量：每个按键记一行日志（tag momoda-latency）：系统收到 → 交给墨墨打 → 文字或光标变化 → 画完 → 这一帧交给屏幕。
     * 键盘本身（蓝牙、有线）和墨水屏刷新的时间在这之外，软件量不到。
     */
    private val frameListener = android.view.Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
        val t = editor.takeTiming() ?: return@OnFrameMetricsAvailableListener
        val vsync = metrics.getMetric(android.view.FrameMetrics.INTENDED_VSYNC_TIMESTAMP) / 1_000_000
        val done = vsync + metrics.getMetric(android.view.FrameMetrics.TOTAL_DURATION) / 1_000_000
        val change = if (t.change > 0) t.change - t.arrive else -1
        Log.i("momoda-latency", "key=${t.key} 系统→App=${t.arrive - t.event} App→变化=$change 变化→画完=${t.draw - maxOf(t.change, t.arrive)} " +
            "画完→交屏=${done - t.draw} 合计=${done - t.event}")
    }

    private fun toggleMeasuring() {
        editor.measuring = !editor.measuring
        if (editor.measuring) window.addOnFrameMetricsAvailableListener(frameListener, handler)
        else runCatching { window.removeOnFrameMetricsAvailableListener(frameListener) }
        toast(if (editor.measuring) "延迟测量：开（结果在日志 momoda-latency）" else "延迟测量：关")
    }

    private fun toggleDebug() {
        debug.visibility = if (debug.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        refreshDebug()
    }

    // ---------- 页眉页脚 ----------

    /** 页眉：标题；页脚：左边字数 · 保存状态，中间 Vim 模式和大写锁定，右边同步、更新、存储授权。 */
    private fun refreshChrome() {
        headerTitle.text = Titles.titleOf(editor.text) ?: "未命名"
        val words = TextStats.format(TextStats.wordCount(editor.text))
        val state = when {
            saveError != null -> "保存失败，正在重试"
            dirty -> "保存中"
            else -> "已保存"
        }
        footer.text = "$words 字 · $state"
        val vimMode = if (::vim.isInitialized && vim.enabled) {
            when (vim.mode) {
                Vim.Mode.NORMAL -> "Vim · 普通"
                Vim.Mode.INSERT -> "Vim · 插入"
                Vim.Mode.VISUAL -> "Vim · 可视"
                Vim.Mode.VISUAL_LINE -> "Vim · 可视行"
            }
        } else null
        footerMode.text = listOfNotNull(vimMode, if (capsLockOn) "大写锁定" else null).joinToString("　")
        refreshHeaderRight()
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
        clockText = if (level != null && level < 20) "电量 $level%　$time" else time
        refreshHeaderRight()
    }

    /** 页眉右侧：同步、更新、存储授权这类提示，然后是时间。右下角已经有“⋯”，不在那里放文字。 */
    private fun refreshHeaderRight() {
        val update = updates.latest?.let { "有新版本 ${it.version}" }
        val parts = listOfNotNull(
            syncStatus.ifEmpty { syncedLabel() },
            update,
            if (store.isPublic) null else "未授权存储",
            clockText.ifEmpty { null },
        )
        // 有新版本时用黑字，在浅灰的页眉里一眼能看到；其余照常浅灰
        val text = android.text.SpannableStringBuilder()
        parts.forEachIndexed { i, part ->
            if (i > 0) text.append(" · ")
            val from = text.length
            text.append(part)
            if (part == update) text.setSpan(android.text.style.ForegroundColorSpan(Color.BLACK), from, text.length, 0)
        }
        headerClock.text = text
    }

    /** 上次同步成功是多久以前（不写具体时刻，免得和页眉的时间混在一起）。按分钟随时钟刷新。 */
    private fun syncedLabel(): String? {
        val at = syncedAt.takeIf { it > 0 } ?: syncPrefs.getLong("lastSync", 0)
        if (at == 0L) return null
        val minutes = (System.currentTimeMillis() - at) / 60_000
        return when {
            minutes < 1 -> "刚刚同步"
            minutes < 60 -> "$minutes 分钟前同步"
            minutes < 24 * 60 -> "${minutes / 60} 小时前同步"
            else -> "${minutes / (24 * 60)} 天前同步"
        }
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
