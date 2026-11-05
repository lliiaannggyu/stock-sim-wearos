package com.workbuddy.stocksim

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

/**
 * 实时汇率（新浪外汇接口，盘中实时更新）。
 * fx_susdcny / fx_shkdcny 字段: [0]=时间 [1]=买价 [2]=卖价 ... [8]=最新价
 * 含义: 1 USD = usdCny 人民币, 1 HKD = hkdCny 人民币。
 * 每 5 分钟刷新一次，失败时沿用上次值（初始为兜底常量）。
 */
object Fx {
    @Volatile var usdCny = 7.15
    @Volatile var hkdCny = 0.92
    var updated by mutableStateOf("--:--")
    private var lastMs = 0L
    private val pU = Pattern.compile("hq_str_fx_susdcny=\"([^\"]*)\"")
    private val pH = Pattern.compile("hq_str_fx_shkdcny=\"([^\"]*)\"")

    /** 1 单位该货币 = ? 人民币 */
    fun rate(cur: String): Double = when (cur) {
        "USD" -> usdCny; "HKD" -> hkdCny; else -> 1.0
    }

    fun refreshIfNeeded(maxAgeMs: Long = 300_000L) {
        if (System.currentTimeMillis() - lastMs < maxAgeMs) return
        lastMs = System.currentTimeMillis()
        Thread {
            try {
                val c = URL("https://hq.sinajs.cn/list=fx_susdcny,fx_shkdcny")
                    .openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 8000
                c.setRequestProperty("Referer", "https://finance.sina.com.cn/")
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
                val inp = c.getInputStream()
                val bo = ByteArrayOutputStream()
                val buf = ByteArray(4096); var n: Int
                while (inp.read(buf).also { n = it } > 0) bo.write(buf, 0, n)
                inp.close()
                val txt = bo.toString("GBK")
                val mu = pU.matcher(txt)
                if (mu.find()) {
                    val f = mu.group(1)!!.split("~")
                    val v = f.getOrNull(8)?.toDoubleOrNull() ?: f.getOrNull(1)?.toDoubleOrNull()
                    if (v != null && v in 4.0..12.0) usdCny = v
                    if (!f.getOrNull(0).isNullOrBlank()) updated = f[0].take(5)
                }
                val mh = pH.matcher(txt)
                if (mh.find()) {
                    val f = mh.group(1)!!.split("~")
                    val v = f.getOrNull(8)?.toDoubleOrNull() ?: f.getOrNull(1)?.toDoubleOrNull()
                    if (v != null && v in 0.6..1.3) hkdCny = v
                }
            } catch (_: Exception) {}
        }.start()
    }
}
