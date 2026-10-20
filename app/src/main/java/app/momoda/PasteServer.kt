package app.momoda

import android.util.Log
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.security.SecureRandom

/**
 * “从其他设备粘贴”：在局域网里临时开一个小网页，让用户在手机或电脑上把长文本（token、接口地址、正文片段）
 * 粘贴过来，汉王上就不用手打了。
 *
 * 安全措施：只在弹窗打开期间运行；提交必须带屏幕上的 4 位配对码；输错 5 次、提交成功一次或 10 分钟无人使用后自动关闭。
 * 注意：这是局域网内的明文 HTTP，只适合在自己信任的 Wi-Fi 下使用。
 */
class PasteServer(
    private val onSubmit: (Map<String, String>) -> Unit,
    private val onClosed: (String) -> Unit,
    /** 一个请求从连上到读完的总时限。只有一个处理线程，慢吞吞的连接不能把它一直占着。 */
    private val requestTimeoutMs: Long = 20_000,
) {

    val code = "%04d".format(SecureRandom().nextInt(10000))
    private var server: ServerSocket? = null
    @Volatile private var closed = false
    private var wrong = 0

    /** 启动并返回访问地址（http://IP:端口）；找不到局域网地址时返回 null。 */
    fun start(): String? {
        val ip = lanAddress() ?: return null
        // 优先用固定端口，地址好记；被占用时退回系统分配的端口
        val s = runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(PORT)) } }
            .getOrElse { ServerSocket(0) }
        listen(s)
        return "http://$ip:${s.localPort}"
    }

    internal fun listen(s: ServerSocket) {
        s.soTimeout = 10 * 60_000
        server = s
        Thread({ loop(s) }, "paste-server").apply { isDaemon = true }.start()
    }

    private class TooSlow : java.io.IOException("请求太慢")

    fun close(reason: String = "") {
        if (closed) return
        closed = true
        runCatching { server?.close() }
        onClosed(reason)
    }

    private fun loop(s: ServerSocket) {
        try {
            while (!closed) {
                val client = s.accept()
                runCatching { handle(client) }.onFailure { Log.w("momoda", "paste request", it) }
                client.close()
            }
        } catch (e: java.net.SocketTimeoutException) {
            close("10 分钟没有人使用，已关闭")
        } catch (e: SocketException) {
            // close() 关掉了 socket
        }
    }

    private fun handle(socket: Socket) {
        val deadline = System.currentTimeMillis() + requestTimeoutMs
        socket.soTimeout = requestTimeoutMs.toInt()
        val input = socket.getInputStream().buffered()
        val requestLine = readLine(input, deadline) ?: return
        var length = 0
        while (true) {
            val h = readLine(input, deadline) ?: break
            if (h.isEmpty()) break
            if (h.lowercase().startsWith("content-length:")) length = h.substringAfter(':').trim().toIntOrNull() ?: 0
        }
        val (method, path) = requestLine.split(' ').let { it.getOrElse(0) { "" } to it.getOrElse(1) { "/" } }
        if (method == "POST" && path.startsWith("/submit")) {
            val body = ByteArray(length.coerceAtMost(1_000_000))
            var read = 0
            while (read < body.size) {
                if (System.currentTimeMillis() > deadline) throw TooSlow()
                val n = input.read(body, read, body.size - read); if (n < 0) break; read += n
            }
            val form = parseForm(String(body, 0, read))
            if (form["code"] != code) {
                wrong++
                respond(socket, page("配对码不对，请看汉王屏幕上的 4 位数字。", showForm = true))
                if (wrong >= 5) close("配对码输错太多次，已关闭")
                return
            }
            val fields = form.filterKeys { it != "code" }.filterValues { it.isNotBlank() }
            respond(socket, page(if (fields.isEmpty()) "没有填写任何内容。" else "已发送到墨墨打，可以关闭这个页面了。", showForm = fields.isEmpty()))
            if (fields.isNotEmpty()) {
                onSubmit(fields)
                close("已收到")
            }
        } else {
            respond(socket, page(null, showForm = true))
        }
    }

    private fun readLine(input: java.io.InputStream, deadline: Long): String? {
        val sb = StringBuilder()
        while (true) {
            if (System.currentTimeMillis() > deadline) throw TooSlow()
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) return sb.toString()
        }
    }

    private fun respond(socket: Socket, html: String) {
        val bytes = html.toByteArray()
        val head = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n"
        socket.getOutputStream().apply { write(head.toByteArray()); write(bytes); flush() }
    }

    companion object {
        const val PORT = 8899

        fun parseForm(body: String): Map<String, String> = body.split('&').filter { it.contains('=') }.associate {
            val (k, v) = it.split('=', limit = 2)
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }

        /** Wi-Fi 等局域网接口上的 IPv4 地址。 */
        fun lanAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
        }.getOrNull()

        private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        fun page(message: String?, showForm: Boolean): String = buildString {
            append("""<!doctype html><html lang="zh"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>粘贴到墨墨打</title><style>
body{font-family:-apple-system,"PingFang SC",sans-serif;max-width:560px;margin:24px auto;padding:0 16px;color:#111;background:#fff}
h1{font-size:22px}fieldset{border:1px solid #ccc;border-radius:8px;margin:16px 0;padding:8px 14px 14px}
legend{font-weight:600;padding:0 6px}label{display:block;margin:10px 0 4px;color:#555;font-size:14px}
input,textarea{width:100%;box-sizing:border-box;font-size:16px;padding:10px;border:1px solid #999;border-radius:6px}
textarea{height:140px}button{font-size:18px;padding:12px 28px;margin-top:8px;border:2px solid #111;background:#fff;border-radius:8px}
.msg{padding:12px;border:2px solid #111;border-radius:8px;margin:16px 0}.hint{color:#777;font-size:13px}
</style></head><body><h1>粘贴到墨墨打</h1>""")
            if (message != null) append("<div class=\"msg\">${esc(message)}</div>")
            if (showForm) append("""
<p class="hint">只填需要的部分，留空的不会改动。提交后这个页面就会关闭。</p>
<form method="post" action="/submit">
<label>配对码（汉王屏幕上的 4 位数字）</label><input name="code" inputmode="numeric" maxlength="4" required autofocus>
<fieldset><legend>AI 设置</legend>
<label>接口地址（base_url）</label><input name="ai_url" placeholder="http://100.x.x.x:11434/v1" autocapitalize="off">
<label>API key</label><input name="ai_key" type="password" autocomplete="off">
<label>模型</label><input name="ai_model" placeholder="qwen2.5:14b" autocapitalize="off"></fieldset>
<fieldset><legend>GitHub 同步</legend>
<label>仓库（用户名/仓库名）</label><input name="gh_repo" placeholder="用户名/笔记仓库" autocapitalize="off">
<label>Fine-grained token</label><input name="gh_token" type="password" autocomplete="off"></fieldset>
<fieldset><legend>粘贴到正文</legend>
<label>插入到当前文档的光标处</label><textarea name="text"></textarea></fieldset>
<button type="submit">发送到墨墨打</button></form>""")
            append("</body></html>")
        }
    }
}
