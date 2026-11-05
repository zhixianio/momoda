package app.momoda

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** GitHub 同步的配置。token 用 Android Keystore 加密保存。 */
class SyncSettings(context: Context) {
    private val prefs = context.getSharedPreferences("sync", Context.MODE_PRIVATE)

    /** `owner/name` */
    var repo: String
        get() = prefs.getString("repo", "")!!
        set(v) = prefs.edit().putString("repo", v.trim().removePrefix("https://github.com/").trim('/')).apply()

    var branch: String
        get() = prefs.getString("branch", "main")!!.ifBlank { "main" }
        set(v) = prefs.edit().putString("branch", v.trim()).apply()

    var token: String
        get() = prefs.getString("token", null)?.let { runCatching { SecretBox.open(it) }.getOrNull() } ?: ""
        set(v) = prefs.edit().putString("token", if (v.isBlank()) null else SecretBox.seal(v.trim())).apply()

    /** 仓库里的子目录，例如 `notes/momoda`；空表示仓库根目录。 */
    var subdir: String
        get() = prefs.getString("subdir", "")!!
        set(v) = prefs.edit().putString("subdir", v.trim().trim('/').replace(Regex("/+"), "/")).apply()

    var auto: Boolean
        get() = prefs.getBoolean("auto", true)
        set(v) = prefs.edit().putBoolean("auto", v).apply()

    /** 默认 https://api.github.com；debug 包可以指向本地模拟服务。 */
    var apiBase: String
        get() = prefs.getString("apiBase", "https://api.github.com")!!
        set(v) = prefs.edit().putString("apiBase", v).apply()

    /** GitHub 网页地址（设备授权用）；debug 包可以指向本地模拟服务。 */
    var webBase: String
        get() = prefs.getString("webBase", "https://github.com")!!
        set(v) = prefs.edit().putString("webBase", v).apply()

    /** GitHub App 的 Client ID：默认来自构建配置，debug 包可以覆盖。 */
    var clientId: String
        get() = prefs.getString("clientId", null) ?: BuildConfig.GITHUB_CLIENT_ID
        set(v) = prefs.edit().putString("clientId", v.trim()).apply()

    val configured get() = repo.matches(Regex("[^/\\s]+/[^/\\s]+")) && token.isNotBlank()

    /** 此刻的配置。一次同步从头到尾只用这一份，中途改设置不会让它写到别的仓库或目录。 */
    fun target() = GitHubSync.Target(apiBase, repo, branch, subdir, token)
}

/**
 * GitHub 同步（DESIGN §5）：直接调用 GitHub 的 REST API（Git Data API），设备上不放完整的 git。
 *
 * 一次同步：读远端最新提交的文件树 → 和本地、上次同步的状态三方比较（[SyncPlan]）→
 * 下载、移进回收站、保存冲突副本 → 把本地的新增、修改、删除做成一个提交推上去。
 * 推送时如果远端又有了新提交（非快进），整个流程重来，最多 3 次。
 *
 * 同步范围：文档目录里所有 .md（含子目录，例如 agent 写的 `_agent/`），不含隐藏目录（回收站）和 fonts/。
 * 可以只同步到仓库里的某个子目录（[SyncSettings.subdir]），子目录以外的文件不会被读取或改动。
 */
