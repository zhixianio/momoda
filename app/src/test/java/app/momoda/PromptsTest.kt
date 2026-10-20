package app.momoda

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptsTest {

    @Test fun defaultFileParses() {
        val ps = Prompts.parse(Prompts.DEFAULT)
        assertEquals(listOf("润色", "续写", "精简", "改写得更口语", "翻译成英文"), ps.map { it.name })
        assertTrue(ps.first { it.name == "续写" }.insert)
        assertFalse(ps.first { it.name == "润色" }.insert)
        assertFalse(ps.first { it.name == "续写" }.template.contains("模式"))
    }

    @Test fun introBeforeFirstSectionIsIgnored() =
        assertEquals(listOf("甲"), Prompts.parse("# 标题\n说明文字\n## 甲\n做点什么").map { it.name })

    @Test fun emptySectionIsSkipped() = assertEquals(listOf("乙"), Prompts.parse("## 甲\n\n## 乙\n内容").map { it.name })

    @Test fun renderReplacesPlaceholders() {
        val p = Prompts.Prompt("x", "改写：{{text}}\n上文：{{context}}", false)
        assertEquals("改写：正文\n上文：前面", Prompts.render(p, "正文", "前面"))
    }

    @Test fun renderAppendsWhenNoPlaceholder() =
        assertEquals("润色一下\n\n正文", Prompts.render(Prompts.Prompt("x", "润色一下", false), "正文", ""))

    @Test fun halfWidthColonModeLine() = assertTrue(Prompts.parse("## 续\n模式: 插入\n写").single().insert)

    @Test fun sseDelta() = assertEquals("你好", Llm.parseSseLine("""data: {"choices":[{"delta":{"content":"你好"}}]}"""))

    @Test fun sseDone() = assertEquals(Llm.DONE, Llm.parseSseLine("data: [DONE]"))

    @Test fun sseIgnoresComments() = assertNull(Llm.parseSseLine(": keep-alive"))

    @Test fun sseRoleOnlyDeltaIsNull() = assertNull(Llm.parseSseLine("""data: {"choices":[{"delta":{"role":"assistant"}}]}"""))
}
