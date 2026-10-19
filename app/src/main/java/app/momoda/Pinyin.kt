package app.momoda

import android.content.res.AssetManager
import android.os.SystemClock
import android.util.Log

/**
 * 单个汉字的无声调拼音，用于搜索。数据是随 App 打包的 assets/pinyin.txt
 * （由 scripts/gen-pinyin.py 从 Unicode Unihan 生成，约 2.7 万字，80 KB）。
 *
 * 不用系统 ICU 的 Han-Latin 转换器：它第一次创建在汉王上要 8.6 秒，
 * 在主线程上会触发 ANR，放到后台也会拖慢冷启动。
 * 这里在启动时用后台线程读表（几十毫秒）；读完之前搜索只按原文匹配，绝不阻塞。
 */
object Pinyin {

    @Volatile private var table: Map<Char, String>? = null

    fun warmUp(assets: AssetManager) {
        if (table != null) return
        Thread({
            val t0 = SystemClock.uptimeMillis()
            val map = HashMap<Char, String>(32_768)
            runCatching {
                assets.open("pinyin.txt").bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (line.isEmpty() || line[0] == '#') continue
                        val sp = line.indexOf(' ')
                        val syllable = line.substring(0, sp)
                        for (i in sp + 1 until line.length) map[line[i]] = syllable
                    }
                }
            }.onFailure { Log.w("momoda", "pinyin table", it) }
            table = map
            Log.i("momoda", "pinyin table ${map.size} chars in ${SystemClock.uptimeMillis() - t0}ms")
        }, "pinyin-load").apply { isDaemon = true }.start()
    }

    fun of(ch: Char): String? = table?.get(ch)

    private val withPinyin by lazy { FuzzyMatcher(::of) }
    private val plainOnly = FuzzyMatcher { null }

    /** 读表完成前返回只按原文匹配的版本，避免把“没有拼音”的索引缓存下来。 */
    val matcher: FuzzyMatcher get() = if (table != null) withPinyin else plainOnly
}
