package app.momoda

import java.security.MessageDigest

/**
 * GitHub 同步的决策（DESIGN §5），纯逻辑。
 *
 * 三方比较：本地现在的内容、远端现在的内容、上次同步成功时双方一致的内容（base），都用 git blob sha 表示。
 * 原则：
 * - 正文以设备为准：两边都改了，推送本地版本，远端版本另存为“名字.remote-时间.md”，绝不静默丢弃任何一方。
 * - 远端删了、本地没改：移进回收站，不直接删除。
 * - 本地删了、远端没改：远端也删除。本地删了、远端又改过：把远端版本拉回来（以免误删别人刚写的内容）。
 */
object SyncPlan {

    sealed class Action(open val path: String) {
        data class Upload(override val path: String) : Action(path)
        data class Download(override val path: String) : Action(path)
        data class DeleteRemote(override val path: String) : Action(path)
        /** 本地移进回收站。 */
        data class TrashLocal(override val path: String) : Action(path)
        /** 两边都改了：远端版本另存为 [copyPath]，然后推送本地版本。 */
        data class Conflict(override val path: String, val copyPath: String) : Action(path)
    }

    /**
     * @param local 本地文件 path → blob sha
     * @param remote 远端文件 path → blob sha
     * @param base 上次同步后的 path → blob sha（第一次同步时为空）
     * @param stamp 冲突副本文件名里的时间，例如 `1020-0815`
     */
    fun plan(local: Map<String, String>, remote: Map<String, String>, base: Map<String, String>, stamp: String): List<Action> {
        val out = mutableListOf<Action>()
        for (path in (local.keys + remote.keys + base.keys).toSortedSet()) {
            val l = local[path]
            val r = remote[path]
            val b = base[path]
            when {
                l == r -> Unit                                       // 一致（包括两边都删了）
                l != null && r == null && b == null -> out += Action.Upload(path)          // 本地新建
                l == null && r != null && b == null -> out += Action.Download(path)        // 远端新建
                l != null && r != null && b == null -> out += Action.Conflict(path, copyName(path, stamp, r, local, remote)) // 两边各自新建了同名文件
                l == b && r == null -> out += Action.TrashLocal(path)                       // 远端删了，本地没改
                l == b -> out += Action.Download(path)                                     // 只有远端改了
                r == b && l == null -> out += Action.DeleteRemote(path)                    // 本地删了，远端没改
                r == b -> out += Action.Upload(path)                                       // 只有本地改了
                l == null -> out += Action.Download(path)                                  // 本地删了，但远端又改过：拉回来
                r == null -> out += Action.Upload(path)                                    // 远端删了，但本地又改过：推回去
                else -> out += Action.Conflict(path, copyName(path, stamp, r!!, local, remote)) // 两边都改了
            }
        }
        return out
    }

    /**
     * 同步之后的 base：本地（按执行结果推算的 [agreed]）和远端一致的文件记为一致的内容；
     * 不一致的（同步期间本地又改了、下载被跳过）沿用原来的 base，下次照常三方比较，不会被当成第一次同步。
     */
    fun nextBase(agreed: Map<String, String>, remote: Map<String, String>, base: Map<String, String>): Map<String, String> {
        val out = HashMap<String, String>()
        for (path in agreed.keys + remote.keys + base.keys) {
            val l = agreed[path]
            val r = remote[path]
            if (l != null && l == r) out[path] = l
            else if (l != r) base[path]?.let { out[path] = it }
        }
        return out
    }

    /**
     * 冲突副本的文件名，不能覆盖已有的文件：同一分钟里同一篇第二次冲突时，第一次的副本还在。
     * 已有的文件内容正好就是这份远端版本（上次推送失败后重试）时沿用它，不再多存一份。
     */
    private fun copyName(path: String, stamp: String, sha: String, local: Map<String, String>, remote: Map<String, String>): String {
        var n = 1
        while (true) {
            val name = conflictName(path, if (n == 1) stamp else "$stamp-$n")
            if (local[name].let { it == null || it == sha } && remote[name].let { it == null || it == sha }) return name
            n++
        }
    }

    fun conflictName(path: String, stamp: String): String {
        val dot = path.lastIndexOf('.')
        return if (dot > path.lastIndexOf('/')) "${path.substring(0, dot)}.remote-$stamp${path.substring(dot)}"
        else "$path.remote-$stamp"
    }

    /** git 的 blob sha：sha1("blob <长度>\u0000" + 内容)。和 GitHub 树里的 sha 一致。 */
    fun blobSha(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update("blob ${bytes.size}\u0000".toByteArray())
        md.update(bytes)
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
