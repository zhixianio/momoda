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
 * 文档就是一个目录里的 .md 文件（DESIGN §4）。
 * 有“所有文件访问”权限时放在 Documents/momoda/，卸载 App 也不会丢；
 * 没有权限时先放在 App 私有目录，保证“即开即打”，授权后自动迁移过去。
 */
class DocStore(private val context: Context) {

    private val prefs = context.getSharedPreferences("docs", Context.MODE_PRIVATE)
    private val privateDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "docs")

    var dir: File = resolveDir()
        private set

    val isPublic: Boolean get() = dir == publicDir()
    private val trash get() = File(dir, ".trash")

    fun list(): List<File> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".md") && !f.name.startsWith(".") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    /** 新文档：以日期命名，内容是一个空的一级标题（DESIGN §1）。 */
    fun create(): File {
        val name = Titles.uniqueFileName(today()) { File(dir, it).exists() }
        return File(dir, name).apply { writeText("# ") }
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
        val name = Titles.uniqueFileName("${file.nameWithoutExtension} 冲突 $stamp") { File(dir, it).exists() }
        val copy = File(dir, name)
        write(copy, text)
        return copy
    }

    /** 先写临时文件再 rename，并 fsync，断电也不会留下半个文件。 */
    fun write(file: File, text: String) {
        val tmp = File(dir, ".${file.name}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray())
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            file.writeText(text)
            tmp.delete()
        }
        known.remember(file.absolutePath, file.lastModified(), text)
    }

    /**
     * 让文件名跟随标题（离开文档时调用，打字过程中不改）。
     * 没有标题时保持原名；已经是“标题 2.md”这种带序号的名字时也不再变动。
     */
    fun syncNameWithTitle(file: File, text: String): File {
        // AI 指令表的文件名是固定的，不能跟着标题改名
        if (file.name == Prompts.FILE_NAME) return file
        val current = file.nameWithoutExtension
        val desired = Titles.baseNameFor(Titles.titleOf(text), current)
        if (current == desired || current.matches(Regex(Regex.escape(desired) + " \\d+"))) return file
        val name = Titles.uniqueFileName(desired) { File(dir, it).exists() }
        return rename(file, name)
    }

    fun isEmptyDoc(text: String) = text.isBlank() || text.trim() == "#"

    fun delete(file: File) {
        file.delete()
        forget(file)
    }

    fun moveToTrash(file: File) {
        trash.mkdirs()
        val name = Titles.uniqueFileName(file.nameWithoutExtension) { File(trash, it).exists() }
        file.renameTo(File(trash, name))
        forget(file)
    }

    // ---------- 回收站 ----------

    fun trashList(): List<File> =
        trash.listFiles { f -> f.isFile && f.name.endsWith(".md") }?.sortedByDescending { it.lastModified() }.orEmpty()

    /** 从回收站恢复；原名被占用时自动加序号。 */
    fun restore(file: File): File {
        val name = Titles.uniqueFileName(file.nameWithoutExtension) { File(dir, it).exists() }
        val target = File(dir, name)
        return if (file.renameTo(target)) target else file
    }

    /** 从回收站彻底删除。 */
    fun purge(file: File) {
        if (file.parentFile == trash) file.delete()
    }

    /** 修改某篇文档的标题（改首行），文件名随之更新。 */
    fun retitle(file: File, newTitle: String): File {
        val text = read(file)
        write(file, Titles.withTitle(text, newTitle))
        return syncNameWithTitle(file, read(file))
    }

    private fun rename(file: File, newName: String): File {
        val target = File(dir, newName)
        if (!file.renameTo(target)) return file
        known.move(file.absolutePath, target.absolutePath, target.lastModified())
        val cursor = cursorOf(file)
        forget(file)
        if (cursor >= 0) saveCursor(target, cursor)
        if (lastDocName == file.name) lastDocName = target.name
        if (previousDocName == file.name) previousDocName = target.name
        return target
    }

    private fun forget(file: File) {
        prefs.edit().remove("cursor:${file.name}").apply()
    }

    // ---------- 打开记录 ----------

    var lastDocName: String?
        get() = prefs.getString("last", null)
        set(v) = prefs.edit().putString("last", v).apply()

    /** Ctrl+Tab 切回的上一篇。 */
    var previousDocName: String?
        get() = prefs.getString("previous", null)
        set(v) = prefs.edit().putString("previous", v).apply()

    fun cursorOf(file: File): Int = prefs.getInt("cursor:${file.name}", -1)

    fun saveCursor(file: File, offset: Int) = prefs.edit().putInt("cursor:${file.name}", offset).apply()

    /** 最近修改的一篇正文（不算 AI 指令文件 prompts.md）。 */
    fun mostRecent(): File? = list().firstOrNull { it.name != Prompts.FILE_NAME }

    fun openLastOrCreate(): File {
        val file = lastDocName?.let { File(dir, it) }?.takeIf { it.exists() }
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

    /** 逐个搬过去（见 [moveVerified]）。 */
    private fun migrate(from: File, to: File) {
        val files = from.listFiles { f -> f.isFile && f.name.endsWith(".md") && !f.name.startsWith(".") }.orEmpty()
        // 重装 App 时 Documents/momoda 里已经有文档：没改过的欢迎文档不再搬过去
        val targetHasDocs = to.listFiles { f -> f.isFile && f.name.endsWith(".md") && !f.name.startsWith(".") }.orEmpty().isNotEmpty()
        for (src in files) {
            val text = src.readText()
            if (isEmptyDoc(text) || (targetHasDocs && text == welcomeText)) { src.delete(); continue }
            val cursor = cursorOf(src)
            val dst = moveVerified(src, text, to) ?: continue
            if (cursor >= 0) saveCursor(dst, cursor)
            if (lastDocName == src.name) lastDocName = dst.name
            if (previousDocName == src.name) previousDocName = dst.name
            Log.i("momoda", "migrated ${src.name} → ${dst.absolutePath}")
        }
    }

    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())

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
