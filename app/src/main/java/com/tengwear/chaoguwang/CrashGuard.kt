package com.tengwear.chaoguwang

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.LinearLayout
import java.io.File

/* ============================================================
 * 崩溃防护: 捕获所有未捕获异常, 写日志并显示原因页面 (便于定位小米手表闪退)
 * 错误页用原生 View, 不依赖 Compose, 避免二次崩溃。
 * ============================================================ */
object CrashGuard {
    const val LOG_FILE = "crash.log"

    fun install(ctx: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, err ->
            try {
                val ver = try {
                    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
                } catch (_: PackageManager.NameNotFoundException) { "?" }
                val sb = StringBuilder()
                sb.append("模拟炒股崩溃报告\n")
                sb.append("设备: ${Build.MANUFACTURER} ${Build.MODEL} / API ${Build.VERSION.SDK_INT}\n")
                sb.append("应用: ${ctx.packageName} v$ver\n")
                sb.append("线程: ${thread.name}\n")
                sb.append("异常: ${err.javaClass.name}: ${err.message}\n")
                sb.append(err.stackTraceToString())
                File(ctx.filesDir, LOG_FILE).writeText(sb.toString())
                val intent = Intent(ctx, CrashActivity::class.java).apply {
                    putExtra("report", sb.toString())
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                ctx.startActivity(intent)
            } catch (_: Exception) {}
            try { previous?.uncaughtException(thread, err) } catch (_: Exception) {}
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(1)
        }
    }

    fun readLog(ctx: Context): String = try {
        File(ctx.filesDir, LOG_FILE).readText()
    } catch (_: Exception) { "" }
}

class CrashActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val report = intent.getStringExtra("report") ?: "未知错误"
        val scroll = ScrollView(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val tv = TextView(this).apply {
            text = report
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(24, 24, 24, 12)
        }
        val restart = Button(this).apply {
            text = "重启应用"
            setOnClickListener {
                val i = packageManager.getLaunchIntentForPackage(packageName)
                if (i != null) {
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    startActivity(i)
                }
                finish()
            }
        }
        val copy = Button(this).apply {
            text = "复制日志"
            setOnClickListener {
                try {
                    @Suppress("DEPRECATION")
                    val manager = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    manager.setPrimaryClip(android.content.ClipData.newPlainText("crash", report))
                    text = "已复制 ✓"
                } catch (_: Exception) { text = "复制失败" }
            }
        }
        box.addView(tv); box.addView(restart); box.addView(copy)
        scroll.addView(box)
        scroll.setBackgroundColor(0xFF1A1A1A.toInt())
        setContentView(scroll)
    }
}
