package app.momoda

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 调用 OpenAI 兼容的 Chat Completions 接口（DESIGN §6）：OpenAI、OpenRouter、Ollama、LM Studio、vLLM 等。
 * 家里的节点经 Tailscale 接入时，地址形如 `http://100.x.x.x:11434/v1`，不需要特殊处理。
 */
object Llm {

    data class Config(val baseUrl: String, val apiKey: String, val model: String) {
        val configured get() = baseUrl.isNotBlank() && model.isNotBlank()
    }

    /** 可以随时调用 [cancel] 中断流式输出。 */
    class Call {
        @Volatile var cancelled = false
            private set
        @Volatile internal var conn: HttpURLConnection? = null
        fun cancel() {
            cancelled = true
            runCatching { conn?.disconnect() }
        }
    }

    /**
     * 流式请求。在调用方的后台线程上执行：每收到一段文字回调 [onDelta]，结束时返回完整文本。
     * 出错时抛异常（含服务端返回的错误信息）。
     */
    fun stream(config: Config, system: String, user: String, call: Call, onDelta: (String) -> Unit): String {
        val url = URL(config.baseUrl.trimEnd('/') + "/chat/completions")
        val body = JSONObject()
            .put("model", config.model)
            .put("stream", true)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
            if (config.apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer ${config.apiKey}")
        }
        call.conn = conn
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                error("HTTP $code ${errorMessage(err)}")
            }
            val out = StringBuilder()
            conn.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (call.cancelled) break
                    val delta = parseSseLine(line) ?: continue
                    if (delta == DONE) break
                    out.append(delta)
                    onDelta(delta)
                }
            }
            return out.toString()
        } finally {
            conn.disconnect()
        }
    }

    internal const val DONE = "\u0000[DONE]"

    /** 解析一行 SSE：返回这一行带来的文字；不是数据行返回 null；结束标记返回 [DONE]。 */
    internal fun parseSseLine(line: String): String? {
        if (!line.startsWith("data:")) return null
        val payload = line.removePrefix("data:").trim()
        if (payload == "[DONE]") return DONE
        if (payload.isEmpty()) return null
        val json = runCatching { JSONObject(payload) }.getOrNull() ?: return null
        val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: return null
        val delta = choice.optJSONObject("delta") ?: choice.optJSONObject("message") ?: return null
        return delta.optString("content", "").ifEmpty { null }
    }

    private fun errorMessage(body: String): String =
        runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }.getOrNull()
            ?: body.take(200)
}

/** AI 配置的持久化。API key 用 Android Keystore 里的 AES 密钥加密后再存。 */
class LlmSettings(context: Context) {

    private val prefs = context.getSharedPreferences("llm", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString("baseUrl", "")!!
        set(v) = prefs.edit().putString("baseUrl", v.trim()).apply()

    var model: String
        get() = prefs.getString("model", "")!!
        set(v) = prefs.edit().putString("model", v.trim()).apply()

    var apiKey: String
        get() = prefs.getString("apiKey", null)?.let { runCatching { SecretBox.open(it) }.getOrNull() } ?: ""
        set(v) = prefs.edit().putString("apiKey", if (v.isBlank()) null else SecretBox.seal(v.trim())).apply()

    val config get() = Llm.Config(baseUrl, apiKey, model)
}

/** 用 Android Keystore 中不可导出的 AES-GCM 密钥加解密短文本。 */
internal object SecretBox {
    private const val ALIAS = "momoda-llm-key"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    fun seal(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = c.iv + c.doFinal(plain.toByteArray())
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    fun open(sealed: String): String {
        val bytes = Base64.decode(sealed, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
        }
        return String(c.doFinal(bytes, 12, bytes.size - 12))
    }
}
