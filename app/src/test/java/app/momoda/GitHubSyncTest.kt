package app.momoda

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class GitHubSyncTest {

    private val root = Files.createTempDirectory("momoda-sync").toFile()
    private val docs = File(root, "docs").apply { mkdirs() }
    private val trash = File(root, "trash").apply { mkdirs() }
    private val gh = FakeGitHub()

    @After fun cleanup() { root.deleteRecursively() }

    private fun target(subdir: String = "") = GitHubSync.Target("https://api.test", "me/notes", "main", subdir, "secret-token")

    private fun sync(subdir: String = "", dir: File = docs) =
        GitHubSync(target(subdir), dir, File(root, "state.json"), { f -> f.renameTo(File(trash, f.name)) }, gh, { "test" }).run()

    private fun write(name: String, text: String, dir: File = docs) = File(dir, name).apply { parentFile.mkdirs(); writeText(text) }
    private fun read(name: String, dir: File = docs) = File(dir, name).takeIf { it.exists() }?.readText()

    @Test fun subdirNeverTouchesFilesOutsideIt() {
        gh.push(mapOf("README.md" to "root", "notes/a.md" to "a", "notesX/b.md" to "b", "other/c.md" to "c"))
        sync("notes")
        assertEquals("a", read("a.md"))
        assertFalse(File(docs, "b.md").exists())
        assertFalse(File(docs, "README.md").exists())

        // 本地删掉 a.md、新建 d.md：只影响 notes/ 里的文件
        File(docs, "a.md").delete()
        write("d.md", "d")
        sync("notes")
        assertEquals(mapOf("README.md" to "root", "notesX/b.md" to "b", "other/c.md" to "c", "notes/d.md" to "d"), gh.files())
    }

    @Test fun targetToStringHidesToken() = assertFalse(target().toString().contains("secret-token"))
}
