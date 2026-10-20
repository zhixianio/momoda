package app.momoda

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PasteServerTest {
    @Test fun parsesUrlEncodedForm() =
        assertEquals(mapOf("code" to "1234", "text" to "你好 世界&", "ai_url" to "http://100.1.2.3:11434/v1"),
            PasteServer.parseForm("code=1234&text=%E4%BD%A0%E5%A5%BD+%E4%B8%96%E7%95%8C%26&ai_url=http%3A%2F%2F100.1.2.3%3A11434%2Fv1"))

    @Test fun emptyValues() = assertEquals(mapOf("a" to "", "b" to "1"), PasteServer.parseForm("a=&b=1"))

    @Test fun pageEscapesMessage() = assertTrue(PasteServer.page("<b>", showForm = false).contains("&lt;b&gt;"))

    /** 一个一直不发完请求头的连接，不能让用户自己的提交永远等着。 */
    @Test fun slowClientDoesNotBlockOthers() {
        val got = CountDownLatch(1)
        val server = PasteServer(onSubmit = { got.countDown() }, onClosed = {}, requestTimeoutMs = 500)
        val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        server.listen(socket)
        val slow = Socket(InetAddress.getLoopbackAddress(), socket.localPort)
        val trickle = Thread {
            runCatching {
                val out = slow.getOutputStream()
                out.write("POST /submit HTTP/1.1\r\n".toByteArray())
                repeat(100) { out.write('X'.code); out.flush(); Thread.sleep(100) }
            }
        }.apply { isDaemon = true; start() }
        Thread.sleep(100)
        val body = "code=${server.code}&text=hi"
        Socket(InetAddress.getLoopbackAddress(), socket.localPort).use { s ->
            s.getOutputStream().write("POST /submit HTTP/1.1\r\nContent-Length: ${body.length}\r\n\r\n$body".toByteArray())
            assertTrue("慢连接一直占着服务", got.await(5, TimeUnit.SECONDS))
        }
        trickle.interrupt()
        slow.close()
        server.close()
    }
}
