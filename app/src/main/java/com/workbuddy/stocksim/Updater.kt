package com.workbuddy.stocksim

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/* ============================================================
 * GitHub 应用内更新
 * 流程: 查询 latest Release -> 比较版本号 -> 下载 APK 到缓存 ->
 * FileProvider 授权 -> 调起系统安装器。
 * ============================================================ */
object Updater {
    private const val REPO = "lliiaannggyu/stock-sim-wearos"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    class ReleaseInfo(val version: String, val notes: String, val apkUrl: String, val size: Long)

    private fun onMain(f: () -> Unit) { main.post(f) }

    fun currentVersion(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"
    } catch (_: Exception) { "0" }

    /** 比较点分版本号: a<b 返回 -1, 相等 0, a>b 返回 1 (忽略前缀 v) */
    fun cmpVersion(a: String, b: String): Int {
        val pa = a.trim().removePrefix("v").split(".").map { it.trim().toIntOrNull() ?: 0 }
        val pb = b.trim().removePrefix("v").split(".").map { it.trim().toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }; val y = pb.getOrElse(i) { 0 }
            if (x != y) return if (x < y) -1 else 1
        }
        return 0
    }

    /** 查询 GitHub 最新 Release; 找到 .apk 资产才返回 */
    fun checkAsync(ctx: Context, onResult: (ReleaseInfo?) -> Unit) {
        exec.execute {
            var r: ReleaseInfo? = null
            try {
                val conn = URL(API).openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 15000
                conn.setRequestProperty("User-Agent", "stock-sim-wearos")
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                    val j = JSONObject(body)
                    val tag = j.optString("tag_name", "")
                    val notes = j.optString("body", "")
                    val assets: JSONArray = j.optJSONArray("assets") ?: JSONArray()
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.optString("name", "").endsWith(".apk")) {
                            val url = a.optString("browser_download_url", "")
                            if (url.isNotEmpty()) {
                                r = ReleaseInfo(tag.removePrefix("v"), notes, url, a.optLong("size", 0))
                            }
                            break
                        }
                    }
                }
                conn.disconnect()
            } catch (_: Exception) {}
            onMain { onResult(r) }
        }
    }

    /** 后台下载 APK 到 cacheDir/update.apk, 回调进度百分比 (0..100) */
    fun downloadAsync(ctx: Context, info: ReleaseInfo,
                      onProgress: (Int) -> Unit, onDone: (File) -> Unit, onFail: (String) -> Unit) {
        exec.execute {
            try {
                val conn = URL(info.apkUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 30000
                conn.setRequestProperty("User-Agent", "stock-sim-wearos")
                val total = conn.contentLengthLong.let { if (it > 0) it else info.size }
                val out = File(ctx.cacheDir, "update.apk")
                var got = 0L
                var lastPct = -1
                conn.inputStream.use { ins ->
                    FileOutputStream(out).use { fos ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            fos.write(buf, 0, n)
                            got += n
                            if (total > 0) {
                                val pct = (got * 100 / total).toInt()
                                if (pct != lastPct) { lastPct = pct; onMain { onProgress(pct) } }
                            }
                        }
                    }
                }
                conn.disconnect()
                onMain { onDone(out) }
            } catch (e: Exception) {
                onMain { onFail(e.message ?: "下载失败") }
            }
        }
    }

    /** 经 FileProvider 授权调起系统安装器; 未授权"安装未知应用"时引导去设置 */
    fun install(ctx: Context, apk: File) {
        try {
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", apk)
            val i = Intent(Intent.ACTION_INSTALL_PACKAGE).also {
                it.setDataAndType(uri, "application/vnd.android.package-archive")
                it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(i)
        } catch (_: Exception) {
            try {
                ctx.startActivity(
                    Intent("android.settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES",
                        Uri.parse("package:" + ctx.packageName))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {}
        }
    }
}
