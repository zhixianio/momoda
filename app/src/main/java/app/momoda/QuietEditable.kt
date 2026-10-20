package app.momoda

import android.text.Editable
import android.text.SpanWatcher
import android.text.Spannable
import android.text.SpannableStringBuilder

/**
 * 编辑器用的 Editable。
 *
 * SpannableStringBuilder 每次插入或删除文字后，会给插入点之后的**每一个**样式发一次“位置变了”。
 * DynamicLayout 收到影响排版的样式（标题、粗体、斜体、全角引号）就把它所在的段落重排一遍，
 * TextView 收到每一个都要把相关的显示块作废一遍。在长文档的前部打一个字，等于把后面全文处理一遍：
 * 10 万字在模拟器上每个字要 5 秒，满是中文引号时也要 1.5 秒，汉王上更慢。
 *
 * 这些 Markdown 样式只是跟着文字平移，显示不会变；被改动的那几行，DynamicLayout 已经按文字变化重排过，
 * 随后 [MarkdownStyler] 也会把它们的样式删掉重加（增删照常通知）。
 * 所以文字变化过程中，Markdown 样式的“位置变了”不再转发；选区、输入法拼写区等其他样式照常转发。
 */
class QuietEditable(source: CharSequence) : SpannableStringBuilder(source) {

    /** 正在执行的 replace 层数（分段规则会在文字变化的回调里再改一次文字）。 */
    private var replacing = 0
    /** 正在执行的 setSpan / removeSpan 层数：我们自己移动样式时照常通知。 */
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

    // 上一次换上的过滤器：打一个字要查几千次 SpanWatcher，每次都是同样几个，不必每次新建
    private var lastWatchers: Array<*> = emptyArray<Any>()
    private var lastFiltered: Array<*> = emptyArray<Any>()

    // SpannableStringBuilder 通知样式变化前用这个方法找 SpanWatcher
    override fun <T : Any?> getSpans(queryStart: Int, queryEnd: Int, kind: Class<T>?): Array<T> {
        val spans = super.getSpans(queryStart, queryEnd, kind)
        if (kind != SpanWatcher::class.java || replacing == 0 || explicit > 0 || spans.isEmpty()) return spans
        if (!(spans contentEquals lastWatchers)) {
            lastWatchers = spans.copyOf()
            lastFiltered = Array(spans.size) { ShiftFilter(spans[it] as SpanWatcher) }
        }
        @Suppress("UNCHECKED_CAST")
        for (i in spans.indices) spans[i] = lastFiltered[i] as T
        return spans
    }

    /** 转发给 [target]，但不转发 Markdown 样式的“位置变了”。 */
    class ShiftFilter(private val target: SpanWatcher) : SpanWatcher {
        override fun onSpanAdded(text: Spannable, what: Any, start: Int, end: Int) = target.onSpanAdded(text, what, start, end)

        override fun onSpanRemoved(text: Spannable, what: Any, start: Int, end: Int) = target.onSpanRemoved(text, what, start, end)

        override fun onSpanChanged(text: Spannable, what: Any, ostart: Int, oend: Int, nstart: Int, nend: Int) {
            if (what !is MdSpan) target.onSpanChanged(text, what, ostart, oend, nstart, nend)
        }
    }

    object Factory : Editable.Factory() {
        override fun newEditable(source: CharSequence): Editable = QuietEditable(source)
    }
}
