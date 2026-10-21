package app.momoda

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Typeface
import android.util.Log
import java.io.File

/** 用户设置（DESIGN §3.3）。所有取值范围和默认值集中在这里。 */
class UserSettings(context: Context) {

    private val prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)

    var textSize: Float
        get() = prefs.getFloat("textSize", 20f)
        set(v) = prefs.edit().putFloat("textSize", v.coerceIn(14f, 36f)).apply()

    var lineSpacing: Float
        get() = prefs.getFloat("lineSpacing", 1.75f)
        set(v) = prefs.edit().putFloat("lineSpacing", v).apply()

    /** 段距：段落之间的空行占几分之一行高。 */
    var paragraphSpacing: Float
        get() = prefs.getFloat("paragraphSpacing", 0.25f)
        set(v) = prefs.edit().putFloat("paragraphSpacing", v).apply()

    var charsPerLine: Int
        get() = prefs.getInt("charsPerLine", 30)
        set(v) = prefs.edit().putInt("charsPerLine", v.coerceIn(24, 40)).apply()

    var indent: Boolean
        get() = prefs.getBoolean("indent", false)
        set(v) = prefs.edit().putBoolean("indent", v).apply()

    var chrome: Boolean
        get() = prefs.getBoolean("chrome", true)
        set(v) = prefs.edit().putBoolean("chrome", v).apply()

    var focus: Boolean
        get() = prefs.getBoolean("focus", false)
        set(v) = prefs.edit().putBoolean("focus", v).apply()

    var vim: Boolean
        get() = prefs.getBoolean("vim", false)
        set(v) = prefs.edit().putBoolean("vim", v).apply()

    /** 光标走到屏幕边缘时怎么翻：连续（一行一行）、半页、整页。 */
    /** 中文引号样式（见 [TypingRules.QuoteStyle]），默认换成弯引号。 */
    var quoteStyle: TypingRules.QuoteStyle
        get() = runCatching { TypingRules.QuoteStyle.valueOf(prefs.getString("quoteStyle", null) ?: "") }.getOrDefault(TypingRules.QuoteStyle.CURLY)
        set(v) = prefs.edit().putString("quoteStyle", v.name).apply()

    /** 中英文之间自动加空格。 */
    var autoSpace: Boolean
        get() = prefs.getBoolean("autoSpace", true)
        set(v) = prefs.edit().putBoolean("autoSpace", v).apply()

    /** 输入法候选窗的位置（见 [EditorView.CandidatePosition]）。 */
    var candidatePosition: EditorView.CandidatePosition
        get() = runCatching { EditorView.CandidatePosition.valueOf(prefs.getString("candidatePosition", null) ?: "") }.getOrDefault(EditorView.CandidatePosition.FOLLOW)
        set(v) = prefs.edit().putString("candidatePosition", v.name).apply()

    var scrollMode: EditorView.ScrollMode
        get() = runCatching { EditorView.ScrollMode.valueOf(prefs.getString("scrollMode", null) ?: "") }.getOrDefault(EditorView.ScrollMode.CONTINUOUS)
        set(v) = prefs.edit().putString("scrollMode", v.name).apply()


    var autoUpdate: Boolean
        get() = prefs.getBoolean("autoUpdate", true)
        set(v) = prefs.edit().putBoolean("autoUpdate", v).apply()

    /** 字体文件名；空字符串表示内置的霞鹜文楷屏幕阅读版。 */
    var font: String
        get() = prefs.getString("font", "")!!
        set(v) = prefs.edit().putString("font", v).apply()

    companion object {
        val LINE_SPACINGS = listOf(1.5f, 1.75f, 2.0f)
        val PARAGRAPH_SPACINGS = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
    }
}

/**
 * 字体：内置霞鹜文楷屏幕阅读版，另外把 .ttf/.otf 放进 Documents/momoda/fonts/ 就能选用（DESIGN §2.8）。
 * 字体文件很大，只加载当前选中的那一款，并缓存。
 */
class Fonts(private val assets: AssetManager, private val docsDir: () -> File) {

    data class Choice(val key: String, val label: String)

    val builtIn = Choice("", "霞鹜文楷")
    private val cache = HashMap<String, Typeface>()

    fun folder(): File = File(docsDir(), "fonts")

    fun available(): List<Choice> {
        val files = folder().listFiles { f -> f.isFile && f.extension.lowercase() in setOf("ttf", "otf", "ttc") }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
        return listOf(builtIn) + files.map { Choice(it.name, it.nameWithoutExtension) }
    }

    fun load(key: String): Typeface = cache.getOrPut(key) {
        if (key.isEmpty()) return@getOrPut Typeface.createFromAsset(assets, "fonts/LXGWWenKaiScreen.ttf")
        val file = File(folder(), key)
        runCatching { Typeface.createFromFile(file) }
            .onFailure { Log.w("momoda", "无法加载字体 $file", it) }
            .getOrNull() ?: load("")
    }
}
