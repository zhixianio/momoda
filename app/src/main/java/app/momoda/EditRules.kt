package app.momoda

/** 把 [start, end) 替换成 [text]，然后把光标放到 [cursor]。 */
data class Edit(val start: Int, val end: Int, val text: String, val cursor: Int)

/**
 * 分段与列表规则（DESIGN §2.3）。纯逻辑：输入是“已经发生的一次编辑”之后的全文，输出补充编辑。
 * - 回车写入空行：单个 `\n` 变成 `\n\n`，保证在任何 Markdown 渲染器里都是真正分段。
 * - Shift+Enter 是单换行（soft = true）。
 * - 列表和引用自动延续；在空的列表项上回车则退出列表。
 * - 退格删掉段落分隔中的一个换行时，连带删掉另一个，两段直接合并。
 */
object EditRules {

    private val listItem = Regex("^(\\s*)([-*+]|\\d+[.)]|>)(\\s+)(.*)$")

    /** 刚在 [pos] 插入了一个 `\n`。 */
    fun afterNewline(text: CharSequence, pos: Int, soft: Boolean): Edit? {
        val lineStart = lineStartBefore(text, pos)
        val line = text.subSequence(lineStart, pos).toString()

        listItem.find(line)?.let { m ->
            val (indent, marker, gap, content) = m.destructured
            if (content.isBlank()) {
                // 空列表项上回车：退出列表，留下一个空行作为段落分隔
                return Edit(lineStart, pos + 1, "\n", lineStart + 1)
            }
            val next = when {
                marker.first().isDigit() -> {
                    val n = marker.dropLast(1).toInt()
                    "${n + 1}${marker.last()}"
                }
                else -> marker
            }
            val insert = "$indent$next$gap"
            return Edit(pos + 1, pos + 1, insert, pos + 1 + insert.length)
        }

        if (soft) return null
        // 在空行上回车不再加倍，避免段距越来越大
        if (line.isBlank()) return null
        return Edit(pos + 1, pos + 1, "\n", pos + 2)
    }

    /** 刚删掉了原本位于 [pos] 的一个 `\n`（删除后 [pos] 处是原来它后面的字符）。 */
    fun afterNewlineDeleted(text: CharSequence, pos: Int): Edit? {
        fun isNl(i: Int) = i in text.indices && text[i] == '\n'
        fun isText(i: Int) = i in text.indices && text[i] != '\n'
        // 只有“文字\n\n文字”被删成“文字\n文字”时才合并；连续多个空行时只删一个，不连带。
        // 退格删的是后一个换行：剩下的那个在 pos-1
        if (isNl(pos - 1) && isText(pos - 2) && isText(pos)) return Edit(pos - 1, pos, "", pos - 1)
        // 向前删除（Delete 键）删的是前一个换行：剩下的那个在 pos
        if (isNl(pos) && isText(pos - 1) && isText(pos + 1)) return Edit(pos, pos + 1, "", pos)
        return null
    }

    private fun lineStartBefore(text: CharSequence, pos: Int): Int {
        var i = pos - 1
        while (i >= 0 && text[i] != '\n') i--
        return i + 1
    }
}
