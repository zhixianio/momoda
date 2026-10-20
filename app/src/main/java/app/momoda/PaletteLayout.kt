package app.momoda

/**
 * 命令面板的排列：顶部“常用”按使用次数取前几条，下面按固定的分组和顺序列出全部命令。
 * 顺序固定，靠位置记忆就能找到；“常用”只在使用次数变化时才变。
 */
object PaletteLayout {

    sealed class Entry {
        data class Header(val title: String) : Entry()
        /** [searchable] 为 false 的是“常用”里的重复项：搜索时只匹配分组里的那一份，避免同一条出现两次。 */
        data class Item(val id: String, val searchable: Boolean = true) : Entry()
    }

    const val FREQUENT = "常用"

    /**
     * [neverFrequent]：不进“常用”的命令，例如删除。“常用”的第一条打开面板时默认选中，按一下 Enter 就会执行。
     */
    fun layout(
        groups: List<Pair<String, List<String>>>,
        uses: Map<String, Int>,
        top: Int = 3,
        neverFrequent: Set<String> = emptySet(),
    ): List<Entry> {
        val order = groups.flatMap { it.second }
        val frequent = order.filter { (uses[it] ?: 0) > 0 && it !in neverFrequent }
            .sortedWith(compareByDescending<String> { uses[it] ?: 0 }.thenBy { order.indexOf(it) })
            .take(top)
        return buildList {
            if (frequent.isNotEmpty()) {
                add(Entry.Header(FREQUENT))
                frequent.forEach { add(Entry.Item(it, searchable = false)) }
            }
            for ((title, ids) in groups) {
                if (ids.isEmpty()) continue
                add(Entry.Header(title))
                ids.forEach { add(Entry.Item(it)) }
            }
        }
    }

    /** 使用次数存成 `id:次数,id:次数`。 */
    fun parseUses(s: String): Map<String, Int> = s.split(',').mapNotNull {
        val (id, n) = it.split(':', limit = 2).takeIf { p -> p.size == 2 } ?: return@mapNotNull null
        n.toIntOrNull()?.let { c -> id to c }
    }.toMap()

    fun formatUses(uses: Map<String, Int>): String = uses.entries.joinToString(",") { "${it.key}:${it.value}" }
}
