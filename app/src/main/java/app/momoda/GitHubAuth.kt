package app.momoda

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * GitHub 设备授权（OAuth Device Flow），配合一个 GitHub App 使用：
 * 汉王上显示 8 位验证码，用户在手机或电脑上打开 github.com/login/device 输入并授权，汉王上一个字都不用打。
 *
 * - 只需要 GitHub App 的 Client ID（公开信息），不需要 client secret，开源 App 里也不应该放 secret。
 * - GitHub App 应当关闭“Expire user authorization tokens”：续期需要 client secret，关闭后 token 长期有效，
 *   权限仍然只限安装了这个 App 的仓库，并且可以随时在 GitHub 上撤销授权。
 */
class GitHubAuth(
    private val clientId: String,
    private val webBase: String = "https://github.com",
    private val apiBase: String = "https://api.github.com",
) {
    data class DeviceCode(val deviceCode: String, val userCode: String, val verificationUri: String, val interval: Int, val expiresIn: Int)

    class Cancelled : Exception("已取消")

    @Volatile var cancelled = false

    fun requestCode(): DeviceCode {
        val r = post("$webBase/login/device/code", mapOf("client_id" to clientId))
        r.optString("error").takeIf { it.isNotEmpty() }?.let { error(describe(it, r)) }
        return DeviceCode(
            r.getString("device_code"), r.getString("user_code"), r.optString("verification_uri", "https://github.com/login/device"),
            r.optInt("interval", 5), r.optInt("expires_in", 900),
        )
    }

    /** 轮询直到用户授权（返回 token）、拒绝、过期或被取消。阻塞调用，放在后台线程。 */
    fun waitForToken(code: DeviceCode): String {
        var interval = code.interval
        val deadline = System.currentTimeMillis() + code.expiresIn * 1000L
        while (System.currentTimeMillis() < deadline) {
            sleep(interval * 1000L)
            val r = post("$webBase/login/oauth/access_token", mapOf(
                "client_id" to clientId,
                "device_code" to code.deviceCode,
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
            ))
            r.optString("access_token").takeIf { it.isNotEmpty() }?.let { return it }
            when (val err = r.optString("error")) {
                "authorization_pending" -> Unit
                "slow_down" -> interval = r.optInt("interval", interval + 5)
                else -> error(describe(err, r))
            }
        }
        error("验证码已过期，请重新登录")
    }

    /** 这个 token 能访问的仓库（GitHub App 安装到的仓库），格式 `owner/name`。 */
    fun repositories(token: String): List<String> {
        val out = mutableListOf<String>()
        val installs = get("$apiBase/user/installations", token).optJSONArray("installations") ?: return out
        for (i in 0 until installs.length()) {
            val id = installs.getJSONObject(i).getLong("id")
            val repos = get("$apiBase/user/installations/$id/repositories?per_page=100", token).optJSONArray("repositories") ?: continue
            for (j in 0 until repos.length()) out += repos.getJSONObject(j).getString("full_name")
        }
        return out.distinct()
    }

    private fun sleep(ms: Long) {
        var left = ms
        while (left > 0) {
            if (cancelled) throw Cancelled()
            Thread.sleep(minOf(left, 250))
            left -= 250
        }
        if (cancelled) throw Cancelled()
    }

    private fun describe(err: String, r: JSONObject) = when (err) {
        "access_denied" -> "你在 GitHub 上拒绝了授权"
        "expired_token" -> "验证码已过期，请重新登录"
        "device_flow_disabled" -> "这个 GitHub App 没有开启 Device Flow"
        "incorrect_client_credentials" -> "Client ID 不对"
        else -> r.optString("error_description").ifEmpty { err }
    }

    private fun post(url: String, form: Map<String, String>): JSONObject {
        val body = form.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            return JSONObject(stream?.bufferedReader()?.use { it.readText() }.orEmpty().ifEmpty { "{}" })
        } finally {
            conn.disconnect()
        }
    }

    private fun get(url: String, token: String): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            if (conn.responseCode !in 200..299) error("GitHub 返回 ${conn.responseCode}")
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }
}
