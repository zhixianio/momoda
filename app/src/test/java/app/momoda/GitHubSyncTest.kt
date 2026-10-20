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

    @Test fun switchingLocalDirDoesNotDeleteRemote() {
        write("a.md", "a")
        write("b.md", "b")
        sync()
        // 收回存储权限后文档目录退回到 App 私有目录：那里是空的，不能当成“本地删了所有文档”
        val privateDir = File(root, "private").apply { mkdirs() }
        sync(dir = privateDir)
        assertEquals(mapOf("a.md" to "a", "b.md" to "b"), gh.files())
        assertEquals("a", read("a.md", privateDir))
    }

    @Test fun legacyStateKeyStillCountsAsSameDir() {
        write("a.md", "a")
        write("b.md", "b")
        sync()
        // 0.9.0-beta.1 写的状态文件：key 里没有本地目录
        val state = File(root, "state.json")
        val json = org.json.JSONObject(state.readText())
        state.writeText(json.put("key", "https://api.test|me/notes|main|").toString())
        File(docs, "b.md").delete()
        sync()
        assertEquals(mapOf("a.md" to "a"), gh.files())
    }

    @Test fun downloadDoesNotOverwriteTextSavedDuringSync() {
        write("a.md", "v1")
        sync()
        gh.push(mapOf("a.md" to "别的设备改的"))
        // 同步已经扫描过本地、正在下载远端版本时，编辑器自动保存了刚打的字
        gh.beforeBlobGet = { write("a.md", "v1 刚打的字") }
        sync()
        gh.beforeBlobGet = null
        assertEquals("v1 刚打的字", read("a.md"))

        // 下一次同步按“两边都改了”处理：推送本地版本，远端版本另存
        val r = sync()
        assertEquals("v1 刚打的字", read("a.md"))
        assertEquals("v1 刚打的字", gh.files()["a.md"])
        assertEquals("别的设备改的", read(r.conflicts.single()))
    }

    @Test fun trashDoesNotTakeTextSavedDuringSync() {
        write("0.md", "0")
        write("a.md", "v1")
        sync()
        gh.push(mapOf("0.md" to "0 新", "a.md" to null))
        gh.beforeBlobGet = { write("a.md", "v1 刚打的字") }
        sync()
        gh.beforeBlobGet = null
        assertEquals("v1 刚打的字", read("a.md"))
        assertTrue(trash.list()!!.isEmpty())
        // 远端删了、本地又改过：推回去
        sync()
        assertEquals("v1 刚打的字", gh.files()["a.md"])
    }

    @Test fun editDuringSyncIsNotTreatedAsFirstSyncConflict() {
        write("a.md", "v1")
        write("b.md", "b")
        sync()
        write("a.md", "v2")
        gh.push(mapOf("b.md" to "b 新"))
        // a.md 已经上传了 v2，下载 b.md 的时候用户又改了 a.md
        gh.beforeBlobGet = { write("a.md", "v3") }
        sync()
        gh.beforeBlobGet = null
        val r = sync()
        assertEquals(emptyList<String>(), r.conflicts)
        assertEquals("v3", gh.files()["a.md"])
    }

    @Test fun targetToStringHidesToken() = assertFalse(target().toString().contains("secret-token"))
}
