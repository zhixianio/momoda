package app.momoda

/**
 * 命令面板和文档切换的搜索：支持原文、拼音首字母、全拼和首字母子序列，多音字的每个读音都算。
 * 例如 `xj` 匹配“新建文档”，`xinj` 也匹配，`cw` 和 `zw` 都匹配“长文”，`zen` 匹配“Zen 模式”。
 * [readingsOf] 返回单个汉字的所有无声调拼音（小写，常用的在前），非汉字返回 null。
 */
class FuzzyMatcher(private val readingsOf: (Char) -> List<String>?) {

    /** [units] 每个元素是一个字的候选读音；字母数字就是它本身。 */
    private class Index(val plain: String, val units: List<List<String>>)

    private val cache = HashMap<String, Index>()

    /** 越小越靠前；null 表示不匹配。空查询匹配一切。 */
    fun score(query: String, item: String): Int? {
        val q = query.lowercase().filterNot { it.isWhitespace() }
        if (q.isEmpty()) return 0
        val idx = cache.getOrPut(item) { index(item) }
        val u = idx.units
        return when {
            idx.plain.startsWith(q) -> 0
            idx.plain.contains(q) -> 1
            initialsAt(u, 0, q) -> 2
            u.indices.any { initialsAt(u, it, q) } -> 3
            u.indices.any { fullFrom(u, it, q) } -> 4
            initialsSubsequence(u, q) -> 5
            else -> null
        }
    }

    fun <T> filter(query: String, items: List<T>, label: (T) -> String): List<T> =
        items.mapIndexedNotNull { i, it -> score(query, label(it))?.let { s -> Triple(s, i, it) } }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .map { it.third }

    private fun index(item: String): Index {
        val units = mutableListOf<List<String>>()
        for (ch in item) {
            val readings = readingsOf(ch)
            when {
                !readings.isNullOrEmpty() -> units += readings
                ch.isLetterOrDigit() -> units += listOf(ch.lowercaseChar().toString())
            }
        }
        return Index(item.lowercase().filterNot { it.isWhitespace() }, units)
    }

    /** 从第 [i] 个字开始，连续每个字的某个读音首字母依次等于 [q] 的每个字母。 */
    private fun initialsAt(u: List<List<String>>, i: Int, q: String): Boolean {
        if (i + q.length > u.size) return false
        for (k in q.indices) if (u[i + k].none { it[0] == q[k] }) return false
        return true
    }

    /** 从第 [j] 个字开始，用每个字的某个读音拼出 [rest]；最后一个字可以只拼一部分（`xinj` → 新建）。 */
    private fun fullFrom(u: List<List<String>>, j: Int, rest: String): Boolean {
        if (rest.isEmpty()) return true
        if (j >= u.size) return false
        for (r in u[j]) {
            if (r.startsWith(rest)) return true
            if (rest.startsWith(r) && fullFrom(u, j + 1, rest.substring(r.length))) return true
        }
        return false
    }

    private fun initialsSubsequence(u: List<List<String>>, q: String): Boolean {
        var k = 0
        for (unit in u) if (k < q.length && unit.any { it[0] == q[k] }) k++
        return k == q.length
    }
}
