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

    @Test fun failedRefUpdateIsSafeToRetry() {
        write("a.md", "v1")
        write("b.md", "b")
        sync()
        write("a.md", "本地 v2")
        gh.push(mapOf("a.md" to "远端 v2", "b.md" to null, "c.md" to "c"))
        gh.failNextPatch = java.io.IOException("网络断了")
        assertTrue(runCatching { sync() }.isFailure)
        // 下载、移进回收站、冲突副本已经落地，推送没成功；再同步一次
        sync()
        assertEquals("本地 v2", gh.files()["a.md"])
        assertEquals("本地 v2", read("a.md"))
        assertEquals("c", read("c.md"))
        assertFalse(gh.files().containsKey("b.md"))
        // 远端版本另存了（同一分钟内重试沿用同一份副本；跨了分钟会多一份内容相同的副本）
        val copies = docs.list()!!.filter { it.contains(".remote-") }
        assertTrue(copies.isNotEmpty())
        for (c in copies) {
            assertEquals("远端 v2", read(c))
            assertEquals("远端 v2", gh.files()[c])
        }
    }

    @Test fun lostRefUpdateResponseIsSafeToRetry() {
        write("a.md", "v1")
        sync()
        write("a.md", "v2")
        gh.failAfterPatch = java.io.IOException("响应丢了")
        assertTrue(runCatching { sync() }.isFailure)
        val r = sync()
        assertEquals("已是最新", r.summary())
        assertEquals(mapOf("a.md" to "v2"), gh.files())
    }

    @Test fun notFastForwardRetriesWithoutLosingRemoteCommit() {
        write("a.md", "v1")
        sync()
        write("a.md", "v2")
        // 推送前一刻别的设备抢先推了 b.md
        var pushed = false
        val racing = object : GitHubSync.Api {
            override fun call(method: String, path: String, body: org.json.JSONObject?): org.json.JSONObject {
                if (method == "PATCH" && !pushed) { pushed = true; gh.push(mapOf("b.md" to "b")) }
                return gh.call(method, path, body)
            }
        }
        GitHubSync(target(), docs, File(root, "state.json"), { f -> f.renameTo(File(trash, f.name)) }, racing, { "test" }).run()
        assertEquals(mapOf("a.md" to "v2", "b.md" to "b"), gh.files())
        assertEquals("b", read("b.md"))
    }

    @Test fun onlyOneSyncAtATimeInProcess() {
        write("a.md", "v1")
        sync()
        gh.push(mapOf("a.md" to "v2"))
        // 第一次同步下载到一半时（Activity 重建后）又开始了一次同步
        var second: Result<GitHubSync.Result>? = null
        gh.beforeBlobGet = { gh.beforeBlobGet = null; second = runCatching { sync() } }
        sync()
        assertTrue(second!!.exceptionOrNull() is GitHubSync.Busy)
        assertEquals("v2", read("a.md"))
        // 结束后可以再同步
        assertEquals("已是最新", sync().summary())
    }

    @Test fun remoteNamesDifferingOnlyInCaseAreNotMixedUp() {
        gh.push(mapOf("A.md" to "大写", "a.md" to "小写"))
        val r = sync()
        assertEquals(listOf("a.md"), r.skipped)
        assertEquals(listOf("A.md"), docs.list()!!.filter { it.endsWith(".md") })
        assertEquals("大写", read("A.md"))
        // 再同步也不会把远端的哪一个覆盖或删掉
        write("A.md", "大写 改过")
        sync()
        assertEquals(mapOf("A.md" to "大写 改过", "a.md" to "小写"), gh.files())
    }

    @Test fun caseOnlyRenameOnRemoteFollowsInOneSync() {
        write("a.md", "内容")
        sync()
        gh.push(mapOf("a.md" to null, "A.md" to "内容"))
        sync()
        assertEquals(listOf("A.md"), docs.list()!!.filter { it.endsWith(".md") })
        assertEquals("内容", read("A.md"))
    }

    @Test fun targetToStringHidesToken() = assertFalse(target().toString().contains("secret-token"))
}
