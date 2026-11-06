package app.momoda

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文档就是一个目录里的 .md 文件（DESIGN §4），可以分在子文件夹里（例如别的应用推到 GitHub 仓库里的）。
 * 有“所有文件访问”权限时放在 Documents/momoda/，卸载 App 也不会丢；
 * 没有权限时先放在 App 私有目录，保证“即开即打”，授权后自动迁移过去。
 * 每篇文档用相对 [dir] 的路径（[key]）记录光标、上次打开等，不同文件夹里可以有同名文件。
 */
class DocStore(private val context: Context) {

    private val prefs = context.getSharedPreferences("docs", Context.MODE_PRIVATE)
    private val privateDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "docs")

    var dir: File = resolveDir()
        private set

    val isPublic: Boolean get() = dir == publicDir()
    private val trash get() = File(dir, ".trash")

    /**
     * 本地文档改了几次（写入、新建、改名、删除、移进或移出回收站）。同步开始时记下这个数，
     * 结束时没变就说明这次同步把改动都传上去了。同步自己写文件不经过这里，不算。
     */
    var localChanges = 0L
        private set

    /** 每次本地改动后调用（主线程）。 */
    var onLocalChange: (() -> Unit)? = null

    private fun changed() {
        localChanges++
        onLocalChange?.invoke()
    }

    /** 所有文档，含子文件夹，最近修改的在前。范围和同步一样：不含以点开头的目录（回收站等）和 fonts/。 */
    fun list(): List<File> = dir.walkTopDown()
        .onEnter { it == dir || (!it.name.startsWith(".") && !(it.parentFile == dir && it.name == "fonts")) }
        .filter { it.isFile && it.name.endsWith(".md") && !it.name.startsWith(".") }
        .sortedByDescending { it.lastModified() }
        .toList()

    /** 文档相对文档目录的路径，例如 `notes/1104/会议.md`；根目录下的就是文件名。 */
    fun key(file: File): String = file.relativeTo(dir).invariantSeparatorsPath

    fun fileFor(key: String): File = File(dir, key)

    /** 文档所在的文件夹（相对路径，根目录是空字符串）。 */
    fun folderOf(file: File): String = file.parentFile?.relativeTo(dir)?.invariantSeparatorsPath.orEmpty()

    /** 新文档放在哪个文件夹（相对文档目录，空字符串是根目录）。 */
    var newDocFolder: String
        get() = prefs.getString("newDocFolder", "")!!
        set(v) = prefs.edit().putString("newDocFolder", cleanFolder(v)).apply()

    /** 新文档：以日期命名，内容是一个空的一级标题（DESIGN §1），放在 [newDocFolder] 里。 */
    fun create(): File {
        val folder = File(dir, newDocFolder).apply { mkdirs() }.takeIf { it.isDirectory } ?: dir
        val name = Titles.uniqueFileName(today()) { File(folder, it).exists() }
        return File(folder, name).apply {
            writeText("# ")
            // 记下是墨墨打建的：以后文件名跟着标题改（见 namedByMomoda）
            prefs.edit().putBoolean("made:${key(this)}", true).apply()
            changed()
        }
    }

    /** 列表里显示的标题、字数、摘要（有缓存，不改变“上次读写”的记录）。 */
    val summaries = DocSummaries()

    fun read(file: File): String {
        if (!file.exists()) return ""
        val text = file.readText()
        known.remember(file.absolutePath, file.lastModified(), text)
        return text
    }

    /** 我们最后一次读或写每个文件时它的样子，用来发现“被其他程序改过”。 */
    private val known = KnownFiles()

    /** 文件在我们上次读写之后被别的程序改过（或删掉了）。 */
    fun changedOnDisk(file: File): Boolean {
        val changed = known.changed(file.absolutePath, file.exists(), file.lastModified()) { file.readText() }
        if (changed) Log.w("momoda", "文件被改过：${file.name}，现在${if (file.exists()) "的修改时间 ${file.lastModified()}" else "已删除"}")
        return changed
    }

    /** 冲突时把我们的版本另存为“标题 冲突 1020-0815.md”，不动对方的版本。 */
    fun saveConflictCopy(file: File, text: String): File {
        val stamp = SimpleDateFormat("MMdd-HHmm", Locale.CHINA).format(Date())
        val folder = file.parentFile ?: dir
        val name = Titles.uniqueFileName("${file.nameWithoutExtension} 冲突 $stamp") { File(folder, it).exists() }
        val copy = File(folder, name)
        write(copy, text)
        return copy
    }

    /** 先写临时文件再 rename，并 fsync，断电也不会留下半个文件。 */
    fun write(file: File, text: String) {
        val tmp = File(file.parentFile ?: dir, ".${file.name}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray())
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            file.writeText(text)
            tmp.delete()
        }
        known.remember(file.absolutePath, file.lastModified(), text)
        changed()
    }

    /**
     * 文件名是不是墨墨打自己起的（新建时的日期名，或者跟着标题起的名字）。
     * 只有这样的文件，文件名才跟着标题改；别的应用起的名字（例如 `2026-11-04-meeting.md`）不动。
     * 在打开文档时用当时的内容判断（[text]），离开时把结果交给 [syncNameWithTitle]。
     */
    fun namedByMomoda(file: File, text: String): Boolean =
        file.name != Prompts.FILE_NAME &&
            Titles.isMomodaName(file.nameWithoutExtension, Titles.titleOf(text), prefs.getBoolean("made:${key(file)}", false))

    /**
     * 让文件名跟随标题（离开文档时调用，打字过程中不改）。[follow] 为 false 时不改（见 [namedByMomoda]）。
     * 没有标题时保持原名；已经是“标题 2.md”这种带序号的名字时也不再变动。
     */
    fun syncNameWithTitle(file: File, text: String, follow: Boolean): File {
        // AI 指令表的文件名是固定的，不能跟着标题改名
        if (!follow || file.name == Prompts.FILE_NAME) return file
        val current = file.nameWithoutExtension
        val desired = Titles.baseNameFor(Titles.titleOf(text), current)
        if (current == desired || current.matches(Regex(Regex.escape(desired) + " \\d+"))) return file
        val folder = file.parentFile ?: dir
        val name = Titles.uniqueFileName(desired) { File(folder, it).exists() }
        return rename(file, name)
    }

    fun isEmptyDoc(text: String) = text.isBlank() || text.trim() == "#"

    fun delete(file: File) {
        file.delete()
        forget(file)
        changed()
    }

    fun moveToTrash(file: File) {
        trash.mkdirs()
        val name = Titles.uniqueFileName(file.nameWithoutExtension) { File(trash, it).exists() }
        val folder = folderOf(file)
        if (file.renameTo(File(trash, name))) {
            // 记下原来在哪个文件夹，恢复时放回去
            prefs.edit().apply { if (folder.isEmpty()) remove("trashFrom:$name") else putString("trashFrom:$name", folder) }.apply()
        }
        forget(file)
        changed()
    }

    // ---------- 回收站 ----------

    fun trashList(): List<File> =
        trash.listFiles { f -> f.isFile && f.name.endsWith(".md") }?.sortedByDescending { it.lastModified() }.orEmpty()

    /** 从回收站恢复到原来的文件夹（那个文件夹没了就重新建）；原名被占用时自动加序号。 */
    fun restore(file: File): File {
        val from = prefs.getString("trashFrom:${file.name}", "").orEmpty()
        val folder = File(dir, from).apply { mkdirs() }.takeIf { it.isDirectory } ?: dir
        val name = Titles.uniqueFileName(file.nameWithoutExtension) { File(folder, it).exists() }
        val target = File(folder, name)
        if (!file.renameTo(target)) return file
        prefs.edit().remove("trashFrom:${file.name}").apply()
        changed()
        return target
    }

    /** 从回收站彻底删除。 */
    fun purge(file: File) {
        if (file.parentFile == trash) file.delete()
    }

    /** 修改某篇文档的标题（改首行）；墨墨打起的文件名随之更新（见 [namedByMomoda]）。 */
    fun retitle(file: File, newTitle: String): File {
        val text = read(file)
        val follow = namedByMomoda(file, text)
        write(file, Titles.withTitle(text, newTitle))
        return syncNameWithTitle(file, read(file), follow)
    }

    /** 在原来的文件夹里改名。 */
    private fun rename(file: File, newName: String): File {
        val target = File(file.parentFile ?: dir, newName)
        if (!file.renameTo(target)) return file
        known.move(file.absolutePath, target.absolutePath, target.lastModified())
        val cursor = cursorOf(file)
        val made = prefs.getBoolean("made:${key(file)}", false)
        forget(file)
        if (cursor >= 0) saveCursor(target, cursor)
        if (made) prefs.edit().putBoolean("made:${key(target)}", true).apply()
        if (lastDocName == key(file)) lastDocName = key(target)
        if (previousDocName == key(file)) previousDocName = key(target)
        changed()
        return target
    }

    private fun forget(file: File) {
        prefs.edit().remove("cursor:${key(file)}").remove("made:${key(file)}").apply()
    }

    // ---------- 打开记录 ----------

    /** 上次打开的文档（[key]，相对路径）。 */
    var lastDocName: String?
        get() = prefs.getString("last", null)
        set(v) = prefs.edit().putString("last", v).apply()

    /** Ctrl+Tab 切回的上一篇（[key]）。 */
    var previousDocName: String?
        get() = prefs.getString("previous", null)
        set(v) = prefs.edit().putString("previous", v).apply()

    fun cursorOf(file: File): Int = prefs.getInt("cursor:${key(file)}", -1)

    fun saveCursor(file: File, offset: Int) = prefs.edit().putInt("cursor:${key(file)}", offset).apply()

    /** 最近修改的一篇正文（不算根目录的 AI 指令文件 prompts.md）。 */
    fun mostRecent(): File? = list().firstOrNull { it != File(dir, Prompts.FILE_NAME) }

    fun openLastOrCreate(): File {
        val file = lastDocName?.let { File(dir, it) }?.takeIf { it.isFile }
            ?: mostRecent()
            ?: (if (welcomed) null else createWelcome())
            ?: create()
        // 只有全新安装、目录里还没有文档时才放欢迎文档；已有文档的用户升级后也就此标记，删掉后不会再出现
        welcomed = true
        return file
    }

    private var welcomed: Boolean
        get() = prefs.getBoolean("welcomed", false)
        set(v) = prefs.edit().putBoolean("welcomed", v).apply()

    /** 内容就是随 App 带的欢迎文档、还没改过（同步时让位给仓库里的版本）。 */
    fun isUntouchedWelcome(bytes: ByteArray): Boolean = welcomeText?.let { String(bytes) == it } == true

    private val welcomeText: String? by lazy {
        runCatching { context.assets.open("welcome.md").bufferedReader().use { it.readText() } }.getOrNull()
    }

    /** 第一次打开时的说明文档（assets/welcome.md），就是一篇普通文档。 */
    private fun createWelcome(): File? {
        val text = welcomeText ?: return null
        val name = Titles.uniqueFileName("欢迎使用墨墨打") { File(dir, it).exists() }
        // 光标放在开头，打开时从第一行读起（默认会跳到文末）
        return File(dir, name).apply { writeText(text); saveCursor(this, 0) }
    }

    // ---------- 存储位置 ----------

    var askedForAccess: Boolean
        get() = prefs.getBoolean("askedAccess", false)
        set(v) = prefs.edit().putBoolean("askedAccess", v).apply()

    /** Android 10 及更早：用户拒绝存储权限时勾了“不再询问”。 */
    var accessDeniedForever: Boolean
        get() = prefs.getBoolean("accessDeniedForever", false)
        set(v) = prefs.edit().putBoolean("accessDeniedForever", v).apply()

    private fun resolveDir(): File {
        if (hasPublicAccess(context)) {
            val pub = publicDir()
            if (pub.mkdirs() || pub.isDirectory) return pub
        }
        return privateDir.apply { mkdirs() }
    }

    /**
     * 回到前台时调用：如果刚拿到权限，就把私有目录里的文档搬到 Documents/momoda。
     * 返回 true 表示存储位置变了，调用方需要重新打开当前文档。
     */
    fun refreshLocation(): Boolean {
        val target = resolveDir()
        if (target == dir) return false
        if (target == publicDir()) migrate(from = privateDir, to = target)
        dir = target
        return true
    }

    /** 逐个搬过去（见 [moveVerified]），子文件夹照原样搬。 */
    private fun migrate(from: File, to: File) {
        fun docs(root: File) = root.walkTopDown().onEnter { it == root || !it.name.startsWith(".") }
            .filter { it.isFile && it.name.endsWith(".md") && !it.name.startsWith(".") }.toList()
        val files = docs(from)
        // 重装 App 时 Documents/momoda 里已经有文档：没改过的欢迎文档不再搬过去
        val targetHasDocs = docs(to).isNotEmpty()
        for (src in files) {
            val text = src.readText()
            if (isEmptyDoc(text) || (targetHasDocs && text == welcomeText)) { src.delete(); continue }
            val rel = src.relativeTo(from).invariantSeparatorsPath
            val cursor = prefs.getInt("cursor:$rel", -1)
            val toFolder = File(to, rel).parentFile?.apply { mkdirs() } ?: to
            val dst = moveVerified(src, text, toFolder) ?: continue
            val dstKey = dst.relativeTo(to).invariantSeparatorsPath
            if (cursor >= 0) prefs.edit().remove("cursor:$rel").putInt("cursor:$dstKey", cursor).apply()
            if (lastDocName == rel) lastDocName = dstKey
            if (previousDocName == rel) previousDocName = dstKey
            Log.i("momoda", "migrated $rel → ${dst.absolutePath}")
        }
    }

    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())

    /** 去掉首尾的斜杠和多余的斜杠，不允许 `..` 和以点开头的目录。 */
    private fun cleanFolder(v: String): String = v.trim().replace('\\', '/').split('/')
        .map { it.trim() }.filter { it.isNotEmpty() && it != "." && it != ".." && !it.startsWith(".") }
        .joinToString("/")

    /** 授权存储时该打开哪里。 */
    enum class AccessRequest { ALL_FILES_SETTINGS, PERMISSION_DIALOG, APP_SETTINGS }

    companion object {
        /**
         * Android 11 起去“所有文件访问”设置页；更早的系统弹权限对话框。
         * 但用户拒绝时勾过“不再询问”（[deniedForever]）且系统不再给解释机会（![rationale]）时，
         * 系统不会再弹窗，requestPermissions 直接返回拒绝，点“授权”毫无反应：只能去应用信息页里打开。
         */
        fun accessRequest(sdk: Int, deniedForever: Boolean, rationale: Boolean): AccessRequest = when {
            sdk >= 30 -> AccessRequest.ALL_FILES_SETTINGS
            deniedForever && !rationale -> AccessRequest.APP_SETTINGS
            else -> AccessRequest.PERMISSION_DIALOG
        }

        /**
         * 把内容为 [text] 的 [src] 搬进 [to]：复制、fsync、读回校验后才删原文件，返回新文件；校验失败返回 null，原文件不动。
         * 目标里已有同名文件时改名另存，不覆盖；但内容完全相同时直接删原文件——
         * 收回权限期间自动同步会把文档下载到私有目录，重新授权时每篇都会多出一份“标题 2”，再被同步推到其他设备。
         */
        internal fun moveVerified(src: File, text: String, to: File): File? {
            val same = File(to, src.name)
            if (same.isFile && same.readText() == text) { src.delete(); return same }
            val dst = File(to, Titles.uniqueFileName(src.nameWithoutExtension) { File(to, it).exists() })
            FileOutputStream(dst).use { it.write(text.toByteArray()); it.fd.sync() }
            if (dst.readText() != text) return null
            dst.setLastModified(src.lastModified())
            src.delete()
            return dst
        }

        fun publicDir() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "momoda")

        fun hasPublicAccess(context: Context): Boolean =
            if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
            else context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }
}

