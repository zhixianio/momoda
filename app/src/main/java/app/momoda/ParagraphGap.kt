package app.momoda

/**
 * 段落分隔的全部规则（DESIGN §2.3），Android 和 Mac 共用，纯逻辑。
 *
 * 文件里段落之间是一个空行（`\n\n`），编辑器里原样保存、不做转换：文件永远是标准 Markdown，
 * 和 GitHub、别的应用、同步时逐字节比较看到的完全一样。屏幕上：
 * - **段距行**（紧跟在文字行后面的第一个空行，内容只有一个 `\n`）显示成段距，大小由各端的“段距”设置决定。
 *   Android 把这一行压到段距的高度（见 EditorView.ParagraphSpacing 为什么不加在下一段上）；
 *   Mac 可以让它不占高度，改用下一段第一行（[followsGap]）的段前距。两种画法看起来一样；
 * - 光标不停在段距行上（[snap]），上下移动时跳过它；
 * - 回车写入整个段落分隔（[afterEnter]），退格一次删掉整个段落分隔（[afterNewlineDeleted]）。
 *
 * 段距行之后连续的空行是用户有意留的空白，照常一整行高，光标可以停。
 * 一行算不算段距行只看它前面的两个字符，所以重排时只需带上这一行和下一段的第一行。
 */
object ParagraphGap {

    /** [i] 是不是段距行（那个 `\n`）的位置。光标在 [i] 就是停在段距行上。 */
    fun isGapStart(t: CharSequence, i: Int): Boolean =
        i in 2 until t.length && t[i] == '\n' && t[i - 1] == '\n' && t[i - 2] != '\n'

    /** [start, end) 这一行（[end] 含行尾的 `\n`）是不是段距行。 */
    fun isGapLine(t: CharSequence, start: Int, end: Int): Boolean = end - start == 1 && isGapStart(t, start)

    /** 从 [start] 开始的这一行紧跟在段距行后面，也就是下一段的第一行：段距加在它上方。 */
    fun followsGap(t: CharSequence, start: Int): Boolean = isGapStart(t, start - 1)

    /**
     * 光标不停在段距行上。落到上面时按移动方向挪开：往前（[forward] 为 false，往上、往左、退格）到上一段末尾，
     * 往后到下一段开头。不在段距行上原样返回。
     */
    fun snap(t: CharSequence, pos: Int, forward: Boolean): Int =
        if (isGapStart(t, pos)) (if (forward) pos + 1 else pos - 1) else pos

    /**
     * 刚在 [pos] 插入了一个 `\n`（普通回车，不是 Shift+Enter，也不在列表里）：再补一个，成为段落分隔。
     * 在空行上回车不再加倍，否则段距会越来越大。
     */
    fun afterEnter(text: CharSequence, pos: Int): Edit? {
        var lineStart = pos - 1
        while (lineStart >= 0 && text[lineStart] != '\n') lineStart--
        if (text.subSequence(lineStart + 1, pos).isBlank()) return null
        return Edit(pos + 1, pos + 1, "\n", pos + 2)
    }

    /** 刚删掉了原本位于 [pos] 的一个 `\n`（删除后 [pos] 处是原来它后面的字符）：段落分隔一次删完。 */
    fun afterNewlineDeleted(text: CharSequence, pos: Int): Edit? {
        fun isNl(i: Int) = i in text.indices && text[i] == '\n'
        fun isText(i: Int) = i in text.indices && text[i] != '\n'
        // 只有“文字\n\n文字”被删成“文字\n文字”时才合并；连续多个空行时只删一个，不连带。
        // 退格删的是后一个换行：剩下的那个在 pos-1
        if (isNl(pos - 1) && isText(pos - 2) && isText(pos)) return Edit(pos - 1, pos, "", pos - 1)
        // 刚回车完又退格（回车写入的是 \n\n）：在文末，或者后面紧跟着下一段的段落分隔。
        // 只删一个的话，光标会落在段距行上，要退两次才回到上一行末尾
        if (isNl(pos - 1) && isText(pos - 2) && (pos == text.length || (isNl(pos) && isNl(pos + 1) && isText(pos + 2)))) {
            return Edit(pos - 1, pos, "", pos - 1)
        }
        // 向前删除（Delete 键）删的是前一个换行：剩下的那个在 pos
        if (isNl(pos) && isText(pos - 1) && isText(pos + 1)) return Edit(pos, pos + 1, "", pos)
        return null
    }
}
