package app.momoda

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnownFilesTest {

    private val k = KnownFiles().apply { remember("/a.md", 100, "# 甲\n\n乙") }

    @Test fun unknownFileIsNotChanged() = assertFalse(k.changed("/b.md", true, 1) { "x" })

    @Test fun sameMtimeIsNotChanged() = assertFalse(k.changed("/a.md", true, 100) { error("不该读") })

    @Test fun deletedIsChanged() = assertTrue(k.changed("/a.md", false, 0) { "" })

    @Test fun newMtimeSameContentIsNotChanged() {
        assertFalse(k.changed("/a.md", true, 200) { "# 甲\n\n乙" })
        // 记下新的修改时间，下次不用再读
        assertFalse(k.changed("/a.md", true, 200) { error("不该读") })
    }

    @Test fun newMtimeNewContentIsChanged() = assertTrue(k.changed("/a.md", true, 200) { "# 甲\n\n乙丙" })

    @Test fun unreadableCountsAsChanged() = assertTrue(k.changed("/a.md", true, 200) { throw java.io.IOException() })

    @Test fun renameKeepsRecord() {
        k.move("/a.md", "/c.md", 300)
        assertFalse(k.changed("/c.md", true, 300) { error("不该读") })
        assertFalse(k.changed("/a.md", true, 300) { "x" })
    }
}
