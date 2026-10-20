package app.momoda

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietEditableTest {

    private val layout = "android.text.DynamicLayout\$ChangeWatcher"

    @Test fun shiftsDuringTextChangeAreHiddenFromLayout() {
        // 打字时插入点之后的样式跟着平移：不让 DynamicLayout 逐段重排
        assertTrue(QuietEditable.hidesFromLayout(layout, replacing = 1, explicit = 0))
        assertTrue(QuietEditable.hidesFromLayout(layout, replacing = 2, explicit = 0))
    }

    @Test fun ourOwnStylingStillReachesLayout() {
        // 渲染 Markdown 时加上的标题、粗体要重排（哪怕是在文字变化的回调里加的）
        assertFalse(QuietEditable.hidesFromLayout(layout, replacing = 1, explicit = 1))
        assertFalse(QuietEditable.hidesFromLayout(layout, replacing = 0, explicit = 1))
        assertFalse(QuietEditable.hidesFromLayout(layout, replacing = 0, explicit = 0))
    }

    @Test fun otherWatchersAlwaysNotified() {
        assertFalse(QuietEditable.hidesFromLayout("android.widget.TextView\$ChangeWatcher", replacing = 1, explicit = 0))
    }
}
