package app.momoda

/** 中文习惯的字数：一个汉字计 1，一个连续的西文单词或数字计 1，标点和空白不计。 */
object TextStats {

    fun wordCount(text: CharSequence): Int {
        var count = 0
        var inWord = false
        for (ch in text) {
            when {
                isCjk(ch) -> {
                    count++
                    inWord = false
                }
                ch.isLetterOrDigit() -> {
                    if (!inWord) count++
                    inWord = true
                }
                // 单词内部的撇号和连字符不打断单词：don't、e-ink
                inWord && (ch == '\'' || ch == '-' || ch == '’') -> Unit
                else -> inWord = false
            }
        }
        return count
    }

    fun isCjk(ch: Char): Boolean {
        val block = Character.UnicodeBlock.of(ch) ?: return false
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
            block == Character.UnicodeBlock.HIRAGANA ||
            block == Character.UnicodeBlock.KATAKANA ||
            block == Character.UnicodeBlock.HANGUL_SYLLABLES
    }

    fun format(n: Int): String = "%,d".format(n)
}
