package app.momoda

/** 把 [start, end) 替换成 [text]，然后把光标放到 [cursor]。 */
data class Edit(val start: Int, val end: Int, val text: String, val cursor: Int)

/**
 * 回车的规则（DESIGN §2.3）。纯逻辑：输入是“已经发生的一次编辑”之后的全文，输出补充编辑。
 * - 列表和引用自动延续；在空的列表项上回车则退出列表。
 * - 其他地方的回车写入段落分隔（`\n\n`），Shift+Enter 是单换行（soft = true）。段落分隔的规则都在 [ParagraphGap]。
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
                    val n = marker.dropLast(1).toBigInteger()
                    "${n + java.math.BigInteger.ONE}${marker.last()}"
                }
                else -> marker
            }
            val insert = "$indent$next$gap"
            return Edit(pos + 1, pos + 1, insert, pos + 1 + insert.length)
        }

        if (soft) return null
        return ParagraphGap.afterEnter(text, pos)
    }

    private fun lineStartBefore(text: CharSequence, pos: Int): Int {
        var i = pos - 1
        while (i >= 0 && text[i] != '\n') i--
        return i + 1
    }
}
