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

    fun read(file: File): String {
        if (!file.exists()) return ""
        val text = file.readText()
        knownMtime[file.absolutePath] = file.lastModified()
        return text
    }

    /** 我们最后一次读或写某个文件时它的修改时间，用来发现“被其他程序改过”。 */
    private val knownMtime = HashMap<String, Long>()

    /** 文件在我们上次读写之后被别的程序改过（或删掉了）。 */
    fun changedOnDisk(file: File): Boolean {
        val known = knownMtime[file.absolutePath] ?: return false
        return !file.exists() || file.lastModified() != known
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
        knownMtime[file.absolutePath] = file.lastModified()
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
        knownMtime.remove(file.absolutePath)?.let { knownMtime[target.absolutePath] = target.lastModified() }
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

    fun openLastOrCreate(): File =
        lastDocName?.let { File(dir, it) }?.takeIf { it.exists() }
            ?: list().firstOrNull()
            ?: create()

    // ---------- 存储位置 ----------

    var askedForAccess: Boolean
        get() = prefs.getBoolean("askedAccess", false)
        set(v) = prefs.edit().putBoolean("askedAccess", v).apply()

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

    /** 逐个复制并校验后再删除原文件；目标里已有同名文件时改名，不覆盖。 */
    private fun migrate(from: File, to: File) {
        val files = from.listFiles { f -> f.isFile && f.name.endsWith(".md") && !f.name.startsWith(".") }.orEmpty()
        for (src in files) {
            val text = src.readText()
            if (isEmptyDoc(text)) { src.delete(); continue }
            val name = Titles.uniqueFileName(src.nameWithoutExtension) { File(to, it).exists() }
            val dst = File(to, name)
            FileOutputStream(dst).use { it.write(text.toByteArray()); it.fd.sync() }
            if (dst.readText() == text) {
                dst.setLastModified(src.lastModified())
                val cursor = cursorOf(src)
                src.delete()
                if (cursor >= 0) saveCursor(dst, cursor)
                if (lastDocName == src.name) lastDocName = dst.name
                if (previousDocName == src.name) previousDocName = dst.name
                Log.i("momoda", "migrated ${src.name} → ${dst.absolutePath}")
            }
        }
    }

    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())

    companion object {
        fun publicDir() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "momoda")

        fun hasPublicAccess(context: Context): Boolean =
            if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
            else context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }
}
