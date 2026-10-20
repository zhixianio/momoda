package app.momoda

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/** 内存里的 GitHub 仓库，只实现 [GitHubSync] 用到的几个 Git Data API。 */
class FakeGitHub(private val repo: String = "me/notes", private val branch: String = "main") : GitHubSync.Api {

    private val blobs = HashMap<String, ByteArray>()
    private val trees = HashMap<String, Map<String, String>>()
    private val commits = HashMap<String, Pair<String, String?>>() // sha → (tree, parent)
    private var head: String
    private var seq = 0

    /** 每次 GET blob 之前调用，用来模拟“同步进行中用户又保存了”。 */
    var beforeBlobGet: ((String) -> Unit)? = null
    /** 下一次 PATCH ref 时抛出的异常（只抛一次）。 */
    var failNextPatch: Exception? = null
    /** PATCH ref 实际生效后再抛出的异常（模拟响应丢失）。 */
    var failAfterPatch: Exception? = null
    val calls = mutableListOf<String>()

    init {
        trees["t0"] = emptyMap()
        commits["c0"] = "t0" to null
        head = "c0"
    }

    /** 远端当前的文件（完整路径 → 内容）。 */
    fun files(): Map<String, String> = trees.getValue(commits.getValue(head).first).mapValues { String(blobs.getValue(it.value)) }

    /** 模拟别的设备推送了一个提交：[changes] 里值为 null 表示删除。 */
    fun push(changes: Map<String, String?>) {
        val tree = HashMap(trees.getValue(commits.getValue(head).first))
        for ((p, text) in changes) if (text == null) tree.remove(p) else tree[p] = putBlob(text.toByteArray())
        val t = "t${++seq}"; trees[t] = tree
        val c = "c${++seq}"; commits[c] = t to head
        head = c
    }

    private fun putBlob(bytes: ByteArray): String = SyncPlan.blobSha(bytes).also { blobs[it] = bytes }

    override fun call(method: String, path: String, body: JSONObject?): JSONObject {
        calls += "$method $path"
        val p = path.removePrefix("/repos/$repo")
        return when {
            method == "GET" && p == "/git/ref/heads/$branch" -> JSONObject().put("object", JSONObject().put("sha", head))
            method == "GET" && p.startsWith("/git/commits/") ->
                JSONObject().put("tree", JSONObject().put("sha", commits.getValue(p.removePrefix("/git/commits/")).first))
            method == "GET" && p.startsWith("/git/trees/") -> {
                val arr = JSONArray()
                val tree = trees.getValue(p.removePrefix("/git/trees/").substringBefore('?'))
                // 和真的 GitHub 一样，递归列表里也有目录条目
                for (dir in tree.keys.flatMap { k -> k.split('/').dropLast(1).runningReduce { a, b -> "$a/$b" } }.toSet())
                    arr.put(JSONObject().put("path", dir).put("type", "tree").put("sha", "d"))
                for ((k, v) in tree) arr.put(JSONObject().put("path", k).put("type", "blob").put("sha", v))
                JSONObject().put("tree", arr).put("truncated", false)
            }
            method == "GET" && p.startsWith("/git/blobs/") -> {
                val sha = p.removePrefix("/git/blobs/")
                beforeBlobGet?.invoke(sha)
                // GitHub 返回的 base64 每 60 个字符换一行
                JSONObject().put("content", Base64.getMimeEncoder(60, "\n".toByteArray()).encodeToString(blobs.getValue(sha))).put("encoding", "base64")
            }
            method == "POST" && p == "/git/blobs" -> JSONObject().put("sha", putBlob(Base64.getDecoder().decode(body!!.getString("content"))))
            method == "POST" && p == "/git/trees" -> {
                val tree = HashMap(trees.getValue(body!!.getString("base_tree")))
                val entries = body.getJSONArray("tree")
                for (i in 0 until entries.length()) {
                    val e = entries.getJSONObject(i)
                    if (e.isNull("sha")) tree.remove(e.getString("path")) else tree[e.getString("path")] = e.getString("sha")
                }
                val t = "t${++seq}"; trees[t] = tree
                JSONObject().put("sha", t)
            }
            method == "POST" && p == "/git/commits" -> {
                val c = "c${++seq}"
                commits[c] = body!!.getString("tree") to body.getJSONArray("parents").getString(0)
                JSONObject().put("sha", c)
            }
            method == "PATCH" && p == "/git/refs/heads/$branch" -> {
                failNextPatch?.let { failNextPatch = null; throw it }
                val c = body!!.getString("sha")
                if (commits.getValue(c).second != head) throw GitHubSync.HttpError(422, "Update is not a fast forward")
                head = c
                failAfterPatch?.let { failAfterPatch = null; throw it }
                JSONObject()
            }
            else -> throw GitHubSync.HttpError(404, "not found: $method $path")
        }
    }
}