class GitHubSync(
    private val target: Target,
    private val dir: File,
    private val stateFile: File,
    private val moveToTrash: (File) -> Unit,
    private val client: Api = HttpApi(target),
    private val device: () -> String = { "${Build.MANUFACTURER} ${Build.MODEL}" },
    /** 和编辑器保存共用的锁：检查本地文件没变和写入之间，不能插进一次保存。 */
    private val lock: Any = Any(),
    /**
     * 本地这篇可以让位给远端的同名文件（例如新装 App 时生成、还没改过的欢迎文档）：
     * 两边不同时直接下载远端的，不当作冲突另存一份。每台新设备第一次同步都会碰到。
     */
    private val disposable: (path: String, bytes: ByteArray) -> Boolean = { _, _ -> false },
) {
    /** 同步的目标。token 不出现在 toString 里，以免被写进日志。 */
    class Target(val apiBase: String, val repo: String, val branch: String, val subdir: String, val token: String) {
        override fun toString() = "$apiBase|$repo|$branch|$subdir"
    }

    /** GitHub REST API：返回 JSON；出错时抛 [HttpError]。单测里换成模拟的仓库。 */
    fun interface Api {
        fun call(method: String, path: String, body: JSONObject?): JSONObject
    }

    data class Result(
        val uploaded: Int, val downloaded: Int, val deleted: Int, val trashed: Int, val conflicts: List<String>,
        /** 和别的文件只差大小写、本地放不下而没有同步的远端文件。 */
        val skipped: List<String> = emptyList(),
    ) {
        val changedLocal get() = downloaded + trashed + conflicts.size > 0
        fun summary() = buildString {
            val parts = mutableListOf<String>()
            if (uploaded > 0) parts += "上传 $uploaded"
            if (downloaded > 0) parts += "下载 $downloaded"
            if (deleted > 0) parts += "远端删除 $deleted"
            if (trashed > 0) parts += "移进回收站 $trashed"
            if (conflicts.isNotEmpty()) parts += "冲突 ${conflicts.size}（远端版本已另存）"
            if (skipped.isNotEmpty()) parts += "跳过 ${skipped.joinToString("、")}（和别的文件只差大小写，请在电脑上改名）"
            append(if (parts.isEmpty()) "已是最新" else parts.joinToString("，"))
        }
    }

    private class NotFastForward : Exception()

    /** 另一次同步还没结束。 */
    class Busy : Exception("上一次同步还没结束")

    /**
     * 同步一次。同一进程里同时只能有一次同步：状态文件和本地文件只有一份，
     * Activity 重建后新实例不知道旧实例的同步还在跑，所以不能靠 Activity 里的标志。
     * 已经有同步在跑时抛 [Busy]。
     */
    fun run(): Result {
        if (!running.compareAndSet(false, true)) throw Busy()
        try {
            repeat(3) { attempt ->
                try {
                    return once()
                } catch (e: NotFastForward) {
                    if (attempt == 2) throw IllegalStateException("远端一直在变化，稍后再试")
                }
            }
            error("unreachable")
        } finally {
            running.set(false)
        }
    }

    companion object {
        private val running = AtomicBoolean(false)
    }

    private fun once(): Result {
        val (owner, name) = target.repo.split('/', limit = 2)
        val repoPath = "/repos/$owner/$name"
        val branch = target.branch

        // 1. 远端：分支最新提交 → 文件树
        val ref = try {
            api("GET", "$repoPath/git/ref/heads/$branch")
        } catch (e: HttpError) {
            if (e.code == 404 || e.code == 409) error("找不到分支 $branch。如果仓库是空的，请在 GitHub 上先创建一个 README")
            throw e
        }
        val headSha = ref.getJSONObject("object").getString("sha")
        val treeSha = api("GET", "$repoPath/git/commits/$headSha").getJSONObject("tree").getString("sha")
        val tree = api("GET", "$repoPath/git/trees/$treeSha?recursive=1")
        if (tree.optBoolean("truncated")) error("仓库文件太多，GitHub 返回的文件树被截断了")
        val remote = HashMap<String, String>()
        val entries = tree.getJSONArray("tree")
        for (i in 0 until entries.length()) {
            val e = entries.getJSONObject(i)
            // 只看子目录里的文件，并换算成相对子目录的路径；子目录外的文件原样保留在远端
            val full = e.getString("path")
            if (e.getString("type") != "blob" || !full.startsWith(prefix)) continue
            val path = full.removePrefix(prefix)
            if (inScope(path)) remote[path] = e.getString("sha")
        }

        // 2. 本地和上次同步的状态
        val local = scanLocal()
        val state = loadState()
        val key = state.optString("key")
        val savedBase = if (key == stateKey() || key == legacyStateKey()) toMap(state.optJSONObject("base")) else emptyMap()
        // 可以让位的本地文件：当作上次同步时就是这样（没改过），三方比较时远端的版本胜出
        val base = HashMap(savedBase)
        for ((path, sha) in local) {
            val r = remote[path] ?: continue
            if (r != sha && savedBase[path] == null && disposable(path, File(dir, path).readBytes())) base[path] = sha
        }

        // 只差大小写的远端文件本地放不下，这次不同步，也不会因为“本地没有”去删远端的
        val skipped = SyncPlan.caseClashes(local, remote, base).sorted()
        remote.keys.removeAll(skipped.toSet())

        // 3. 决策并执行
        val stamp = SimpleDateFormat("MMdd-HHmm", Locale.CHINA).format(Date())
        val actions = SyncPlan.plan(local, remote, base, stamp)
        val treeEntries = JSONArray()
        var uploaded = 0; var downloaded = 0; var deleted = 0; var trashed = 0
        val conflicts = mutableListOf<String>()
        // 执行之后本地应有的内容。下载和移进回收站之前都核对文件还是扫描时的样子：
        // 同步要联网好几秒，这期间编辑器可能刚保存过，不能把刚打的字覆盖掉或移走（留给下次同步按冲突处理）。
        val agreed = HashMap(local)
        // 写到本地的远端版本：本地路径 → （远端路径，内容的 sha），之后把修改时间改成远端最后一次提交的时间
        val written = HashMap<String, Pair<String, String>>()
        for (a in actions) {
            when (a) {
                is SyncPlan.Action.Download -> {
                    val bytes = blob(repoPath, remote.getValue(a.path))
                    if (ifUnchanged(a.path, local[a.path]) { writeLocal(a.path, bytes) }) {
                        agreed[a.path] = remote.getValue(a.path); downloaded++
                        written[a.path] = a.path to remote.getValue(a.path)
                    }
                }
                is SyncPlan.Action.TrashLocal -> {
                    if (ifUnchanged(a.path, local[a.path]) { moveToTrash(File(dir, a.path)) }) { agreed.remove(a.path); trashed++ }
                }
                is SyncPlan.Action.Conflict -> {
                    val bytes = blob(repoPath, remote.getValue(a.path))
                    if (!ifUnchanged(a.copyPath, local[a.copyPath]) { writeLocal(a.copyPath, bytes) }) error("${a.copyPath} 刚被改过，稍后再同步")
                    agreed[a.copyPath] = remote.getValue(a.path)
                    written[a.copyPath] = a.path to remote.getValue(a.path)
                    conflicts += a.copyPath
                    treeEntries.put(uploadEntry(repoPath, a.path).also { agreed[a.path] = it.getString("sha") }); uploaded++
                }
                is SyncPlan.Action.Upload -> { treeEntries.put(uploadEntry(repoPath, a.path).also { agreed[a.path] = it.getString("sha") }); uploaded++ }
                is SyncPlan.Action.DeleteRemote -> {
                    treeEntries.put(JSONObject().put("path", prefix + a.path).put("mode", "100644").put("type", "blob").put("sha", JSONObject.NULL))
                    deleted++
                }
            }
        }

        // 下载下来的文件，修改时间是这一刻；改成它在远端最后一次提交的时间，文档列表里的日期才是文档真正改的时间
        val downloadedTimesOk = applyRemoteTimes(owner, name, written, onlyEarlier = false)
        // 升级到这一版后第一次同步：以前下载的文件修改时间都是下载那一刻，补一次。只往早里改，
        // 在这台设备上写的文档（本地修改时间早于推送的提交）不动。这次下载的没改成功，也留到下次一起补
        val timesFixed = downloadedTimesOk && (state.optBoolean("timesFixed") ||
            applyRemoteTimes(owner, name, local.filter { (p, sha) -> remote[p] == sha && p !in written }.mapValues { (p, sha) -> p to sha }, onlyEarlier = true))

        // 4. 推送一个提交
        val remoteAfter = HashMap(remote)
        if (treeEntries.length() > 0) {
            val newTree = api("POST", "$repoPath/git/trees", JSONObject().put("base_tree", treeSha).put("tree", treeEntries)).getString("sha")
            val message = "墨墨打同步：${if (uploaded > 0) "更新 $uploaded 篇" else ""}${if (uploaded > 0 && deleted > 0) "，" else ""}${if (deleted > 0) "删除 $deleted 篇" else ""}\n\n来自 ${device()}"
            val commit = api("POST", "$repoPath/git/commits", JSONObject()
                .put("message", message).put("tree", newTree).put("parents", JSONArray().put(headSha))).getString("sha")
            try {
                api("PATCH", "$repoPath/git/refs/heads/$branch", JSONObject().put("sha", commit).put("force", false))
            } catch (e: HttpError) {
                if (e.code == 422) throw NotFastForward()
                throw e
            }
            for (i in 0 until treeEntries.length()) {
                val e = treeEntries.getJSONObject(i)
                val rel = e.getString("path").removePrefix(prefix)
                if (e.isNull("sha")) remoteAfter.remove(rel) else remoteAfter[rel] = e.getString("sha")
            }
        }

        // 5. 记录新的“共同状态”。不重新扫描本地：同步期间又改过的文件，base 应当是这次推送的版本
        val newBase = JSONObject()
        for ((p, sha) in SyncPlan.nextBase(agreed, remoteAfter, base)) newBase.put(p, sha)
        stateFile.writeText(JSONObject().put("key", stateKey()).put("base", newBase).put("time", System.currentTimeMillis())
            .put("timesFixed", timesFixed).toString())

        return Result(uploaded, downloaded, deleted, trashed, conflicts, skipped)
    }

    /**
     * 把 [written] 里的本地文件的修改时间改成远端最后一次改它的提交时间。查不到（网络、权限、接口变化）就保持原样，
     * 不影响同步，返回 false。改之前核对文件还是刚写进去的内容：同步期间编辑器刚保存过的，不去动它。
     * [onlyEarlier] 时只把修改时间往早里改。
     */
    private fun applyRemoteTimes(owner: String, name: String, written: Map<String, Pair<String, String>>, onlyEarlier: Boolean): Boolean {
        if (written.isEmpty()) return true
        val times = runCatching { lastCommitTimes(owner, name, written.values.map { it.first }.distinct()) }.getOrNull() ?: return false
        for ((localPath, remoteAndSha) in written) {
            val time = times[remoteAndSha.first] ?: continue
            val f = File(dir, localPath)
            val seen = f.lastModified()
            if (!f.isFile || (onlyEarlier && seen <= time)) continue
            // 读文件、算 sha 不拿锁（文档多时要好一会儿，不能挡住编辑器保存）；
            // 改时间时再拿锁核对修改时间没变：这期间保存过的，不去动它
            if (SyncPlan.blobSha(f.readBytes()) != remoteAndSha.second) continue
            synchronized(lock) { if (f.lastModified() == seen) f.setLastModified(time) }
        }
        return true
    }

    /** 每个文件最后一次被改的提交时间（毫秒）。用 GraphQL 一次查 50 个；REST 只能一个个查，第一次同步几百篇会很慢。 */
    private fun lastCommitTimes(owner: String, name: String, paths: List<String>): Map<String, Long> {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        val out = HashMap<String, Long>()
        for (chunk in paths.chunked(50)) {
            val fields = chunk.mapIndexed { i, p ->
                "f$i: history(first: 1, path: ${JSONObject.quote(prefix + p)}) { nodes { committedDate } }"
            }.joinToString(" ")
            val query = "query { repository(owner: ${JSONObject.quote(owner)}, name: ${JSONObject.quote(name)}) { " +
                "ref(qualifiedName: ${JSONObject.quote("refs/heads/${target.branch}")}) { target { ... on Commit { $fields } } } } }"
            val r = api("POST", "/graphql", JSONObject().put("query", query))
            // GraphQL 出错时常常仍是 HTTP 200，错误放在 errors 里：当作失败，下次同步再补
            if (r.has("errors") || r.isNull("data")) error("GraphQL：${r.optJSONArray("errors")?.optJSONObject(0)?.optString("message").orEmpty()}")
            val commit = r.optJSONObject("data")?.optJSONObject("repository")?.optJSONObject("ref")?.optJSONObject("target") ?: continue
            chunk.forEachIndexed { i, p ->
                val date = commit.optJSONObject("f$i")?.optJSONArray("nodes")?.optJSONObject(0)?.optString("committedDate").orEmpty()
                runCatching { iso.parse(date)?.time }.getOrNull()?.let { out[p] = it }
            }
        }
        return out
    }

    /** 读取 GitHub 仓库信息，用于“测试连接”：返回能否写入。 */
    fun check(): String {
        val (owner, name) = target.repo.split('/', limit = 2)
        val r = api("GET", "/repos/$owner/$name")
        val push = r.optJSONObject("permissions")?.optBoolean("push") ?: false
        return if (push) "连接成功：${r.optString("full_name")}（可以写入）" else "能读取 ${r.optString("full_name")}，但没有写入权限：请给 token 加上 Contents 的 Read and write"
    }

    /**
     * “上次同步的状态”属于哪一对（远端位置，本地目录）。本地目录也要算在内：
     * 收回存储权限后文档目录会退回 App 私有目录，里面没有这些文档；
     * 如果沿用原来的 base，会被当成“本地全删了”，把远端的文档全部删掉。
     */
    private fun stateKey() = "${legacyStateKey()}|${dir.absolutePath}"

    /** 0.9.0-beta.1 的 key 不含本地目录；升级后第一次同步时认为目录没变。 */
    private fun legacyStateKey() = "${target.apiBase}|${target.repo}|${target.branch}|${target.subdir}"

    /** 子目录前缀，例如 `notes/momoda/`；根目录时为空。 */
    private val prefix = target.subdir.let { if (it.isEmpty()) "" else "$it/" }

    private fun inScope(path: String): Boolean =
        path.endsWith(".md") && path.split('/').none { it.startsWith(".") } && !path.startsWith("fonts/")

    private fun scanLocal(): Map<String, String> {
        val out = HashMap<String, String>()
        dir.walkTopDown()
            .onEnter { it == dir || (!it.name.startsWith(".") && !(it.parentFile == dir && it.name == "fonts")) }
            .filter { it.isFile }
            .forEach { f ->
                val path = f.relativeTo(dir).invariantSeparatorsPath
                if (inScope(path)) out[path] = SyncPlan.blobSha(f.readBytes())
            }
        return out
    }

    /** 本地文件的内容仍是 [expected]（null 表示不存在）时才执行 [action]，返回是否执行了。 */
    private fun ifUnchanged(path: String, expected: String?, action: () -> Unit): Boolean = synchronized(lock) {
        val f = File(dir, path)
        val now = if (f.isFile) SyncPlan.blobSha(f.readBytes()) else null
        if (now != expected) return false
        action()
        true
    }

    private fun writeLocal(path: String, bytes: ByteArray) {
        val target = File(dir, path)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, ".${target.name}.sync")
        FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
        if (!tmp.renameTo(target)) { target.writeBytes(bytes); tmp.delete() }
    }

    private fun uploadEntry(repoPath: String, path: String): JSONObject {
        val bytes = File(dir, path).readBytes()
        val sha = api("POST", "$repoPath/git/blobs", JSONObject()
            .put("content", Base64.getEncoder().encodeToString(bytes)).put("encoding", "base64")).getString("sha")
        return JSONObject().put("path", prefix + path).put("mode", "100644").put("type", "blob").put("sha", sha)
    }

    private fun blob(repoPath: String, sha: String): ByteArray {
        val r = api("GET", "$repoPath/git/blobs/$sha")
        return Base64.getMimeDecoder().decode(r.getString("content"))
    }

    private fun loadState(): JSONObject = runCatching { JSONObject(stateFile.readText()) }.getOrDefault(JSONObject())

    private fun toMap(o: JSONObject?): Map<String, String> {
        if (o == null) return emptyMap()
        val m = HashMap<String, String>()
        for (k in o.keys()) m[k] = o.getString(k)
        return m
    }

    class HttpError(val code: Int, message: String) : Exception(message)

    private fun api(method: String, path: String, body: JSONObject? = null): JSONObject = client.call(method, path, body)

    private class HttpApi(private val target: Target) : Api {
        override fun call(method: String, path: String, body: JSONObject?): JSONObject {
            val conn = (URL(target.apiBase.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
                // Android 的 HttpURLConnection（基于 OkHttp）支持 PATCH，更新分支引用要用它
                requestMethod = method
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("Authorization", "Bearer ${target.token}")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
            }
            try {
                if (body != null) conn.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) {
                    val msg = runCatching { JSONObject(text).optString("message") }.getOrNull().orEmpty().ifEmpty { text.take(200) }
                    throw HttpError(code, when (code) {
                        401 -> "token 无效或已过期"
                        403 -> "没有权限（$msg）"
                        404 -> "找不到仓库 ${target.repo}，或 token 没有访问它的权限"
                        else -> "GitHub 返回 $code：$msg"
                    })
                }
                return if (text.isBlank()) JSONObject() else JSONObject(text)
            } finally {
                conn.disconnect()
            }
        }
    }
}
