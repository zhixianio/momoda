package app.momoda

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文档就是一个目录里的 .md 文件。
 * M0 放在 /sdcard/Android/data/app.momoda/files/docs，用来验证 USB 能否直接拷出。
 */
class DocStore(context: Context) {

    val dir: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "docs").apply { mkdirs() }
    private val trash = File(dir, ".trash")
    private val prefs = context.getSharedPreferences("docs", Context.MODE_PRIVATE)

    fun list(): List<File> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".md") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    fun create(): File {
        val stamp = SimpleDateFormat("yyyy-MM-dd HHmmss", Locale.CHINA).format(Date())
        return File(dir, "$stamp.md").apply { if (!exists()) writeText("") }
    }

    fun read(file: File): String = if (file.exists()) file.readText() else ""

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
    }

    fun moveToTrash(file: File) {
        trash.mkdirs()
        file.renameTo(File(trash, file.name))
    }

    var lastDocName: String?
        get() = prefs.getString("last", null)
        set(v) = prefs.edit().putString("last", v).apply()

    fun cursorOf(file: File): Int = prefs.getInt("cursor:${file.name}", -1)

    fun saveCursor(file: File, offset: Int) = prefs.edit().putInt("cursor:${file.name}", offset).apply()

    fun openLastOrCreate(): File =
        lastDocName?.let { File(dir, it) }?.takeIf { it.exists() }
            ?: list().firstOrNull()
            ?: create()
}