/**
 * 记住我们最后一次读写每个文件时的修改时间和内容，判断它是不是被别的程序改过。
 * 修改时间对不上时再比内容：内容没变就不算（修改时间可能因为文件系统、同步写回同样的内容而变），
 * 免得凭空多出一篇“冲突”副本。纯逻辑，单独测试。
 */
class KnownFiles {
    private class Seen(var mtime: Long, val size: Int, val hash: Int)
    private val seen = HashMap<String, Seen>()

    fun remember(path: String, mtime: Long, text: String) {
        seen[path] = Seen(mtime, text.length, text.hashCode())
    }

    /** 文件改名：记录跟着走（改名不改内容）。 */
    fun move(from: String, to: String, mtime: Long) {
        seen.remove(from)?.let { seen[to] = it.also { s -> s.mtime = mtime } }
    }

    /** 没读写过的文件不算被改过；删掉了算。[read] 只在修改时间对不上时才调用。 */
    fun changed(path: String, exists: Boolean, mtime: Long, read: () -> String): Boolean {
        val s = seen[path] ?: return false
        if (!exists) return true
        if (mtime == s.mtime) return false
        val text = runCatching(read).getOrNull() ?: return true
        if (text.length != s.size || text.hashCode() != s.hash) return true
        s.mtime = mtime
        return false
    }
}
