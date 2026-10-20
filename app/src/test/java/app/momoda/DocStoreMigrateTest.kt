package app.momoda

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DocStoreMigrateTest {

    private val root = Files.createTempDirectory("momoda-migrate").toFile()
    private val from = File(root, "private").apply { mkdirs() }
    private val to = File(root, "public").apply { mkdirs() }

    @After fun cleanup() { root.deleteRecursively() }

    private fun names() = to.list()!!.sorted()

    @Test fun movesIntoEmptyTarget() {
        val src = File(from, "a.md").apply { writeText("# a\n\n正文") }
        val dst = DocStore.moveVerified(src, src.readText(), to)
        assertEquals(File(to, "a.md"), dst)
        assertEquals("# a\n\n正文", dst!!.readText())
        assertFalse(src.exists())
    }

    @Test fun identicalCopyIsNotDuplicated() {
        // 收回权限期间同步把同一篇下载到了私有目录；重新授权后不应多出“a 2.md”
        File(to, "a.md").writeText("# a\n\n正文")
        val src = File(from, "a.md").apply { writeText("# a\n\n正文") }
        val dst = DocStore.moveVerified(src, src.readText(), to)
        assertEquals(File(to, "a.md"), dst)
        assertEquals(listOf("a.md"), names())
        assertFalse(src.exists())
    }

    @Test fun differentContentIsKeptUnderNewName() {
        File(to, "a.md").writeText("# a\n\n公共目录里的版本")
        val src = File(from, "a.md").apply { writeText("# a\n\n私有目录里的版本") }
        val dst = DocStore.moveVerified(src, src.readText(), to)!!
        assertEquals(listOf("a 2.md", "a.md"), names())
        assertEquals("# a\n\n私有目录里的版本", dst.readText())
        assertEquals("# a\n\n公共目录里的版本", File(to, "a.md").readText())
    }
}
