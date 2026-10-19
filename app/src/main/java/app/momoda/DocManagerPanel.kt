package app.momoda

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * 文档管理页（DESIGN §1）：全屏列表，显示标题、日期、字数和开头几行摘要。
 * 也是回收站的入口。为了墨水屏，列表不滚动而是分页：一页放得下多少就显示多少，翻页整页跳。
 *
 * 键盘：↑↓ 选择，Enter 打开（回收站里是恢复），Del 删除（进回收站；回收站里是彻底删除，需要按两次），
 *       R 改标题，S 切换排序，T 切换回收站，PageUp/PageDown 翻页，Esc 关闭。
 * 笔：点行打开；行尾的按钮改标题、删除或恢复；上下滑动翻页。
 */
class DocManagerPanel(
    private val context: Context,
    private val store: DocStore,
    private val typeface: Typeface?,
    private val current: () -> File,
    private val actions: Actions,
) {
    interface Actions {
        fun open(file: File)
        fun retitle(file: File, onDone: () -> Unit)
        /** 当前文档被删掉或恢复后，主界面可能需要换一篇来显示。 */
        fun changed()
        fun dismissed()
    }

    private enum class Sort(val label: String) { MODIFIED("修改时间"), TITLE("标题") }

    private class Entry(val file: File, val title: String, val detail: String, val excerpt: String)

    private val dp = context.resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()
    private val dateFmt = SimpleDateFormat("yyyy-M-d HH:mm", Locale.CHINA)
    private val frame = GradientDrawable().apply { setColor(Color.TRANSPARENT); setStroke(px(2), Color.BLACK) }

    private lateinit var dialog: Dialog
    private lateinit var header: TextView
    private lateinit var listBox: LinearLayout
    private lateinit var pager: TextView

    private var sort = Sort.MODIFIED
    private var inTrash = false
    private var entries = listOf<Entry>()
    private var selected = 0
    private var page = 0
    private var perPage = 8
    private var purgeArmed: File? = null
    private val rowHeight = px(84)

    fun show() {
        dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        header = TextView(context).apply {
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            this.typeface = typeface
            setPadding(px(28), px(24), px(28), px(4))
        }
        val hint = TextView(context).apply {
            text = "Enter 打开　Del 删除　R 改标题　S 排序　T 回收站　Esc 关闭"
            setTextColor(Color.rgb(0x88, 0x88, 0x88))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            this.typeface = typeface
            setPadding(px(28), 0, px(28), px(12))
        }
        val toolbar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(px(20), 0, px(20), px(8))
            addView(button("切换排序") { toggleSort() })
            addView(button("回收站 / 文档") { toggleTrash() })
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(button("关闭") { dialog.dismiss() })
        }
        listBox = object : LinearLayout(context) {
            private var downY = 0f
            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) downY = ev.y
                return ev.actionMasked == MotionEvent.ACTION_MOVE && abs(ev.y - downY) > px(24)
            }
            override fun onTouchEvent(ev: MotionEvent): Boolean {
                if (ev.actionMasked == MotionEvent.ACTION_UP && abs(ev.y - downY) > px(24)) {
                    turnPage(if (ev.y < downY) +1 else -1)
                }
                return true
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), 0, px(20), 0)
        }
        pager = TextView(context).apply {
            setTextColor(Color.rgb(0x77, 0x77, 0x77))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            this.typeface = typeface
            gravity = Gravity.CENTER
            setPadding(0, px(8), 0, px(16))
        }

        val root = object : LinearLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN && onKey(event)) return true
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            isFocusable = true
            isFocusableInTouchMode = true
            addView(header)
            addView(hint)
            addView(toolbar)
            addView(listBox, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(pager)
            addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                if (bottom - top != oldBottom - oldTop) listBox.post { relayoutPages() }
            }
        }
        dialog.setContentView(root)
        dialog.setOnDismissListener { actions.dismissed() }
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.WHITE))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setWindowAnimations(0)
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        }
        dialog.show()
        // 全屏页面也隐藏系统栏，和主界面一致
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            dialog.window?.insetsController?.let {
                it.hide(android.view.WindowInsets.Type.systemBars())
                it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        root.requestFocus()
        reload()
    }

    private fun onKey(e: KeyEvent): Boolean {
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> select(selected + 1)
            KeyEvent.KEYCODE_DPAD_UP -> select(selected - 1)
            KeyEvent.KEYCODE_PAGE_DOWN -> turnPage(+1)
            KeyEvent.KEYCODE_PAGE_UP -> turnPage(-1)
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> activate(selected)
            KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_DEL -> delete(selected)
            KeyEvent.KEYCODE_R -> retitle(selected)
            KeyEvent.KEYCODE_S -> toggleSort()
            KeyEvent.KEYCODE_T -> toggleTrash()
            KeyEvent.KEYCODE_ESCAPE -> dialog.dismiss()
            else -> return false
        }
        return true
    }

    private fun reload() {
        val files = if (inTrash) store.trashList() else store.list()
        val loaded = files.map { f ->
            val text = store.read(f)
            val title = Titles.titleOf(text) ?: f.nameWithoutExtension
            val detail = buildString {
                append(dateFmt.format(Date(f.lastModified())))
                append(" · ").append(TextStats.format(TextStats.wordCount(text))).append(" 字")
                if (!inTrash && f == current()) append(" · 当前")
            }
            Entry(f, title, detail, Titles.excerptOf(text))
        }
        entries = when (sort) {
            Sort.MODIFIED -> loaded
            Sort.TITLE -> loaded.sortedBy { sortKey(it.title) }
        }
        selected = selected.coerceIn(0, (entries.size - 1).coerceAtLeast(0))
        header.text = if (inTrash) "回收站（${entries.size}）" else "管理文档（${entries.size}）　按${sort.label}"
        relayoutPages()
    }

    /** 标题排序：汉字按拼音，其他按原样。 */
    private fun sortKey(title: String) = buildString {
        for (ch in title) append(Pinyin.of(ch) ?: ch.lowercaseChar().toString())
    }

    private fun relayoutPages() {
        val h = listBox.height
        if (h > 0) perPage = (h / rowHeight).coerceAtLeast(1)
        page = (selected / perPage).coerceAtLeast(0)
        render()
    }

    private fun render() {
        listBox.removeAllViews()
        if (entries.isEmpty()) {
            listBox.addView(TextView(context).apply {
                text = if (inTrash) "回收站是空的" else "还没有文档"
                setTextColor(Color.GRAY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                this.typeface = typeface
                setPadding(px(8), px(24), px(8), px(24))
            })
            pager.text = ""
            return
        }
        val pages = (entries.size + perPage - 1) / perPage
        page = page.coerceIn(0, pages - 1)
        val from = page * perPage
        for (i in from until minOf(entries.size, from + perPage)) listBox.addView(row(i), LinearLayout.LayoutParams(-1, rowHeight))
        pager.text = if (pages > 1) "第 ${page + 1} / $pages 页　PageUp / PageDown 或上下滑动翻页" else ""
    }

    private fun row(i: Int): View {
        val e = entries[i]
        val title = TextView(context).apply {
            text = e.title
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            this.typeface = typeface
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val sub = TextView(context).apply {
            text = if (e.excerpt.isEmpty()) e.detail else "${e.detail}　${e.excerpt}"
            setTextColor(Color.rgb(0x77, 0x77, 0x77))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            this.typeface = typeface
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title)
            addView(sub)
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(12), 0, px(8), 0)
            background = if (i == selected) frame else null
            addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            if (inTrash) {
                addView(button("恢复") { activate(i) })
                addView(button(if (purgeArmed == e.file) "确认删除" else "彻底删除") { delete(i) })
            } else {
                addView(button("改标题") { retitle(i) })
                addView(button("删除") { delete(i) })
            }
            setOnClickListener { activate(i) }
        }
    }

    private fun button(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        setTextColor(Color.BLACK)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = this@DocManagerPanel.typeface
        gravity = Gravity.CENTER
        setPadding(px(14), px(10), px(14), px(10))
        background = GradientDrawable().apply { setColor(Color.WHITE); setStroke(1, Color.rgb(0x99, 0x99, 0x99)) }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(8) }
        setOnClickListener { onClick() }
    }

    private fun select(i: Int) {
        if (entries.isEmpty()) return
        selected = i.coerceIn(0, entries.size - 1)
        purgeArmed = null
        val p = selected / perPage
        if (p != page) page = p
        render()
    }

    private fun turnPage(d: Int) {
        if (entries.isEmpty()) return
        val pages = (entries.size + perPage - 1) / perPage
        val np = (page + d).coerceIn(0, pages - 1)
        if (np == page) return
        page = np
        selected = page * perPage
        render()
    }

    private fun toggleSort() {
        sort = if (sort == Sort.MODIFIED) Sort.TITLE else Sort.MODIFIED
        selected = 0
        reload()
    }

    private fun toggleTrash() {
        inTrash = !inTrash
        selected = 0
        purgeArmed = null
        reload()
    }

    private fun activate(i: Int) {
        val e = entries.getOrNull(i) ?: return
        if (inTrash) {
            store.restore(e.file)
            actions.changed()
            reload()
        } else {
            dialog.dismiss()
            actions.open(e.file)
        }
    }

    private fun delete(i: Int) {
        val e = entries.getOrNull(i) ?: return
        if (inTrash) {
            // 彻底删除不可恢复：第一次只是“上膛”，再按一次才删
            if (purgeArmed != e.file) { purgeArmed = e.file; selected = i; render(); return }
            store.purge(e.file)
            purgeArmed = null
        } else {
            store.moveToTrash(e.file)
            actions.changed()
        }
        reload()
    }

    private fun retitle(i: Int) {
        if (inTrash) return
        val e = entries.getOrNull(i) ?: return
        actions.retitle(e.file) { reload() }
    }
}
