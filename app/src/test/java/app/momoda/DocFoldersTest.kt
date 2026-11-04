package app.momoda

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DocFoldersTest {

    private val keys = listOf("a.md", "notes/1104/会议.md", "notes/1104/待办.md", "notes/1103/x.md", "notes/总结.md", "日记/1.md")
    private val time = mapOf("a.md" to 1L, "notes/1104/会议.md" to 50L, "notes/1104/待办.md" to 40L,
        "notes/1103/x.md" to 30L, "notes/总结.md" to 20L, "日记/1.md" to 60L)

    @Test fun rootChildrenCountDeepDocs() {
        val subs = DocFolders.children(keys, "") { time.getValue(it) }
        assertEquals(listOf("日记", "notes"), subs.map { it.name })
        assertEquals(listOf(1, 4), subs.map { it.docs })
    }

    @Test fun nestedChildren() =
        assertEquals(listOf("notes/1104", "notes/1103"), DocFolders.children(keys, "notes") { time.getValue(it) }.map { it.path })

    @Test fun leafHasNoChildren() = assertEquals(emptyList<String>(), DocFolders.children(keys, "notes/1104") { 0L }.map { it.path })

    @Test fun docsInFolder() {
        assertEquals(listOf("a.md"), DocFolders.docsIn(keys, ""))
        assertEquals(listOf("notes/总结.md"), DocFolders.docsIn(keys, "notes"))
    }

    @Test fun parentOfFolders() {
        assertNull(DocFolders.parent(""))
        assertEquals("", DocFolders.parent("notes"))
        assertEquals("notes", DocFolders.parent("notes/1104"))
    }
}
