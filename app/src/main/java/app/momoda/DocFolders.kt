package app.momoda

/**
 * 文档管理页按文件夹浏览：某个文件夹下面有哪些子文件夹、各有几篇。纯逻辑，输入是文档的相对路径（[DocStore.key]）。
 */
object DocFolders {

    class Sub(val path: String, val name: String, val docs: Int, val latest: Long)

    /** [folder] 的上一层；根目录返回 null。 */
    fun parent(folder: String): String? = if (folder.isEmpty()) null else folder.substringBeforeLast('/', "")

    /** 文档所在的文件夹。 */
    fun folderOf(key: String): String = key.substringBeforeLast('/', "")

    /** [folder] 下一层的子文件夹（含更深处的文档数），最近修改的在前。[modified] 给出每篇文档的修改时间。 */
    fun children(keys: List<String>, folder: String, modified: (String) -> Long): List<Sub> {
        val prefix = if (folder.isEmpty()) "" else "$folder/"
        val groups = keys.filter { it.startsWith(prefix) && it.indexOf('/', prefix.length) >= 0 }
            .groupBy { it.substring(prefix.length).substringBefore('/') }
        return groups.map { (name, docs) -> Sub(prefix + name, name, docs.size, docs.maxOf(modified)) }
            .sortedByDescending { it.latest }
    }

    /** 直接放在 [folder] 里的文档。 */
    fun docsIn(keys: List<String>, folder: String): List<String> = keys.filter { folderOf(it) == folder }
}
