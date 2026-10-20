package app.momoda

import android.text.Editable
import android.text.SpanWatcher
import android.text.SpannableStringBuilder

/**
 * 编辑器用的 Editable。
 *
 * SpannableStringBuilder 每次插入或删除文字后，会给插入点之后的**每一个**样式发一次“位置变了”；
 * DynamicLayout 收到影响排版的样式（标题、粗体、斜体、全角引号）就把它所在的段落重排一遍。
 * 在长文档的前部打一个字，等于把后面全文重排一遍：10 万字在模拟器上每个字要 5 秒，汉王上更慢。
 *
 * 这些样式只是跟着文字平移，排版没有变；被改动的那一段，DynamicLayout 已经按文字变化重排过了。
 * 所以文字变化引起的样式平移不再通知 DynamicLayout；我们自己调用 setSpan / removeSpan（渲染 Markdown）照常通知。
 */
class QuietEditable(source: CharSequence) : SpannableStringBuilder(source) {

    /** 正在执行的 replace 层数（分段规则会在文字变化的回调里再改一次文字）。 */
    private var replacing = 0
    /** 正在执行的 setSpan / removeSpan 层数。 */
    private var explicit = 0

    override fun replace(start: Int, end: Int, tb: CharSequence, tbstart: Int, tbend: Int): SpannableStringBuilder {
        replacing++
        try {
            return super.replace(start, end, tb, tbstart, tbend)
        } finally {
            replacing--
        }
    }

    override fun setSpan(what: Any?, start: Int, end: Int, flags: Int) {
        explicit++
        try {
            super.setSpan(what, start, end, flags)
        } finally {
            explicit--
        }
    }

    override fun removeSpan(what: Any?) {
        explicit++
        try {
            super.removeSpan(what)
        } finally {
            explicit--
        }
    }

    // SpannableStringBuilder 通知样式变化前用这个方法找 SpanWatcher
    override fun <T : Any?> getSpans(queryStart: Int, queryEnd: Int, kind: Class<T>?): Array<T> {
        val spans = super.getSpans(queryStart, queryEnd, kind)
        if (kind != SpanWatcher::class.java || spans.none { hidesFromLayout(it!!.javaClass.name, replacing, explicit) }) return spans
        val kept = spans.filterNot { hidesFromLayout(it!!.javaClass.name, replacing, explicit) }
        @Suppress("UNCHECKED_CAST")
        val out = java.lang.reflect.Array.newInstance(kind, kept.size) as Array<T>
        kept.forEachIndexed { i, s -> out[i] = s }
        return out
    }

    object Factory : Editable.Factory() {
        override fun newEditable(source: CharSequence): Editable = QuietEditable(source)
    }

    companion object {
        private const val LAYOUT_WATCHER = "android.text.DynamicLayout\$ChangeWatcher"

        /** 文字变化过程中（不在我们自己的 setSpan / removeSpan 里）的样式通知，不发给 DynamicLayout。 */
        fun hidesFromLayout(watcherClass: String, replacing: Int, explicit: Int): Boolean =
            watcherClass == LAYOUT_WATCHER && replacing > 0 && explicit == 0
    }
}
