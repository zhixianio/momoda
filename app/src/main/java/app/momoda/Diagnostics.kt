package app.momoda

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 不联网的诊断记录（发布准备会话 RELEASE.md §5.5）：
 * - 崩溃时把调用栈写进 App 私有目录，并尽量先把当前文档存一下；
 * - 启动时读取系统记录的上次退出原因（崩溃、ANR、被杀），只保留最近几条；
 * - 命令面板“导出诊断信息”把这些连同版本、设备、设置写成一个文本文件，放在文档目录里，
 *   用户可以自己拷出来发给开发者。不包含任何文档内容。
 *
 * 异常信息（message）可能带着正文片段（例如解析失败的数字、AI 返回的 JSON），只记异常类名和调用栈。
 */
object Diagnostics {

    private const val MAX_FILES = 10
    private val stamp get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())

    private fun dir(context: Context) = File(context.filesDir, "diagnostics").apply { mkdirs() }

    /** 安装崩溃处理器；[emergencySave] 在写崩溃日志之前调用，尽量不丢字。 */
    fun install(context: Context, emergencySave: () -> Unit) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val app = context.applicationContext
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { emergencySave() }
            runCatching {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                write(app, "crash", "线程：${thread.name}\n时间：$stamp\n\n${redact(sw.toString())}")
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** 读取系统记录的上次退出原因（Android 11+），记下新出现的异常退出。 */
    fun recordExitReasons(context: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        runCatching {
            val am = context.getSystemService(ActivityManager::class.java)
            val prefs = context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)
            val seen = prefs.getLong("lastExitSeen", 0)
            val reasons = am.getHistoricalProcessExitReasons(context.packageName, 0, 5)
                .filter { it.timestamp > seen }
            for (r in reasons) {
                val kind = when (r.reason) {
                    ApplicationExitInfo.REASON_ANR -> "anr"
                    ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash-system"
                    ApplicationExitInfo.REASON_LOW_MEMORY -> "lowmem"
                    else -> continue
                }
                // 只留 ANR 的线程栈；崩溃的描述里有异常信息，native 崩溃的记录里有内存内容，都可能带着正文
                val trace = if (kind != "anr") null
                    else runCatching { r.traceInputStream?.bufferedReader()?.use { it.readText().take(20_000) } }.getOrNull()
                val reason = if (kind == "anr") r.description ?: "${r.reason}" else (r.description?.substringBefore(':') ?: "${r.reason}")
                write(context, kind, "时间：${Date(r.timestamp)}\n原因：$reason\n\n${trace ?: ""}")
            }
            reasons.maxOfOrNull { it.timestamp }?.let { prefs.edit().putLong("lastExitSeen", it).apply() }
        }.onFailure { Log.w("momoda", "读取退出原因失败", it) }
    }

    private fun write(context: Context, kind: String, body: String) {
        val d = dir(context)
        File(d, "$kind-${System.currentTimeMillis()}.txt").writeText(body)
        d.listFiles()?.sortedByDescending { it.lastModified() }?.drop(MAX_FILES)?.forEach { it.delete() }
    }

    /** 写出诊断报告，返回文件。[settings] 是设置的文字描述。 */
    fun export(context: Context, outDir: File, settings: String): File {
        val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
        val report = buildString {
            appendLine("墨墨打诊断信息（不含任何文档内容）")
            appendLine("生成时间：$stamp")
            appendLine("版本：${pkg.versionName}（${if (Build.VERSION.SDK_INT >= 28) pkg.longVersionCode else 0}）")
            appendLine("设备：${Build.MANUFACTURER} ${Build.MODEL}，Android ${Build.VERSION.RELEASE}（SDK ${Build.VERSION.SDK_INT}）")
            appendLine("系统版本：${Build.DISPLAY}")
            appendLine()
            appendLine("设置：")
            appendLine(settings)
            val logs = dir(context).listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()
            appendLine()
            appendLine("最近的异常记录（${logs.size} 条）：")
            for (f in logs) {
                appendLine("──── ${f.name}")
                // 0.9.0-beta.1 写的崩溃记录里还有异常信息，导出时一并隐去
                appendLine(if (f.name.startsWith("crash-")) redact(f.readText()) else f.readText())
            }
        }
        val name = "momoda-诊断-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.CHINA).format(Date())}.txt"
        return File(outDir, name).apply { writeText(report) }
    }

    private val frame = Regex("^\\s+(at [\\w$.<>/@-]+\\(.*\\)|\\.\\.\\. \\d+ more)$")
    private val header = Regex("^(\\s*(?:Caused by: |Suppressed: )?)([A-Za-z_$][\\w$]*(?:\\.[\\w$]+)*(?:Exception|Error|Throwable))(:.*)?$")

    /**
     * 调用栈去掉异常信息：保留调用栈行、异常类名和我们自己写的“线程：”“时间：”，
     * 其余的行（异常信息，包括它的续行）都去掉。
     */
    fun redact(trace: String): String = trace.lines().mapNotNull { line ->
        when {
            line.isBlank() || line.startsWith("线程：") || line.startsWith("时间：") || line.startsWith("原因：") -> line
            frame.matches(line) -> line
            else -> header.matchEntire(line)?.let { m ->
                m.groupValues[1] + m.groupValues[2] + if (m.groupValues[3].isEmpty()) "" else ": （异常信息已隐去）"
            }
        }
    }.joinToString("\n")
}
