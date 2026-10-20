package app.momoda

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.PrintWriter
import java.io.StringWriter

class DiagnosticsTest {

    private fun traceOf(e: Throwable) = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()

    @Test fun messagesAreRemovedButStackStays() {
        val cause = NumberFormatException("For input string: \"13800138000\"")
        val e = IllegalStateException("正文第一行\n正文第二行\n\tat 看起来像调用栈的正文", cause)
        e.addSuppressed(RuntimeException("被压制的异常也带正文"))
        val out = Diagnostics.redact("线程：main\n时间：2026-10-20 08:15:00\n\n" + traceOf(e))

        for (secret in listOf("13800138000", "正文", "被压制")) assertFalse(out, out.contains(secret))
        assertTrue(out.contains("线程：main"))
        assertTrue(out.contains("java.lang.IllegalStateException: （异常信息已隐去）"))
        assertTrue(out.contains("Caused by: java.lang.NumberFormatException: （异常信息已隐去）"))
        assertTrue(out.contains("Suppressed: java.lang.RuntimeException: （异常信息已隐去）"))
        assertTrue(out.contains("\tat app.momoda.DiagnosticsTest.messagesAreRemovedButStackStays(DiagnosticsTest.kt:"))
        assertTrue(Regex("\t\\.\\.\\. \\d+ more").containsMatchIn(out))
    }

    @Test fun exceptionWithoutMessageIsKeptAsIs() {
        val out = Diagnostics.redact(traceOf(NullPointerException()))
        assertEquals("java.lang.NullPointerException", out.lines().first())
    }

    @Test fun systemCrashRecordKeepsReason() {
        assertEquals("时间：x\n原因：java.lang.RuntimeException", Diagnostics.redact("时间：x\n原因：java.lang.RuntimeException\n正文"))
    }
}
