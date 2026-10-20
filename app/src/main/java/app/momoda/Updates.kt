package app.momoda

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 版本号比较：`v0.2.0` 与 `0.1.9-m0` 这类写法都能比。先比数字部分；数字相同时，带后缀（-m0、-beta.2）的预览版
 * 比正式版旧，两个预览版之间按后缀逐段比较（beta.1 < beta.2 < beta.10）。
 */
object Versions {
    fun parse(v: String): List<Int> =
        v.trim().removePrefix("v").removePrefix("V").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }

    private fun suffix(v: String): String? = v.trim().substringAfter('-', "").ifEmpty { null }

    fun isNewer(candidate: String, current: String): Boolean {
        val c = compareNumbers(parse(candidate), parse(current))
        if (c != 0) return c > 0
        val a = suffix(candidate)
        val b = suffix(current)
        return when {
            a == null || b == null -> a == null && b != null // 正式版比预览版新
            else -> compareSuffix(a, b) > 0
        }
    }

    private fun compareNumbers(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    private fun compareSuffix(a: String, b: String): Int {
        val pa = a.split('.')
        val pb = b.split('.')
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrNull(i) ?: return -1
            val y = pb.getOrNull(i) ?: return 1
            val nx = x.toIntOrNull()
            val ny = y.toIntOrNull()
            val r = if (nx != null && ny != null) nx.compareTo(ny) else x.compareTo(y)
            if (r != 0) return r
        }
        return 0
    }
}

/**
 * 检查更新（DESIGN §7）：每天最多查一次 GitHub Releases 的最新版本，有新版本时只在页脚提示，不弹窗；
 * 下载和安装由用户在命令面板里触发，安装用系统 PackageInstaller，最后一步由系统弹窗确认。
 */
class Updates(private val context: Context, private val apiUrl: String) {

    data class Release(val version: String, val apkUrl: String, val notes: String)

    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())

    val configured get() = apiUrl.isNotEmpty()

    var latest: Release? = null
        private set

    /** 距离上次检查超过一天才联网；[force] 用于命令面板里的“检查更新”。 */
    fun check(current: String, force: Boolean, onResult: (Release?, String?) -> Unit) {
        if (!configured) { onResult(null, "还没有配置发布地址"); return }
        val last = prefs.getLong("lastCheck", 0)
        if (!force && System.currentTimeMillis() - last < 24 * 3600_000L) {
            latest = cached()?.takeIf { Versions.isNewer(it.version, current) }
            onResult(latest, null)
            return
        }
        Thread({
            val result = runCatching { fetch() }
            main.post {
                result.onSuccess { r ->
                    prefs.edit().putLong("lastCheck", System.currentTimeMillis())
                        .putString("version", r?.version).putString("apk", r?.apkUrl).putString("notes", r?.notes).apply()
                    latest = r?.takeIf { Versions.isNewer(it.version, current) }
                    onResult(latest, null)
                }.onFailure {
                    Log.w("momoda", "检查更新失败", it)
                    onResult(null, "检查更新失败：${it.message ?: it.javaClass.simpleName}")
                }
            }
        }, "update-check").apply { isDaemon = true }.start()
    }

    private fun cached(): Release? {
        val v = prefs.getString("version", null) ?: return null
        val apk = prefs.getString("apk", null) ?: return null
        return Release(v, apk, prefs.getString("notes", "") ?: "")
    }

    private fun fetch(): Release? {
        val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) {
                    return Release(json.optString("tag_name"), a.optString("browser_download_url"), json.optString("body"))
                }
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 下载 APK 并交给系统安装。系统会弹窗请用户确认；签名和当前安装的不一致时系统会拒绝安装。
     * [onStatus] 在主线程上报进度文字。
     */
    fun install(release: Release, onStatus: (String) -> Unit) {
        onStatus("正在下载 ${release.version}…")
        Thread({
            runCatching {
                val installer = context.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                val id = installer.createSession(params)
                installer.openSession(id).use { session ->
                    val conn = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 10_000
                        readTimeout = 60_000
                        instanceFollowRedirects = true
                    }
                    try {
                        if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
                        session.openWrite("momoda.apk", 0, conn.contentLengthLong.takeIf { it > 0 } ?: -1).use { out ->
                            conn.inputStream.use { it.copyTo(out, 64 * 1024) }
                            session.fsync(out)
                        }
                    } finally {
                        conn.disconnect()
                    }
                    listenForResult(onStatus)
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val pi = PendingIntent.getBroadcast(context, id, Intent(ACTION).setPackage(context.packageName), flags)
                    session.commit(pi.intentSender)
                }
            }.onFailure {
                Log.w("momoda", "下载更新失败", it)
                main.post { onStatus("下载失败：${it.message ?: it.javaClass.simpleName}") }
            }
        }, "update-download").apply { isDaemon = true }.start()
    }

    private fun listenForResult(onStatus: (String) -> Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        // 系统需要用户确认（以及第一次时开启“安装未知应用”）
                        @Suppress("DEPRECATION")
                        val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                        confirm?.let { c.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        onStatus("请在系统弹窗里确认安装")
                    }
                    PackageInstaller.STATUS_SUCCESS -> { onStatus("更新完成"); c.unregisterReceiver(this) }
                    else -> {
                        onStatus("安装没有完成：${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "已取消"}")
                        c.unregisterReceiver(this)
                    }
                }
            }
        }
        // 必须在 commit 之前注册好，否则可能错过结果广播；回调仍在主线程执行
        val filter = IntentFilter(ACTION)
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, null, main, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(receiver, filter, null, main)
    }

    companion object {
        private const val ACTION = "app.momoda.INSTALL_RESULT"
    }
}
