package app.momoda

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DocSummariesTest {

    private val dir = Files.createTempDirectory("momoda-summaries").toFile()

    @After fun cleanup() { dir.deleteRecursively() }

    @Test fun summaryIsCachedUntilFileChanges() {
        val f = File(dir, "a.md").apply { writeText("# 标题\n\n正文五个字"); setLastModified(1_000_000) }
        val summaries = DocSummaries()
        assertEquals("标题", summaries.of(f).title)
        assertEquals(7, summaries.of(f).words)

        // 修改时间和大小都没变：用缓存，不再读文件
        f.writeText("# 别的\n\n正文五个字"); f.setLastModified(1_000_000)
        assertEquals("标题", summaries.of(f).title)

        // 修改时间变了：重新读
        f.setLastModified(2_000_000)
        assertEquals("别的", summaries.of(f).title)
        assertEquals("正文五个字", summaries.of(f).excerpt)
    }

    @Test fun missingFileIsEmpty() {
        val s = DocSummaries().of(File(dir, "gone.md"))
        assertEquals(null, s.title)
        assertEquals(0, s.words)
    }
}
