package app.momoda

import android.text.SpanWatcher
import android.text.Spannable
import org.junit.Assert.assertEquals
import org.junit.Test

class QuietEditableTest {

    private class Recorder : SpanWatcher {
        val calls = mutableListOf<String>()
        override fun onSpanAdded(text: Spannable, what: Any, start: Int, end: Int) { calls += "added" }
        override fun onSpanRemoved(text: Spannable, what: Any, start: Int, end: Int) { calls += "removed" }
        override fun onSpanChanged(text: Spannable, what: Any, ostart: Int, oend: Int, nstart: Int, nend: Int) { calls += "changed" }
    }

    private val text = SpanText("")
    private val markdown = HeadingSpan(1.2f)
    private val selection = Any() // 选区、输入法拼写区之类

    @Test fun markdownShiftsAreDropped() {
        // 打字时插入点之后的标题、引号跟着平移：不通知，否则长文档里每个字要处理几千次
        val r = Recorder()
        QuietEditable.ShiftFilter(r).onSpanChanged(text, markdown, 10, 12, 11, 13)
        assertEquals(emptyList<String>(), r.calls)
    }

    @Test fun otherSpansStillReported() {
        // 光标和输入法的拼写区移动必须照常通知，否则输入法和光标会错位
        val r = Recorder()
        QuietEditable.ShiftFilter(r).onSpanChanged(text, selection, 10, 10, 11, 11)
        assertEquals(listOf("changed"), r.calls)
    }

    @Test fun addAndRemoveAlwaysReported() {
        // 渲染 Markdown 时删掉重加的样式要重排
        val r = Recorder()
        val f = QuietEditable.ShiftFilter(r)
        f.onSpanAdded(text, markdown, 0, 3)
        f.onSpanRemoved(text, markdown, 0, 3)
        assertEquals(listOf("added", "removed"), r.calls)
    }
}
