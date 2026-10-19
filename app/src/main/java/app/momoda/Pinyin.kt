package app.momoda

import android.content.res.AssetManager
import android.os.SystemClock
import android.util.Log

/**
 * 单个汉字的无声调拼音，用于搜索。数据是随 App 打包的 assets/pinyin.txt
 * （由 scripts/gen-pinyin.py 从 Unicode Unihan 生成，约 2.7 万字，含多音字的其他读音，95 KB）。
 *
 * 不用系统 ICU 的 Han-Latin 转换器：它第一次创建在汉王上要 8.6 秒，
 * 在主线程上会触发 ANR，放到后台也会拖慢冷启动。
 * 这里在启动时用后台线程读表（几十毫秒）；读完之前搜索只按原文匹配，绝不阻塞。
 */
object Pinyin {

    private class Table(val primary: Map<Char, String>, val all: Map<Char, List<String>>)

    @Volatile private var table: Table? = null

    fun warmUp(assets: AssetManager) {
        if (table != null) return
        Thread({
            val t0 = SystemClock.uptimeMillis()
            val primary = HashMap<Char, String>(32_768)
            val all = HashMap<Char, MutableList<String>>(32_768)
            runCatching {
                var alternates = false
                assets.open("pinyin.txt").bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (line == "#alt") { alternates = true; continue }
                        if (line.isEmpty() || line[0] == '#') continue
                        val sp = line.indexOf(' ')
                        val syllable = line.substring(0, sp)
                        for (i in sp + 1 until line.length) {
                            val ch = line[i]
                            if (!alternates) primary[ch] = syllable
                            all.getOrPut(ch) { ArrayList(2) }.add(syllable)
                        }
                    }
                }
            }.onFailure { Log.w("momoda", "pinyin table", it) }
            table = Table(primary, all)
            Log.i("momoda", "pinyin table ${primary.size} chars in ${SystemClock.uptimeMillis() - t0}ms")
        }, "pinyin-load").apply { isDaemon = true }.start()
    }

    /** 主读音（用于按标题排序）。 */
    fun of(ch: Char): String? = table?.primary?.get(ch)

    /** 所有读音，常用的在前（用于搜索，多音字每个读音都能搜到）。 */
    fun readings(ch: Char): List<String>? = table?.all?.get(ch)

    private val withPinyin by lazy { FuzzyMatcher(::readings) }
    private val plainOnly = FuzzyMatcher { null }

    /** 读表完成前返回只按原文匹配的版本，避免把“没有拼音”的索引缓存下来。 */
    val matcher: FuzzyMatcher get() = if (table != null) withPinyin else plainOnly
}
