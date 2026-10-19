package app.momoda

import android.icu.text.Transliterator
import android.os.Build

/** 单个汉字的无声调拼音，用于搜索（系统 ICU，Android 10+）。 */
object Pinyin {

    private val transliterator: Transliterator? =
        if (Build.VERSION.SDK_INT >= 29) runCatching { Transliterator.getInstance("Han-Latin; Latin-ASCII; Lower") }.getOrNull()
        else null

    private val cache = HashMap<Char, String?>()

    fun of(ch: Char): String? {
        if (Character.UnicodeScript.of(ch.code) != Character.UnicodeScript.HAN) return null
        return cache.getOrPut(ch) {
            transliterator?.transliterate(ch.toString())?.trim()?.filter { it in 'a'..'z' }?.ifEmpty { null }
        }
    }

    val matcher by lazy { FuzzyMatcher(::of) }
}
