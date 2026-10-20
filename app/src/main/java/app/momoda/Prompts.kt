package app.momoda

/**
 * AI 指令表：`Documents/momoda/prompts.md`，用户可以自己编辑（以后也可以由 agent 通过 GitHub 修改）。
 *
 * 格式：每个 `## 名称` 是一条指令，下面的文字是给模型的要求。
 * - `{{text}}` 换成要处理的文字（选区，或者光标所在的段落）；不写时自动附在最后。
 * - `{{context}}` 换成光标之前的上文（最多 2000 字），适合续写。
 * - 可选的一行 `模式：插入` 表示结果默认插到后面，而不是替换原文。
 */
object Prompts {

    data class Prompt(val name: String, val template: String, val insert: Boolean)

    const val FILE_NAME = "prompts.md"

    val DEFAULT = """
# 墨墨打的 AI 指令

每个“## 名称”是一条指令。{{text}} 是选中的文字（没有选区时是光标所在的段落），{{context}} 是光标之前的上文。
写上一行“模式：插入”表示结果插到后面，不替换原文。

## 润色
在不改变原意、语气和人称的前提下润色下面的文字：让句子更通顺，去掉重复和赘词，标点规范。只输出修改后的文字。

{{text}}

## 续写
模式：插入
接着下面的上文往下写一段，大约一百到两百字，保持原来的文风、人称和节奏。只输出续写的内容，不要重复上文。

{{context}}

## 精简
把下面的文字压缩到原来的一半左右，保留关键信息和原来的语气。只输出结果。

{{text}}

## 改写得更口语
把下面的文字改写得更口语、更自然，像是在和朋友聊天。只输出结果。

{{text}}

## 翻译成英文
把下面的文字翻译成自然、地道的英文。只输出译文。

{{text}}
""".trimStart()

    /** 写给模型的通用要求，放在 system 消息里。 */
    const val SYSTEM = "你是一位中文写作助手。严格按照要求处理文字，只输出结果本身：不要解释，不要加引号或前后缀，保留原文的 Markdown 格式。"

    fun parse(markdown: String): List<Prompt> {
        val out = mutableListOf<Prompt>()
        var name: String? = null
        val body = StringBuilder()
        fun flush() {
            val n = name ?: return
            var text = body.toString().trim()
            var insert = false
            val modeLine = Regex("^模式[:：]\\s*(插入|替换)\\s*$", RegexOption.MULTILINE).find(text)
            if (modeLine != null) {
                insert = modeLine.groupValues[1] == "插入"
                text = text.removeRange(modeLine.range).trim()
            }
            if (text.isNotEmpty()) out += Prompt(n, text, insert)
        }
        for (line in markdown.lines()) {
            if (line.startsWith("## ")) {
                flush()
                name = line.removePrefix("## ").trim()
                body.clear()
            } else if (name != null) {
                body.appendLine(line)
            }
        }
        flush()
        return out
    }

    /** 把模板里的占位符换成实际文字；没有 {{text}} 和 {{context}} 时把文字附在最后。 */
    fun render(p: Prompt, text: String, context: String): String {
        var t = p.template
        val hasPlaceholder = t.contains("{{text}}") || t.contains("{{context}}")
        t = t.replace("{{text}}", text).replace("{{context}}", context)
        return if (hasPlaceholder) t else "$t\n\n$text"
    }
}
