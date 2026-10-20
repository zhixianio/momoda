package app.momoda

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 文档列表（Ctrl+O 切换器、文档管理页）里每篇的标题、字数和摘要。
 * 按（修改时间，大小）缓存：文档多、存储慢时，不必每次打开列表都在主线程把每篇全文读一遍。
 * 只读文件，不碰 [DocStore] 记的“上次读写时的修改时间”，可以在后台线程预热。
 */
class DocSummaries {

    class Summary(val title: String?, val words: Int, val excerpt: String)

    private class Entry(val modified: Long, val length: Long, val summary: Summary)

    private val cache = ConcurrentHashMap<String, Entry>()

    fun of(file: File): Summary {
        // 先取修改时间和大小再读内容：读的过程中文件变了，下次取到的修改时间不同，会重新读
        val modified = file.lastModified()
        val length = file.length()
        cache[file.absolutePath]?.let { if (it.modified == modified && it.length == length) return it.summary }
        val text = runCatching { file.readText() }.getOrDefault("")
        return summarize(text).also { cache[file.absolutePath] = Entry(modified, length, it) }
    }

    /** 后台预热：把还没缓存的都读一遍。 */
    fun warmUp(files: List<File>) {
        for (f in files) runCatching { of(f) }
    }

    companion object {
        fun summarize(text: String) = Summary(Titles.titleOf(text), TextStats.wordCount(text), Titles.excerptOf(text))
    }
}
