package app.momoda

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PasteServerTest {
    @Test fun parsesUrlEncodedForm() =
        assertEquals(mapOf("code" to "1234", "text" to "你好 世界&", "ai_url" to "http://100.1.2.3:11434/v1"),
            PasteServer.parseForm("code=1234&text=%E4%BD%A0%E5%A5%BD+%E4%B8%96%E7%95%8C%26&ai_url=http%3A%2F%2F100.1.2.3%3A11434%2Fv1"))

    @Test fun emptyValues() = assertEquals(mapOf("a" to "", "b" to "1"), PasteServer.parseForm("a=&b=1"))

    @Test fun pageEscapesMessage() = assertTrue(PasteServer.page("<b>", showForm = false).contains("&lt;b&gt;"))
}
