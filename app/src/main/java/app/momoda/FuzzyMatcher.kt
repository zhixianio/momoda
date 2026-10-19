package app.momoda

/**
 * 命令面板和文档切换的搜索：支持原文、拼音首字母、全拼和首字母子序列。
 * 例如 `xj` 匹配“新建文档”，`xinj` 也匹配，`zen` 匹配“Zen 模式”。
 * [pinyinOf] 返回单个汉字的无声调拼音（小写），非汉字返回 null。
 */
class FuzzyMatcher(private val pinyinOf: (Char) -> String?) {

    private class Index(val plain: String, val full: String, val initials: String)

    private val cache = HashMap<String, Index>()

    /** 越小越靠前；null 表示不匹配。空查询匹配一切。 */
    fun score(query: String, item: String): Int? {
        val q = query.lowercase().filterNot { it.isWhitespace() }
        if (q.isEmpty()) return 0
        val idx = cache.getOrPut(item) { index(item) }
        return when {
            idx.plain.startsWith(q) -> 0
            idx.plain.contains(q) -> 1
            idx.initials.startsWith(q) -> 2
            idx.initials.contains(q) -> 3
            idx.full.contains(q) -> 4
            isSubsequence(q, idx.initials) -> 5
            else -> null
        }
    }

    fun <T> filter(query: String, items: List<T>, label: (T) -> String): List<T> =
        items.mapIndexedNotNull { i, it -> score(query, label(it))?.let { s -> Triple(s, i, it) } }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .map { it.third }

    private fun index(item: String): Index {
        val plain = item.lowercase().filterNot { it.isWhitespace() }
        val full = StringBuilder()
        val initials = StringBuilder()
        for (ch in item) {
            val py = pinyinOf(ch)
            when {
                py != null && py.isNotEmpty() -> { full.append(py); initials.append(py[0]) }
                ch.isLetterOrDigit() -> { val c = ch.lowercaseChar(); full.append(c); initials.append(c) }
            }
        }
        return Index(plain, full.toString(), initials.toString())
    }

    private fun isSubsequence(q: String, s: String): Boolean {
        var i = 0
        for (c in s) if (i < q.length && c == q[i]) i++
        return i == q.length
    }
}
