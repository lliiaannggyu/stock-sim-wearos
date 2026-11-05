package com.workbuddy.stocksim

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.ScalingLazyColumn
import androidx.wear.compose.material.Switch
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.Vignette
import androidx.wear.compose.material.VignettePosition
import androidx.wear.compose.material.rememberScalingLazyListState
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

/* ================= 常量 ================= */
// 涨跌色为语义色, 不随主题种子变化
val Up = Color(0xFFF28B82)
val Down = Color(0xFF6DD58C)
val Dark1 = Color(0xFF3B0905)
val Dark2 = Color(0xFF03210F)

// 其余颜色来自种子配色主题 (Theme.kt), 随主题切换即时生效
val Gold: Color get() = SchemeState.ter
val Dim: Color get() = SchemeState.txtDim
val Pri: Color get() = SchemeState.pri
val OnPri: Color get() = SchemeState.onPri
val Surf: Color get() = SchemeState.surf
val SurfHigh: Color get() = SchemeState.surfHigh
val TxtMid: Color get() = SchemeState.txtMid

fun upDownColor(v: Double): Color = if (v > 0) Up else if (v < 0) Down else Dim
fun signed(v: Double, d: Int = 2): String =
    (if (v > 0) "+" else "") + String.format(Locale.CHINA, "%.${d}f", v)
fun f2(v: Double, d: Int = 2): String = String.format(Locale.CHINA, "%.${d}f", v)
fun round2(v: Double) = (v * 100).roundToInt() / 100.0

/** 大额数字缩写: 123456 -> 12.3万 */
fun shortNum(v: Double): String =
    if (abs(v) >= 100000) f2(v / 10000, 1) + "万" else f2(v, 0)

fun buzz(ctx: Context, ms: Long = 8) {
    try {
        val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        v.vibrate(VibrationEffect.createOneShot(ms, 60))
    } catch (_: Exception) {}
}

/* ================= 市场规则 ================= */

object Mk {
    /** 市场识别: CN / HK / US */
    fun market(code: String): String = when {
        code.startsWith("hk") -> "HK"
        code.startsWith("us") -> "US"
        else -> "CN"
    }

    fun sym(code: String): String = when (market(code)) {
        "HK" -> "HK$"; "US" -> "$"; else -> "¥"
    }

    fun currency(code: String): String = when (market(code)) {
        "HK" -> "HKD"; "US" -> "USD"; else -> "CNY"
    }

    fun marketName(code: String): String = when (market(code)) {
        "HK" -> "港股"; "US" -> "美股"; else -> "A股"
    }

    /** 是否场内基金 (ETF/LOF) */
    fun isFund(code: String): Boolean {
        if (market(code) != "CN") return false
        return code.startsWith("sh5") || code.startsWith("sh50") ||
            code.startsWith("sz15") || code.startsWith("sz16")
    }

    /** 是否 T+1 (仅A股证券/基金) */
    fun isT1(code: String): Boolean = market(code) == "CN"

    /** 每手股数 */
    fun lot(code: String, q: Quote?): Int = when (market(code)) {
        "US" -> 1
        "HK" -> max(q?.lot ?: 0, 1)
        else -> 100
    }

    /** 折算人民币汇率(实时, 新浪外汇) */
    fun fx(cur: String): Double = Fx.rate(cur)

    /** 买入费用 */
    fun buyFee(code: String, amt: Double): Double = when (market(code)) {
        "HK" -> max(amt * 0.0003, 3.0) + amt * 0.001            // 佣金0.03%最低3 + 印花税双边0.1%
        "US" -> max(amt * 0.0005, 1.0)                          // 佣金0.05%最低$1
        else -> if (isFund(code)) max(amt * 0.00025, 5.0)
                else max(amt * 0.00025, 5.0) + amt * 0.00001    // 佣金+过户费
    }

    /** 卖出费用 */
    fun sellFee(code: String, amt: Double): Double = when (market(code)) {
        "HK" -> max(amt * 0.0003, 3.0) + amt * 0.001
        "US" -> max(amt * 0.0005, 1.0)
        else -> if (isFund(code)) max(amt * 0.00025, 5.0)
                else max(amt * 0.00025, 5.0) + amt * (0.0005 + 0.00001) // +印花税
    }

    /** 规则说明文字 */
    fun ruleText(code: String, q: Quote?): String = when {
        market(code) == "US" -> "T+0 · 任意股数 · 佣金0.05% 最低$1"
        market(code) == "HK" -> "T+0 · 每手${lot(code, q)}股 · 佣金0.03% · 印花税双边0.1%"
        isFund(code) -> "T+1 · 100份整数倍 · 佣金万2.5 · 免印花税"
        else -> "T+1 · 100股整数倍 · 佣金万2.5 · 印花税卖出0.05%"
    }

    /** 搜到的代码标准化: ustcehy.ps -> usTCEHY */
    fun normCode(raw: String): String {
        val r = raw.trim()
        return if (r.startsWith("us"))
            "us" + r.substring(2).substringBefore('.').uppercase()
        else r
    }
}

/* ================= 数据模型 ================= */

class Quote {
    var code: String = ""; var name: String = ""
    var price = 0.0; var prevClose = 0.0; var open = 0.0
    var high = 0.0; var low = 0.0
    var change = 0.0; var changePct = 0.0
    var turnover = 0.0; var marketCap = 0.0
    var limitUp = 0.0; var limitDown = 0.0
    var lot = 0
    var time: String = ""
}

class Lot(var date: String, var qty: Int)

class Position {
    var code: String = ""; var name: String = ""
    var cur: String = "CNY"
    var qty = 0; var frozen = 0; var avgCost = 0.0
    var lots = ArrayList<Lot>()
}

class Order {
    var id = ""; var side = ""; var code = ""; var name = ""
    var cur = "CNY"
    var status = "pending"; var price = 0.0; var frozenCash = 0.0
    var qty = 0; var frozenQty = 0
    var created = ""; var finished = ""; var filledPrice = ""
}

class Trade {
    var time = ""; var code = ""; var name = ""; var side = ""
    var cur = "CNY"
    var price = 0.0; var qty = 0; var amount = 0.0; var fee = 0.0
    var pnl = Double.NaN
}

class Account {
    companion object {
        val CURS = listOf("CNY", "HKD", "USD")
        val INITIAL = mapOf("CNY" to 1000000.0, "HKD" to 100000.0, "USD" to 10000.0)
        /** 初始总资产折人民币(按实时汇率动态计算) */
        fun initialTotalCny(): Double =
            INITIAL["CNY"]!! + INITIAL["HKD"]!! * Fx.rate("HKD") + INITIAL["USD"]!! * Fx.rate("USD")
    }

    val cash = mutableMapOf("CNY" to 1000000.0, "HKD" to 100000.0, "USD" to 10000.0)
    val frozenCash = mutableMapOf("CNY" to 0.0, "HKD" to 0.0, "USD" to 0.0)
    val realizedPnl = mutableMapOf("CNY" to 0.0, "HKD" to 0.0, "USD" to 0.0)
    var positions = LinkedHashMap<String, Position>()
    var orders = ArrayList<Order>()
    var trades = ArrayList<Trade>()

    fun availableCash(cur: String) = (cash[cur] ?: 0.0) - (frozenCash[cur] ?: 0.0)

    /** 全部资产折人民币 */
    fun totalCny(quotes: Map<String, Quote>): Double {
        var t = 0.0
        for (c in CURS) t += (cash[c] ?: 0.0) * Mk.fx(c)
        for (p in positions.values) {
            val q = quotes[p.code]
            if (q != null) t += q.price * p.qty * Mk.fx(p.cur)
        }
        return t
    }
}

/* ================= 交易引擎 ================= */

class Engine(private val ctx: Context) {
    var acc: Account
    private val rnd = java.util.Random()

    init { acc = load() }

    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
    private fun now() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
    private fun file() = File(ctx.filesDir, "account.json")

    private fun load(): Account {
        try {
            val f = FileInputStream(file())
            val bo = ByteArrayOutputStream()
            val buf = ByteArray(8192); var n: Int
            while (f.read(buf).also { n = it } > 0) bo.write(buf, 0, n)
            f.close()
            val o = JSONObject(bo.toString("UTF-8"))
            val a = Account()
            // 分币种资金 (兼容旧版单币种格式)
            val cm = o.optJSONObject("cash_map")
            if (cm != null) for (c in Account.CURS) a.cash[c] = cm.optDouble(c, Account.INITIAL[c] ?: 0.0)
            else a.cash["CNY"] = o.optDouble("cash", 1000000.0)
            val fm = o.optJSONObject("frozen_map")
            if (fm != null) for (c in Account.CURS) a.frozenCash[c] = fm.optDouble(c, 0.0)
            val rm = o.optJSONObject("pnl_map")
            if (rm != null) for (c in Account.CURS) a.realizedPnl[c] = rm.optDouble(c, 0.0)
            o.optJSONObject("positions")?.let { ps ->
                val it = ps.keys()
                while (it.hasNext()) {
                    val k = it.next(); val p = ps.getJSONObject(k)
                    val pos = Position()
                    pos.code = k; pos.name = p.optString("name")
                    pos.cur = p.optString("cur", Mk.currency(k))
                    pos.qty = p.optInt("qty"); pos.frozen = p.optInt("frozen")
                    pos.avgCost = p.optDouble("avg_cost", 0.0)
                    p.optJSONArray("lots")?.let { ls ->
                        for (i in 0 until ls.length()) {
                            val l = ls.getJSONObject(i)
                            pos.lots.add(Lot(l.optString("date"), l.optInt("qty")))
                        }
                    }
                    a.positions[k] = pos
                }
            }
            o.optJSONArray("orders")?.let { os ->
                for (i in 0 until os.length()) {
                    val x = os.getJSONObject(i)
                    val od = Order()
                    od.id = x.optString("id"); od.side = x.optString("side")
                    od.code = x.optString("code"); od.name = x.optString("name")
                    od.cur = x.optString("cur", Mk.currency(od.code))
                    od.status = x.optString("status", "pending")
                    od.price = x.optDouble("price"); od.frozenCash = x.optDouble("frozen_cash")
                    od.qty = x.optInt("qty"); od.frozenQty = x.optInt("frozen_qty")
                    od.created = x.optString("created"); od.finished = x.optString("finished")
                    od.filledPrice = x.optString("filled_price")
                    a.orders.add(od)
                }
            }
            o.optJSONArray("trades")?.let { ts ->
                for (i in 0 until ts.length()) {
                    val x = ts.getJSONObject(i)
                    val t = Trade()
                    t.time = x.optString("time"); t.code = x.optString("code")
                    t.name = x.optString("name"); t.side = x.optString("side")
                    t.cur = x.optString("cur", Mk.currency(t.code))
                    t.price = x.optDouble("price"); t.amount = x.optDouble("amount")
                    t.fee = x.optDouble("fee"); t.pnl = x.optDouble("pnl", Double.NaN)
                    t.qty = x.optInt("qty")
                    a.trades.add(t)
                }
            }
            return a
        } catch (_: Exception) {
            // 读取失败不立即覆盖存档: 先备份可疑文件, 返回内存新账户, 下次交易成功后才落盘
            try {
                if (file().exists()) {
                    val bak = File(ctx.filesDir, "account.json.bak")
                    if (bak.exists()) bak.delete()
                    file().renameTo(bak)
                }
            } catch (_: Exception) {}
            return Account()
        }
    }

    fun save(a: Account = acc) {
        try {
            val o = JSONObject()
            val cm = JSONObject(); val fm = JSONObject(); val rm = JSONObject()
            for (c in Account.CURS) {
                cm.put(c, a.cash[c] ?: 0.0); fm.put(c, a.frozenCash[c] ?: 0.0)
                rm.put(c, a.realizedPnl[c] ?: 0.0)
            }
            o.put("cash_map", cm); o.put("frozen_map", fm); o.put("pnl_map", rm)
            val ps = JSONObject()
            for (p in a.positions.values) {
                val x = JSONObject()
                x.put("name", p.name); x.put("cur", p.cur); x.put("qty", p.qty)
                x.put("frozen", p.frozen); x.put("avg_cost", p.avgCost)
                val ls = JSONArray()
                for (l in p.lots) ls.put(JSONObject().put("date", l.date).put("qty", l.qty))
                x.put("lots", ls)
                ps.put(p.code, x)
            }
            o.put("positions", ps)
            val os = JSONArray()
            for (od in a.orders) {
                val x = JSONObject()
                x.put("id", od.id); x.put("side", od.side); x.put("code", od.code)
                x.put("name", od.name); x.put("cur", od.cur); x.put("status", od.status)
                x.put("price", od.price); x.put("frozen_cash", od.frozenCash)
                x.put("qty", od.qty); x.put("frozen_qty", od.frozenQty)
                x.put("created", od.created); x.put("finished", od.finished)
                x.put("filled_price", od.filledPrice)
                os.put(x)
            }
            o.put("orders", os)
            val ts = JSONArray()
            for ((i, t) in a.trades.withIndex()) {
                if (i > 100) break
                val x = JSONObject()
                x.put("time", t.time); x.put("code", t.code); x.put("name", t.name)
                x.put("side", t.side); x.put("cur", t.cur); x.put("price", t.price)
                x.put("qty", t.qty); x.put("amount", t.amount); x.put("fee", t.fee)
                if (!t.pnl.isNaN()) x.put("pnl", t.pnl)   // NaN 会让 JSONObject.put 抛异常导致存档失败
                ts.put(x)
            }
            o.put("trades", ts)
            // 原子写入: 先写临时文件再替换, 防止进程中途被杀导致存档损坏
            val tmp = File(ctx.filesDir, "account.json.tmp")
            val f = FileOutputStream(tmp)
            f.write(o.toString().toByteArray(Charsets.UTF_8))
            f.flush(); f.fd.sync(); f.close()
            if (file().exists()) file().delete()
            tmp.renameTo(file())
        } catch (_: Exception) {}
    }

    /** 可卖数量: A股 T+1 (当日买入不可卖), 港美股 T+0 */
    fun available(p: Position): Int {
        return if (!Mk.isT1(p.code)) {
            max(p.qty - p.frozen, 0)
        } else {
            val t = today()
            var a = 0
            for (l in p.lots) if (l.date < t) a += l.qty
            max(a - p.frozen, 0)
        }
    }

    fun fillBuy(q: Quote, qty: Int): String? {
        val cur = Mk.currency(q.code)
        val amt = q.price * qty; val fee = Mk.buyFee(q.code, amt); val total = amt + fee
        if (acc.availableCash(cur) < total - 1e-6) return "该市场可用资金不足"
        acc.cash[cur] = (acc.cash[cur] ?: 0.0) - total
        val p = acc.positions[q.code] ?: run {
            val np = Position(); np.code = q.code; np.name = q.name; np.cur = cur
            acc.positions[q.code] = np; np
        }
        val oldCost = p.avgCost * p.qty
        p.qty += qty
        p.lots.add(Lot(today(), qty))
        p.avgCost = (oldCost + total) / p.qty
        val t = Trade()
        t.time = now(); t.code = q.code; t.name = q.name; t.side = "buy"; t.cur = cur
        t.price = q.price; t.qty = qty; t.amount = amt; t.fee = fee
        acc.trades.add(0, t)
        save(); return null
    }

    fun fillSell(q: Quote, qty: Int): String? {
        val cur = Mk.currency(q.code)
        val p = acc.positions[q.code] ?: return "持仓数量不足"
        if (p.qty < qty) return "持仓数量不足"
        val amt = q.price * qty; val fee = Mk.sellFee(q.code, amt)
        acc.cash[cur] = (acc.cash[cur] ?: 0.0) + amt - fee
        acc.realizedPnl[cur] = (acc.realizedPnl[cur] ?: 0.0) + (q.price - p.avgCost) * qty - fee
        p.qty -= qty
        var remain = qty
        for (l in p.lots) {
            val take = min(l.qty, remain)
            l.qty -= take; remain -= take
            if (remain == 0) break
        }
        p.lots = ArrayList(p.lots.filter { it.qty > 0 })
        if (p.qty <= 0) acc.positions.remove(q.code)
        val t = Trade()
        t.time = now(); t.code = q.code; t.name = q.name; t.side = "sell"; t.cur = cur
        t.price = q.price; t.qty = qty; t.amount = amt; t.fee = fee
        t.pnl = (q.price - p.avgCost) * qty - fee
        acc.trades.add(0, t)
        save(); return null
    }

    fun createLimit(side: String, q: Quote, price: Double, qty: Int): String? {
        val cur = Mk.currency(q.code)
        val o = Order()
        o.id = "O" + System.currentTimeMillis() + (100 + rnd.nextInt(900))
        o.side = side; o.code = q.code; o.name = q.name; o.cur = cur
        o.price = price; o.qty = qty; o.created = now()
        if (side == "buy") {
            val frozen = price * qty + Mk.buyFee(q.code, price * qty)
            if (acc.availableCash(cur) < frozen - 1e-6) return "该市场可用资金不足"
            acc.frozenCash[cur] = (acc.frozenCash[cur] ?: 0.0) + frozen
            o.frozenCash = frozen
        } else {
            val p = acc.positions[q.code] ?: return "可卖数量不足"
            if (available(p) < qty) return if (Mk.isT1(q.code)) "可卖数量不足(T+1)" else "可卖数量不足"
            p.frozen += qty; o.frozenQty = qty
        }
        acc.orders.add(0, o)
        save(); return null
    }

    fun cancelOrder(id: String): Boolean {
        for (o in acc.orders) {
            if (o.id == id && o.status == "pending") {
                o.status = "canceled"; o.finished = now()
                if (o.frozenCash > 0) {
                    val c = o.cur
                    acc.frozenCash[c] = max((acc.frozenCash[c] ?: 0.0) - o.frozenCash, 0.0)
                    o.frozenCash = 0.0
                }
                if (o.frozenQty > 0) {
                    acc.positions[o.code]?.let { it.frozen = max(it.frozen - o.frozenQty, 0) }
                    o.frozenQty = 0
                }
                save(); return true
            }
        }
        return false
    }

    fun pendingCodes(): List<String> {
        val r = ArrayList<String>()
        for (o in acc.orders) if (o.status == "pending" && !r.contains(o.code)) r.add(o.code)
        return r
    }

    fun matchOrders(quotes: Map<String, Quote>): Boolean {
        var changed = false
        for (o in acc.orders) {
            if (o.status != "pending") continue
            val q = quotes[o.code] ?: continue
            if (q.price <= 0) continue
            if (o.side == "buy" && q.price <= o.price + 1e-9) {
                val c = o.cur
                acc.frozenCash[c] = max((acc.frozenCash[c] ?: 0.0) - o.frozenCash, 0.0)
                o.frozenCash = 0.0
                val err = fillBuy(q, o.qty)
                o.status = if (err == null) "filled" else "canceled"
                if (err == null) o.filledPrice = f2(q.price)
                o.finished = now(); changed = true
            } else if (o.side == "sell" && q.price >= o.price - 1e-9) {
                acc.positions[o.code]?.let { it.frozen = max(it.frozen - o.frozenQty, 0) }
                o.frozenQty = 0
                val err = fillSell(q, o.qty)
                o.status = if (err == null) "filled" else "canceled"
                if (err == null) o.filledPrice = f2(q.price)
                o.finished = now(); changed = true
            }
        }
        if (changed) save()
        return changed
    }

    fun reset() { acc = Account(); save() }

    /**
     * 货币互通: 按 (from→to) 交叉汇率换汇, 收取 0.2% 点差(模拟真实换汇成本)。
     */
    fun exchange(from: String, to: String, amt: Double): String? {
        if (from == to) return "请选择两种不同货币"
        if (amt <= 0) return "金额无效"
        if ((acc.cash[from] ?: 0.0) < amt - 1e-6) return "可用余额不足"
        val recv = amt * (Fx.rate(from) / Fx.rate(to)) * (1 - 0.002)
        acc.cash[from] = (acc.cash[from] ?: 0.0) - amt
        acc.cash[to] = (acc.cash[to] ?: 0.0) + recv
        save()
        return null
    }
}

/* ================= 行情网络层 ================= */

class Market {
    // v_sh600519 / v_hk00700 / v_usAAPL
    private val pQt = Pattern.compile("v_((?:sh|sz|bj|hk|us)[A-Za-z0-9.]*)=\"([^\"]*)\"")
    private val pHint = Pattern.compile("v_hint=\"([^\"]*)\"")

    private fun httpGet(url: String, referer: String?): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
        if (referer != null) c.setRequestProperty("Referer", referer)
        val inp = c.getInputStream()
        val bo = ByteArrayOutputStream()
        val buf = ByteArray(8192); var n: Int
        while (inp.read(buf).also { n = it } > 0) bo.write(buf, 0, n)
        inp.close()
        return bo.toString("GBK")
    }

    fun marketOpen(): Boolean {
        val c = java.util.Calendar.getInstance()
        val dow = c.get(java.util.Calendar.DAY_OF_WEEK)
        if (dow == java.util.Calendar.SATURDAY || dow == java.util.Calendar.SUNDAY) return false
        val m = c.get(java.util.Calendar.HOUR_OF_DAY) * 100 + c.get(java.util.Calendar.MINUTE)
        return (m in 930..1129) || (m in 1300..1459)
    }

    fun fetchQuotes(codes: List<String>): Map<String, Quote> {
        val out = HashMap<String, Quote>()
        if (codes.isEmpty()) return out
        try {
            val txt = httpGet("https://qt.gtimg.cn/q=" + codes.joinToString(","), "https://finance.qq.com/")
            val m = pQt.matcher(txt)
            while (m.find()) {
                val f = m.group(2)!!.split("~")
                if (f.size < 36) continue
                val code = m.group(1)!!
                val q = Quote()
                q.code = code; q.name = f[1]
                q.price = num(f[3]); q.prevClose = num(f[4]); q.open = num(f[5])
                q.high = num(f[33]); q.low = num(f[34])
                q.change = num(f[31]); q.changePct = num(f[32])
                q.turnover = num(f[38]); q.marketCap = num(f[45])
                q.time = f[30]
                when (Mk.market(code)) {
                    "HK" -> { q.lot = f.getOrNull(60)?.toDoubleOrNull()?.toInt() ?: 0 }
                    "CN" -> { q.lot = 100; q.limitUp = num(f[47]); q.limitDown = num(f[48]) }
                    else -> { q.lot = 1 }
                }
                if (q.price > 0) out[q.code] = q
            }
        } catch (_: Exception) {}
        return out
    }

    private fun num(s: String) = s.toDoubleOrNull() ?: 0.0

    /** 日K收盘价序列: A股/港股走腾讯 ifzq, 美股走新浪(腾讯对美股只返回2根K线导致曲线为直线) */
    fun fetchSpark(code: String): List<Float>? =
        if (Mk.market(code) == "US") sparkUs(code) else sparkIfzq(code)

    private fun sparkIfzq(code: String): List<Float>? {
        return try {
            val txt = httpGet(
                "https://web.ifzq.gtimg.cn/appstock/app/kline/kline?param=$code,day,,,48",
                "https://gu.qq.com/")
            val node = JSONObject(txt).getJSONObject("data").getJSONObject(code)
            val arr = node.optJSONArray("day") ?: node.optJSONArray("qfqday") ?: return null
            val r = ArrayList<Float>()
            val start = max(0, arr.length() - 48)
            for (i in start until arr.length()) {
                val row = arr.getJSONArray(i)
                val c = row.optString(2).toFloatOrNull()
                if (c != null) r.add(c)
            }
            if (r.size >= 2) r else null
        } catch (_: Exception) { null }
    }

    /** 新浪美股日K: 完整历史 JSON, 取最近 48 根收盘价 */
    private fun sparkUs(code: String): List<Float>? {
        return try {
            val sym = code.substring(2)
            val txt = httpGet(
                "https://stock.finance.sina.com.cn/usstock/api/jsonp_v2.php/var%20_x=/US_MinKService.getDailyK?symbol=$sym",
                "https://finance.sina.com.cn/")
            val i = txt.indexOf('['); val j = txt.lastIndexOf(']')
            if (i < 0 || j <= i) return null
            val arr = JSONArray(txt.substring(i, j + 1))
            val r = ArrayList<Float>()
            val start = max(0, arr.length() - 48)
            for (k in start until arr.length()) {
                val c = arr.getJSONObject(k).optString("c").toFloatOrNull()
                if (c != null) r.add(c)
            }
            if (r.size >= 2) r else null
        } catch (_: Exception) { null }
    }

    fun search(kw: String): List<Quote> {
        val r = ArrayList<Quote>()
        try {
            val txt = httpGet(
                "https://smartbox.gtimg.cn/s3/?v=2&q=" + URLEncoder.encode(kw, "UTF-8") + "&t=all",
                null)
            val m = pHint.matcher(txt)
            if (!m.find()) return r
            for (entry in m.group(1)!!.split("^")) {
                val f = entry.trim().split("~")
                if (f.size >= 3 && f[0] in listOf("sh", "sz", "bj", "hk", "us")) {
                    val q = Quote()
                    q.code = Mk.normCode(f[0] + f[1]); q.name = f[2]
                    r.add(q)
                    if (r.size >= 10) break
                }
            }
        } catch (_: Exception) {}
        return r
    }
}

/* ================= 应用状态 ================= */

class Store(val ctx: Context) {
    val engine = Engine(ctx)
    val market = Market()
    val ui = Handler(Looper.getMainLooper())

    val quotes = mutableStateMapOf<String, Quote>()
    var marketOpen by mutableStateOf(false)
    var tick by mutableStateOf(0)
    var spark by mutableStateOf<List<Float>?>(null)
    var currentCode by mutableStateOf("sh600519")
    var side by mutableStateOf("buy")
    var trQty by mutableStateOf(0)
    var trPrice by mutableStateOf(0.0)
    var otMode by mutableStateOf("o")
    var confirmPhase by mutableStateOf(0)   // 0无 1处理中 2成功 3失败
    var confirmText by mutableStateOf("")
    var confirmSub by mutableStateOf("")
    var toastMsg by mutableStateOf<String?>(null)
    val searchResults = mutableStateListOf<Quote>()
    var curChapter by mutableStateOf(0)
    var playDays by mutableStateOf(0)   // 游玩天数(从0开始, 按自然日递增)

    var watch: MutableList<String> = mutableListOf(
        "sh600519", "sz300750", "hk00700", "usAAPL", "sh510300", "sz000001")

    val prefs = ctx.getSharedPreferences("sim", Context.MODE_PRIVATE)

    init {
        val saved = prefs.getString("watch", null)
        if (saved != null && saved.isNotBlank()) watch = saved.split(",").toMutableList()
        // 游玩天数: 首次启动记录日期, 之后按自然日从0递增 (独立key, 不影响存档)
        val df = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
        val todayStr = df.format(Date())
        val firstDay = prefs.getString("first_day", null)
        if (firstDay == null) {
            prefs.edit().putString("first_day", todayStr).apply()
            playDays = 0
        } else {
            playDays = try {
                val t1 = df.parse(firstDay)!!.time; val t2 = df.parse(todayStr)!!.time
                ((t2 - t1) / 86400000L).toInt().coerceAtLeast(0)
            } catch (_: Exception) { 0 }
        }
        // 主题种子色 (独立key, 不影响存档)
        applyTheme(ThemeSeed.fromId(prefs.getString("theme_seed", null) ?: "blue"))
    }

    fun setTheme(s: ThemeSeed) {
        applyTheme(s)
        prefs.edit().putString("theme_seed", s.id).apply()
    }

    fun saveWatch() { prefs.edit().putString("watch", watch.joinToString(",")).apply() }

    fun addWatch(code: String) {
        val c = Mk.normCode(code)
        if (!watch.contains(c)) { watch.add(0, c); saveWatch(); tick++ }
    }

    fun removeWatch(code: String) {
        watch.remove(code); saveWatch(); tick++
    }

    fun allCodes(): List<String> {
        val s = LinkedHashSet<String>()
        s.add("sh000001"); s.add("sz399001"); s.add("sz399006")
        s.addAll(watch); s.add(currentCode)
        s.addAll(engine.acc.positions.keys)
        return s.toList()
    }

    fun toast(m: String) { toastMsg = m }

    fun refreshQuotesAsync() {
        Fx.refreshIfNeeded()   // 每5分钟拉一次实时汇率
        val codes = allCodes()
        Thread {
            val q = market.fetchQuotes(codes)
            val open = market.marketOpen()
            ui.post {
                quotes.putAll(q)
                marketOpen = open
            }
        }.start()
    }

    fun matchAsync() {
        Thread {
            val pend = engine.pendingCodes()
            if (pend.isEmpty()) return@Thread
            val q = market.fetchQuotes(pend)
            if (q.isNotEmpty() && engine.matchOrders(q)) ui.post { tick++ }
        }.start()
    }

    fun loadSparkAsync() {
        val code = currentCode
        spark = null
        Thread {
            val d = market.fetchSpark(code)
            if (d != null) ui.post { if (currentCode == code) spark = d }
        }.start()
    }

    fun searchAsync(kw: String, after: () -> Unit) {
        Thread {
            val r = market.search(kw)
            ui.post {
                searchResults.clear(); searchResults.addAll(r)
                after()
            }
        }.start()
    }

    fun setConfirm(phase: Int, text: String, sub: String) {
        confirmPhase = phase; confirmText = text; confirmSub = sub
    }

    fun hideConfirmLater() {
        Thread { Thread.sleep(1400); ui.post { setConfirm(0, "", "") } }.start()
    }
}

/* ================= Activity ================= */

class MainActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var running = true

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val store = Store(applicationContext)
        setContent { WearApp(store) }
        loopQuotes(store)
        loopMatch(store)
    }

    private fun loopQuotes(store: Store) {
        if (!running) return
        store.refreshQuotesAsync()
        handler.postDelayed({ loopQuotes(store) }, 3000)
    }

    private fun loopMatch(store: Store) {
        if (!running) return
        store.matchAsync()
        handler.postDelayed({ loopMatch(store) }, 2000)
    }

    override fun onDestroy() { running = false; super.onDestroy() }
}

/* ================= UI (Wear OS 官方组件) ================= */

@Composable
fun WearApp(store: Store) {
    val navController = rememberSwipeDismissableNavController()

    MaterialTheme(colors = SchemeState.colors) {
        Box(Modifier.fillMaxSize()) {
            Scaffold(
                timeText = { if (store.confirmPhase == 0) TimeText() },
                vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) }
            ) {
                SwipeDismissableNavHost(navController, startDestination = "watchlist") {
                    composable("watchlist") { WatchlistScreen(store, navController) }
                    composable("detail") { DetailScreen(store, navController) }
                    composable("trade") { TradeScreen(store, navController) }
                    composable("positions") { PositionsScreen(store, navController) }
                    composable("orders") { OrdersScreen(store, navController) }
                    composable("manage") { ManageScreen(store, navController) }
                    composable("tutorial") { TutorialScreen(store, navController) }
                    composable("chapter") { ChapterScreen(store) }
                    composable("fx") { ExchangeScreen(store, navController) }
                }
            }
            ConfirmOverlay(store)
            ToastBar(store)
        }
    }
    LaunchedEffect(Unit) { store.refreshQuotesAsync(); store.loadSparkAsync() }
}

/* ---- 自选行情 ---- */
@Composable
fun WatchlistScreen(store: Store, nav: androidx.navigation.NavHostController) {
    val listState = rememberScalingLazyListState()
    val t = store.tick
    val ctx = LocalContext.current
    ScalingLazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item { ListHeader { Text("自选行情", fontSize = 16.sp, fontWeight = FontWeight.Medium) } }
        item {
            Text(
                (if (store.marketOpen) "A股交易中" else "A股未开盘") + " · 模拟第 ${store.playDays} 天",
                fontSize = 10.sp, color = Dim
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                CompactChip(
                    onClick = { buzz(ctx); nav.navigate("positions") },
                    label = { Text("资产", fontSize = 11.sp) },
                    colors = ChipDefaults.secondaryChipColors(
                        backgroundColor = SurfHigh, contentColor = Pri)
                )
                CompactChip(
                    onClick = { buzz(ctx); nav.navigate("orders") },
                    label = { Text("委托", fontSize = 11.sp) },
                    colors = ChipDefaults.secondaryChipColors(
                        backgroundColor = SurfHigh, contentColor = Pri)
                )
                CompactChip(
                    onClick = { buzz(ctx); nav.navigate("fx") },
                    label = { Text("换汇", fontSize = 11.sp) },
                    colors = ChipDefaults.secondaryChipColors(
                        backgroundColor = SurfHigh, contentColor = Gold)
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                CompactChip(
                    onClick = { buzz(ctx); nav.navigate("manage") },
                    label = { Text("自选", fontSize = 11.sp) },
                    colors = ChipDefaults.secondaryChipColors(
                        backgroundColor = SurfHigh, contentColor = Pri)
                )
                CompactChip(
                    onClick = { buzz(ctx); nav.navigate("tutorial") },
                    label = { Text("教程", fontSize = 11.sp) },
                    colors = ChipDefaults.secondaryChipColors(
                        backgroundColor = SurfHigh, contentColor = Gold)
                )
            }
        }
        items(store.watch.size) { i ->
            val code = store.watch[i]
            val q = store.quotes[code]
            StackedCard(listState, i, onClick = {
                buzz(ctx); store.currentCode = code; store.loadSparkAsync()
                nav.navigate("detail")
            }) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(q?.name ?: code, fontSize = 14.sp, color = Color.White)
                    Text(
                        q?.let { signed(it.changePct) + "%" } ?: "—",
                        fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        color = upDownColor(q?.changePct ?: 0.0)
                    )
                }
                Text(
                    q?.let { Mk.sym(code) + f2(it.price) } ?: "—",
                    fontSize = 11.sp, color = TxtMid
                )
            }
        }
        item {
            Text("A股 · 港股 · 美股 · ETF\n在「自选」页搜索添加", fontSize = 10.sp, color = Dim,
                textAlign = TextAlign.Center)
        }
    }
}

/* ---- 个股详情 (ScalingLazyColumn 适配圆形屏幕, 内容多时自动弧形滚动不溢出) ---- */
@Composable
fun DetailScreen(store: Store, nav: androidx.navigation.NavHostController) {
    val t = store.tick
    val code = store.currentCode
    val q = store.quotes[code]
    val ctx = LocalContext.current
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    Mk.marketName(code) + if (Mk.isFund(code)) "·基金" else "",
                    fontSize = 9.sp, color = OnPri,
                    modifier = Modifier.background(Pri, RoundedCornerShape(100))
                        .padding(horizontal = 6.dp, vertical = 1.dp)
                )
                Text(q?.name ?: code, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = Color.White)
            }
        }
        item {
            Text(
                if (q != null) Mk.sym(code) + f2(q.price) else "—",
                fontSize = 34.sp, fontWeight = FontWeight.Medium,
                color = upDownColor(q?.changePct ?: 0.0)
            )
        }
        item {
            Text(
                if (q != null) "${signed(q.change)}  ${signed(q.changePct)}%" else "",
                fontSize = 14.sp, fontWeight = FontWeight.Medium,
                color = upDownColor(q?.changePct ?: 0.0)
            )
        }
        item {
            Sparkline(store.spark, (q?.changePct ?: 0.0) >= 0,
                Modifier.fillMaxWidth().height(54.dp))
        }
        item {
            val capUnit = if (Mk.market(code) == "CN") "亿¥" else if (Mk.market(code) == "HK") "亿HK$" else "亿$"
            val rows = mutableListOf(
                "今开" to (q?.let { f2(it.open) } ?: "—"), "昨收" to (q?.let { f2(it.prevClose) } ?: "—"),
                "最高" to (q?.let { f2(it.high) } ?: "—"), "最低" to (q?.let { f2(it.low) } ?: "—"),
                "换手" to (q?.let { f2(it.turnover) + "%" } ?: "—"),
                "市值" to (q?.let { f2(it.marketCap, 0) + capUnit } ?: "—"))
            if (Mk.market(code) == "HK") rows.add("每手" to (q?.let { "${it.lot}股" } ?: "—"))
            if (Mk.market(code) == "CN") {
                rows.add("涨停" to (q?.let { f2(it.limitUp) } ?: "—"))
                rows.add("跌停" to (q?.let { f2(it.limitDown) } ?: "—"))
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (chunk in rows.chunked(2)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        for ((k, v) in chunk) {
                            Row(Modifier.width(116.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(k, fontSize = 11.sp, color = Dim)
                                Text(v, fontSize = 11.sp, color = TxtMid)
                            }
                        }
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        buzz(ctx)
                        store.side = "buy"
                        store.trPrice = q?.price ?: 0.0
                        store.trQty = Mk.lot(code, q)
                        nav.navigate("trade")
                    },
                    colors = ButtonDefaults.buttonColors(backgroundColor = Up, contentColor = Dark1)
                ) { Text("买入", fontSize = 13.sp, fontWeight = FontWeight.Medium) }
                Button(
                    onClick = {
                        buzz(ctx)
                        store.side = "sell"
                        store.trPrice = q?.price ?: 0.0
                        val p = store.engine.acc.positions[code]
                        val avail = p?.let { store.engine.available(it) } ?: 0
                        store.trQty = if (avail >= Mk.lot(code, q)) Mk.lot(code, q) else 0
                        if (avail <= 0) store.toast(if (Mk.isT1(code)) "今日无可用数量(T+1)" else "暂无持仓")
                        nav.navigate("trade")
                    },
                    colors = ButtonDefaults.buttonColors(backgroundColor = Down, contentColor = Dark2)
                ) { Text("卖出", fontSize = 13.sp, fontWeight = FontWeight.Medium) }
            }
        }
    }
}

@Composable
fun Sparkline(data: List<Float>?, up: Boolean, modifier: Modifier) {
    Canvas(modifier) {
        if (data == null || data.size < 2) return@Canvas
        val mn = data.min(); val mx = data.max(); val rng = (mx - mn).takeIf { it > 0 } ?: 1f
        val col = if (up) Up else Down
        val path = Path()
        data.forEachIndexed { i, v ->
            val x = i.toFloat() / (data.size - 1) * size.width
            val y = size.height - 6f - (v - mn) / rng * (size.height - 12f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, col, style = Stroke(3f, cap = StrokeCap.Round))
        val fill = Path().apply {
            addPath(path)
            lineTo(size.width, size.height); lineTo(0f, size.height); close()
        }
        drawPath(fill, col.copy(alpha = 0.15f))
    }
}

/* ---- 交易 ---- */
@Composable
fun TradeScreen(store: Store, nav: androidx.navigation.NavHostController) {
    val t = store.tick
    val code = store.currentCode
    val q = store.quotes[code]
    val ctx = LocalContext.current
    val buy = store.side == "buy"
    val lot = Mk.lot(code, q)
    val amt = store.trPrice * store.trQty
    val fee = if (amt > 0)
        (if (buy) Mk.buyFee(code, amt) else Mk.sellFee(code, amt)) else 0.0
    val curName = if (Mk.market(code) == "CN") (if (Mk.isFund(code)) "元(份)" else "元")
        else if (Mk.market(code) == "HK") "港元" else "美元"
    // 当前市场可用资金
    val cashAvail = store.engine.acc.availableCash(Mk.currency(code))

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    (if (buy) "买入" else "卖出") + " · " + (q?.name ?: code),
                    fontSize = 15.sp, fontWeight = FontWeight.Medium,
                    color = if (buy) Up else Down
                )
                Text(
                    Mk.marketName(code) + if (Mk.isFund(code)) "·基金" else "" +
                        (if (store.marketOpen) "" else " · 按最新价模拟"),
                    fontSize = 10.sp, color = Dim
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CompactChip(
                    onClick = { store.side = "buy"; store.trPrice = q?.price ?: store.trPrice },
                    label = { Text("买入", fontSize = 11.sp) },
                    colors = if (buy) ChipDefaults.secondaryChipColors(
                        backgroundColor = Up, contentColor = Dark1)
                    else ChipDefaults.secondaryChipColors()
                )
                CompactChip(
                    onClick = { store.side = "sell"; store.trPrice = q?.price ?: store.trPrice },
                    label = { Text("卖出", fontSize = 11.sp) },
                    colors = if (!buy) ChipDefaults.secondaryChipColors(
                        backgroundColor = Down, contentColor = Dark2)
                    else ChipDefaults.secondaryChipColors()
                )
            }
        }
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("价格 ($curName)", fontSize = 10.sp, color = Dim)
                Text(f2(store.trPrice), fontSize = 28.sp, fontWeight = FontWeight.Medium, color = Color.White)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CompactChip(onClick = { store.trPrice = max(0.01, round2(store.trPrice - 0.01)) },
                        label = { Text("−0.01", fontSize = 11.sp) })
                    CompactChip(onClick = { store.trPrice = max(0.01, round2(store.trPrice + 0.01)) },
                        label = { Text("+0.01", fontSize = 11.sp) })
                }
            }
        }
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("数量" + (if (lot > 1) " (每手$lot)" else " (股)"), fontSize = 10.sp, color = Dim)
                Text("${store.trQty}", fontSize = 26.sp, fontWeight = FontWeight.Medium, color = Color.White)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CompactChip(onClick = { store.trQty = max(0, store.trQty - lot) },
                        label = { Text("−$lot", fontSize = 11.sp) })
                    CompactChip(onClick = {
                        if (!buy) {
                            val p = store.engine.acc.positions[code]
                            val avail = p?.let { store.engine.available(it) } ?: 0
                            if (store.trQty + lot > avail) {
                                store.toast(if (Mk.isT1(code)) "可卖 $avail (T+1)" else "可卖 $avail")
                                return@CompactChip
                            }
                        }
                        store.trQty += lot
                    }, label = { Text("+$lot", fontSize = 11.sp) })
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("25%" to 0.25, "50%" to 0.5, "全部" to 1.0).forEach { (label, r) ->
                        CompactChip(onClick = {
                            if (buy) {
                                if (store.trPrice > 0) {
                                    // 预留约0.2%费用, 向下取整到手
                                    val raw = cashAvail * r / (store.trPrice * 1.002)
                                    store.trQty = if (lot > 1) (raw.toInt() / lot) * lot else raw.toInt()
                                }
                            } else {
                                val p = store.engine.acc.positions[code]
                                if (p == null) store.toast("当前无持仓")
                                else {
                                    val avail = store.engine.available(p)
                                    store.trQty = if (lot > 1) (avail * r).toInt() / lot * lot
                                        else (avail * r).toInt()
                                }
                            }
                        }, label = { Text(label, fontSize = 11.sp) })
                    }
                }
            }
        }
        item {
            Text(
                Mk.sym(code) + f2(amt) + " · 费用 " + Mk.sym(code) + f2(fee) +
                    " · 可用 " + Mk.sym(code) + shortNum(cashAvail),
                fontSize = 11.sp, color = Dim
            )
        }
        item {
            Button(
                onClick = {
                    if (store.trQty <= 0) { store.toast("数量无效"); return@Button }
                    if (lot > 1 && store.trQty % lot != 0) { store.toast("数量须为$lot 整数倍"); return@Button }
                    if (store.trPrice <= 0) { store.toast("价格无效"); return@Button }
                    buzz(ctx, 15)
                    submitTrade(store) {
                        nav.navigate("orders") { popUpTo("watchlist") }
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = if (buy) Up else Down,
                    contentColor = if (buy) Dark1 else Dark2
                )
            ) {
                Text(
                    "确认" + if (buy) "买入" else "卖出",
                    fontSize = 14.sp, fontWeight = FontWeight.Medium
                )
            }
            Text(Mk.ruleText(code, q), fontSize = 9.sp, color = Dim)
            Spacer(Modifier.height(30.dp))
        }
    }
}

fun submitTrade(store: Store, onFinished: () -> Unit) {
    val code = store.currentCode
    val side = store.side
    val price = store.trPrice
    val qty = store.trQty
    val name = store.quotes[code]?.name ?: code
    store.setConfirm(1, if (side == "buy") "正在买入" else "正在卖出", "$name ${qty}股 @${f2(price)}")
    Thread {
        Thread.sleep(500)
        val quotes = store.market.fetchQuotes(listOf(code))
        val q = quotes[code]
        if (q == null) {
            store.ui.post {
                store.setConfirm(3, "委托失败", "行情获取失败")
                store.hideConfirmLater()
            }
            return@Thread
        }
        val err = if (abs(q.price - price) < 0.001) {
            if (side == "buy") store.engine.fillBuy(q, qty) else store.engine.fillSell(q, qty)
        } else {
            store.engine.createLimit(side, q, price, qty)
        }
        store.ui.post {
            if (err == null) {
                store.setConfirm(2, if (side == "buy") "已提交买入" else "已提交卖出", "滑动返回上一页")
                store.trQty = 0; store.tick++
            } else {
                store.setConfirm(3, "委托失败", err)
            }
            store.hideConfirmLater()
            store.ui.post { onFinished() }
        }
    }.start()
}

/* ---- 资产持仓 ---- */
@Composable
fun PositionsScreen(store: Store, nav: androidx.navigation.NavHostController) {
    val listState = rememberScalingLazyListState()
    val t = store.tick
    val ctx = LocalContext.current
    val acc = store.engine.acc
    val total = acc.totalCny(store.quotes)
    val baseline = Account.initialTotalCny()
    val cashLine = Account.CURS.map { c ->
        val sym = when (c) { "HKD" -> "HK$"; "USD" -> "$"; else -> "¥" }
        sym + shortNum(acc.availableCash(c))
    }.joinToString(" · ")
    ScalingLazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item { ListHeader { Text("资产持仓", fontSize = 16.sp, fontWeight = FontWeight.Medium) } }
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("总资产 ¥${f2(total, 0)}", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White)
                Text(
                    "总盈亏 ${signed(total - baseline, 0)}",
                    fontSize = 12.sp, color = upDownColor(total - baseline)
                )
                Text("可用 $cashLine", fontSize = 10.sp, color = Dim,
                    textAlign = TextAlign.Center)
                Text("实时汇率 1USD=¥${f2(Fx.rate("USD"), 3)} · 1HKD=¥${f2(Fx.rate("HKD"), 4)}",
                    fontSize = 9.sp, color = Dim)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CompactChip(
                    onClick = { buzz(ctx); nav.navigate("fx") },
                    label = { Text("换汇", fontSize = 11.sp) },
                    colors = ChipDefaults.secondaryChipColors(
                        backgroundColor = SurfHigh, contentColor = Gold)
                )
                CompactChip(
                    onClick = { resetFlow(store) },
                    label = { Text("重置账户", fontSize = 11.sp) },
                    colors = ChipDefaults.secondaryChipColors(
                        backgroundColor = SurfHigh, contentColor = Up)
                )
            }
        }
        val pos = acc.positions.values.toList()
        if (pos.isEmpty()) {
            item {
                Text("暂无持仓\n去买入第一只股票吧", fontSize = 12.sp, color = Dim,
                    textAlign = TextAlign.Center)
            }
        }
        items(pos.size) { i ->
            val p = pos[i]
            val price = store.quotes[p.code]?.price ?: 0.0
            val pnl = (price - p.avgCost) * p.qty
            val pnlPct = if (p.avgCost > 0) (price / p.avgCost - 1) * 100 else 0.0
            val lot = Mk.lot(p.code, store.quotes[p.code])
            StackedCard(listState, i, onClick = {
                buzz(ctx)
                store.currentCode = p.code
                store.side = "sell"
                store.trPrice = store.quotes[p.code]?.price ?: 0.0
                val avail = store.engine.available(p)
                store.trQty = if (avail >= lot) lot else 0
                if (avail <= 0) store.toast(if (Mk.isT1(p.code)) "今日无可用卖出(T+1)" else "无可卖数量")
                nav.navigate("trade")
            }) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(p.name, fontSize = 14.sp, color = Color.White)
                    Text("${signed(pnl, 0)} ${signed(pnlPct)}%",
                        fontSize = 12.sp, fontWeight = FontWeight.Medium, color = upDownColor(pnl))
                }
                Text("${p.qty}股 · 可卖${store.engine.available(p)} · 成本${Mk.sym(p.code)}${f2(p.avgCost)}",
                    fontSize = 10.sp, color = TxtMid)
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

fun resetFlow(store: Store) {
    store.setConfirm(1, "重置账户", "恢复初始资金")
    Thread {
        Thread.sleep(600)
        store.engine.reset()
        store.ui.post {
            store.setConfirm(2, "已重置", "¥100万 + HK$10万 + $1万")
            store.tick++
            store.hideConfirmLater()
        }
    }.start()
}

/* ---- 委托/成交 ---- */
@Composable
fun OrdersScreen(store: Store, nav: androidx.navigation.NavHostController) {
    val listState = rememberScalingLazyListState()
    val t = store.tick
    val ctx = LocalContext.current
    val acc = store.engine.acc
    ScalingLazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item { ListHeader { Text("委托 · 成交", fontSize = 16.sp, fontWeight = FontWeight.Medium) } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CompactChip(
                    onClick = { store.otMode = "o" },
                    label = { Text("委托", fontSize = 11.sp) },
                    colors = if (store.otMode == "o") ChipDefaults.secondaryChipColors(
                        backgroundColor = Pri, contentColor = OnPri)
                    else ChipDefaults.secondaryChipColors()
                )
                CompactChip(
                    onClick = { store.otMode = "t" },
                    label = { Text("成交", fontSize = 11.sp) },
                    colors = if (store.otMode == "t") ChipDefaults.secondaryChipColors(
                        backgroundColor = Pri, contentColor = OnPri)
                    else ChipDefaults.secondaryChipColors()
                )
            }
        }
        if (store.otMode == "o") {
            val os = acc.orders
            if (os.isEmpty()) {
                item { Text("暂无委托", fontSize = 12.sp, color = Dim) }
            }
            items(os.size) { i ->
                val o = os[i]
                val st = when (o.status) {
                    "pending" -> "待成交·点按撤单" to Gold
                    "filled" -> "已成交 @${o.filledPrice}" to Down
                    else -> "已撤单" to Dim
                }
                StackedCard(listState, i,
                    onClick = if (o.status == "pending") { {
                        buzz(ctx)
                        store.setConfirm(1, "撤销委托", "")
                        Thread {
                            Thread.sleep(500)
                            val ok = store.engine.cancelOrder(o.id)
                            store.ui.post {
                                store.setConfirm(if (ok) 2 else 3, if (ok) "已撤单" else "撤单失败", "")
                                store.tick++
                                store.hideConfirmLater()
                            }
                        }.start()
                    } } else null
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            o.name + " " + if (o.side == "buy") "买" else "卖",
                            fontSize = 14.sp, color = Color.White
                        )
                        Text("${o.qty}股 @${Mk.sym(o.code)}${f2(o.price)}",
                            fontSize = 12.sp, color = TxtMid)
                    }
                    Text(st.first, fontSize = 11.sp, color = st.second)
                }
            }
        } else {
            val ts = acc.trades
            if (ts.isEmpty()) {
                item { Text("暂无成交记录", fontSize = 12.sp, color = Dim) }
            }
            items(ts.size) { i ->
                val tr = ts[i]
                StackedCard(listState, i) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            tr.name + " " + if (tr.side == "buy") "买入" else "卖出",
                            fontSize = 14.sp, color = Color.White
                        )
                        Text(
                            if (!tr.pnl.isNaN()) signed(tr.pnl, 0)
                            else Mk.sym(tr.code) + f2(tr.amount, 0),
                            fontSize = 12.sp, fontWeight = FontWeight.Medium,
                            color = if (!tr.pnl.isNaN()) upDownColor(tr.pnl) else TxtMid
                        )
                    }
                    Text("${tr.qty}股 @${Mk.sym(tr.code)}${f2(tr.price)} · ${tr.time.take(16).substringAfter(' ')}",
                        fontSize = 10.sp, color = TxtMid)
                }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

/* ---- 自选管理 ---- */
@Composable
fun ManageScreen(store: Store, nav: androidx.navigation.NavHostController) {
    val listState = rememberScalingLazyListState()
    val t = store.tick
    val activity = LocalContext.current as? Activity
    val ctx = LocalContext.current
    val popular = listOf(
        "sh600519" to "贵州茅台", "sz300750" to "宁德时代", "sh601318" to "中国平安",
        "sz000858" to "五粮液", "sh688981" to "中芯国际", "sz300059" to "东方财富",
        "sh510300" to "沪深300ETF", "sh513100" to "纳指ETF", "sz159915" to "创业板ETF",
        "hk00700" to "腾讯控股", "hk09988" to "阿里巴巴", "usAAPL" to "苹果",
        "usTSLA" to "特斯拉", "usNVDA" to "英伟达")
    ScalingLazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item { ListHeader { Text("自选管理", fontSize = 16.sp, fontWeight = FontWeight.Medium) } }
        item {
            CompactChip(
                onClick = { showSearchDialog(store, activity) },
                label = { Text("🔍 搜索全市场 (A股/港/美/ETF)", fontSize = 10.sp) },
                colors = ChipDefaults.secondaryChipColors(
                    backgroundColor = Pri, contentColor = OnPri)
            )
        }
        item {
            Text("搜索支持: 中文名 / 代码 / 拼音\n如 腾讯 / 00700 / AAPL / 纳指",
                fontSize = 9.sp, color = Dim, textAlign = TextAlign.Center)
        }
        // 主题种子色选择器 (来自 AppOpsPro 的种子配色)
        item { ListHeader { Text("主题种子色", fontSize = 12.sp, color = TxtMid) } }
        val themeSeeds = ThemeSeed.colorSeeds.chunked(5)
        items(themeSeeds.size) { ri ->
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                for (s in themeSeeds[ri]) {
                    val selected = ThemeCurrent == s
                    Box(
                        Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(s.seed ?: Color(0xFF8D9199))
                            .border(
                                width = if (selected) 2.5.dp else 1.dp,
                                color = if (selected) Color.White else Dim,
                                shape = CircleShape
                            )
                            .clickable { buzz(ctx); store.setTheme(s) }
                    )
                }
            }
        }
        item { ListHeader { Text("热门标的", fontSize = 12.sp, color = TxtMid) } }
        items(popular.size) { i ->
            val (code, name) = popular[i]
            val added = store.watch.contains(code)
            StackedCard(listState, i, onClick = {
                buzz(ctx)
                if (added) { store.removeWatch(code); store.toast("已移除 $name") }
                else { store.addWatch(code); store.toast("已添加 $name") }
            }) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text(name, fontSize = 14.sp, color = Color.White)
                        Text(Mk.marketName(code) + " · " + code, fontSize = 10.sp, color = Dim)
                    }
                    Switch(checked = added, onCheckedChange = null)
                }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

fun showSearchDialog(store: Store, activity: Activity?) {
    if (activity == null) return
    val input = EditText(activity)
    input.hint = "如: 600519 / 茅台 / 00700 / AAPL"
    input.setPadding(48, 32, 48, 32)
    AlertDialog.Builder(activity)
        .setTitle("搜索添加自选")
        .setView(input)
        .setPositiveButton("搜索") { _, _ ->
            val kw = input.text.toString().trim()
            if (kw.isNotEmpty()) {
                store.searchAsync(kw) {
                    val items = store.searchResults.map {
                        "[${Mk.marketName(it.code)}] ${it.name} (${it.code})"
                    }.toTypedArray()
                    if (items.isEmpty()) {
                        store.toast("无搜索结果")
                    } else {
                        AlertDialog.Builder(activity)
                            .setTitle("选择标的")
                            .setItems(items) { _, w ->
                                val r = store.searchResults[w]
                                store.addWatch(r.code)
                                store.toast("已添加 ${r.name}")
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                }
            }
        }
        .setNegativeButton("取消", null)
        .show()
}

/* ---- 货币互通 · 换汇 ---- */
@Composable
fun ExchangeScreen(store: Store, nav: androidx.navigation.NavHostController) {
    val t = store.tick
    val ctx = LocalContext.current
    val curs = listOf("CNY", "HKD", "USD")
    var from by rememberSaveable { mutableStateOf("CNY") }
    var to by rememberSaveable { mutableStateOf("USD") }
    var amt by rememberSaveable { mutableStateOf(1000.0) }
    val symOf = { c: String -> if (c == "HKD") "HK$" else if (c == "USD") "$" else "¥" }
    val cross = Fx.rate(from) / Fx.rate(to)   // 1单位from = ? to
    val recv = amt * cross * 0.998            // 0.2% 点差(模拟真实换汇成本)

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item { ListHeader { Text("货币互通 · 换汇", fontSize = 15.sp, fontWeight = FontWeight.Medium) } }
        item { Text("实时汇率 · 更新 ${Fx.updated}", fontSize = 10.sp, color = Dim) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CompactChip(
                    onClick = { from = curs[(curs.indexOf(from) + 1) % 3] },
                    label = { Text(symOf(from) + " " + from, fontSize = 12.sp) },
                    colors = ChipDefaults.secondaryChipColors(
                        backgroundColor = SurfHigh, contentColor = Pri)
                )
                Text("→", fontSize = 15.sp, color = Dim)
                CompactChip(
                    onClick = { to = curs[(curs.indexOf(to) + 1) % 3] },
                    label = { Text(symOf(to) + " " + to, fontSize = 12.sp) },
                    colors = ChipDefaults.secondaryChipColors(
                        backgroundColor = SurfHigh, contentColor = Pri)
                )
            }
        }
        item { Text("1${symOf(from)} = ${f2(cross, 4)}${symOf(to)}", fontSize = 12.sp, color = TxtMid) }
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("金额 ${symOf(from)}", fontSize = 10.sp, color = Dim)
                Text(f2(amt, 0), fontSize = 26.sp, fontWeight = FontWeight.Medium, color = Color.White)
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    CompactChip(onClick = { amt = max(0.0, amt - 1000) }, label = { Text("−1000", fontSize = 11.sp) })
                    CompactChip(onClick = { amt += 1000 }, label = { Text("+1000", fontSize = 11.sp) })
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf("25%" to 0.25, "50%" to 0.5, "全部" to 1.0).forEach { (label, r) ->
                    CompactChip(onClick = {
                        amt = round2(((store.engine.acc.cash[from] ?: 0.0) * r))
                    }, label = { Text(label, fontSize = 11.sp) })
                }
            }
        }
        item {
            Text("点差 0.2% · 实收 ≈ ${symOf(to)}${f2(recv)}", fontSize = 11.sp, color = Gold)
            Text("可用 ${symOf(from)}${shortNum(store.engine.acc.cash[from] ?: 0.0)}",
                fontSize = 10.sp, color = Dim)
        }
        item {
            Button(
                onClick = {
                    buzz(ctx, 15)
                    val err = store.engine.exchange(from, to, amt)
                    if (err == null) {
                        store.toast("✓ 已换汇 ${symOf(to)}${f2(recv)}")
                        store.tick++
                    } else store.toast(err)
                },
                colors = ButtonDefaults.buttonColors(backgroundColor = Pri, contentColor = OnPri)
            ) { Text("确认换汇", fontSize = 14.sp, fontWeight = FontWeight.Medium) }
            Spacer(Modifier.height(30.dp))
        }
    }
}

/* ---- 确认动效 ---- */
@Composable
fun ConfirmOverlay(store: Store) {
    if (store.confirmPhase == 0) return
    Box(
        Modifier.fillMaxSize().background(Color(0xF0000000)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (store.confirmPhase == 1) {
                androidx.wear.compose.material.CircularProgressIndicator(
                    modifier = Modifier.size(60.dp),
                    indicatorColor = Pri
                )
            } else {
                val ok = store.confirmPhase == 2
                Text(
                    if (ok) "✓" else "✕", fontSize = 44.sp, fontWeight = FontWeight.Bold,
                    color = if (ok) Down else Up
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(store.confirmText, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White)
            if (store.confirmSub.isNotBlank()) {
                Text(store.confirmSub, fontSize = 11.sp, color = Dim)
            }
        }
    }
}

/* ---- Toast ---- */
@Composable
fun ToastBar(store: Store) {
    val msg = store.toastMsg ?: return
    LaunchedEffect(msg) {
        kotlinx.coroutines.delay(2200)
        if (store.toastMsg == msg) store.toastMsg = null
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Text(
            msg, fontSize = 12.sp, color = Color.White,
            modifier = Modifier
                .padding(bottom = 40.dp)
                .background(SurfHigh, RoundedCornerShape(100))
                .padding(horizontal = 18.dp, vertical = 8.dp)
        )
    }
}

/* ================= 离线炒股教程 (12课) ================= */

class TSection(val h: String, vararg val body: String)
class TChapter(val title: String, val sub: String, val sections: List<TSection>)

object Tutorial {
    val chapters = listOf(
        TChapter("股票是什么", "认识股票: 从零理解第一课", listOf(
            TSection("一句话定义",
                "股票是一家公司所有权的\"份额\"。买入100股贵州茅台, 你就是茅台这家公司的(极小)股东, 公司的厂房、品牌、利润都有你的一份。",
                "上市公司把公司切成几亿甚至几十亿份, 每一份就是一股, 在交易所公开买卖。"),
            TSection("股价为什么涨跌",
                "短期看供需: 想买的人多就涨, 想卖的人多就跌。背后驱动是预期——大家认为公司未来更赚钱, 就愿意出更高价。",
                "长期看价值: 股价长期围绕公司盈利能力波动。业绩持续增长的公司, 股价长期向上; 业绩差的, 迟现原形。"),
            TSection("赚钱的两种方式",
                "① 价差(资本利得): 10元买入15元卖出, 赚5元差价——这是绝大多数散户的主要方式。",
                "② 分红: 公司盈利后把一部分利润分给股东(现金分红/送股)。像茅台、长江电力这类公司常年高分红。"),
            TSection("股票 vs 存款 vs 房子",
                "存款: 安全但收益低, 跑不赢通胀。股票: 长期收益最高的大类资产之一, 但波动大、可能亏钱。关键区别是\"波动\"——好公司拿得住, 时间是朋友。"),
            TSection("必须先记住的一句话",
                "股市不是提款机, 是风险定价机。你能赚到的钱, 来自公司成长+别人犯错的错误定价。先学会不亏大钱, 再谈赚钱。")
        )),
        TChapter("入市前的准备", "开户、资金与心态", listOf(
            TSection("开户流程",
                "年满18岁, 准备身份证+银行卡, 在券商App线上开户, 10分钟搞定。手续费(佣金)可以谈, 一般万2.5左右, 别用默认的高佣金。",
                "A股一个身份证只能开3个沪A账户, 深市不限。港股/美股需要另外开通(港股通需50万门槛, 或港股券商)。"),
            TSection("选券商看什么",
                "① 佣金低; ② App稳定好用; ③ 网点/客服(线下业务方便)。大券商(中信、华泰、东财等)都够用, 新手不必纠结。"),
            TSection("投入多少钱合适",
                "铁律: 只用闲钱! 是3年内确定用不到的钱。",
                "新手建议1-5万起步: 太少(几千元)体验不到仓位管理, 太多(全部积蓄)一次波动就睡不着。"),
            TSection("心态准备",
                "把第一年当作\"学费年\", 目标不是赚钱, 而是建立自己的交易体系和纪律。",
                "先在模拟盘练1-3个月(就像你现在用的这个App), 熟悉规则和操作, 再投真金白银。"),
            TSection("新手最常见的错误开局",
                "借钱炒股 / 满仓一只股票 / 追涨杀跌 / 听消息买卖。这四条踩中任何一条, 大概率第一年就大亏。")
        )),
        TChapter("A股交易规则", "时间、涨跌停、T+1 全搞懂", listOf(
            TSection("交易时间",
                "工作日: 上午 9:30-11:30, 下午 13:00-15:00。9:15-9:25 是集合竞价(可挂单, 9:25撮合出开盘价)。",
                "周末和法定节假日休市。港股: 9:30-12:00, 13:00-16:00; 美股(夏令): 北京时间 21:30-次日4:00。"),
            TSection("T+1 制度",
                "今天买入的股票, 最早明天才能卖出(资金T+0可用, 股票T+1可卖)。当日卖出股票的钱, 当天可以继续买股票。",
                "港股、美股没有T+1, 是T+0, 当天买当天就能卖——所以港美股可以做日内交易。"),
            TSection("最小交易单位",
                "A股必须以100股(一手)的整数倍买卖。港股每手由公司自定(腾讯100股/手, 汇丰400股/手, 中石化5000股/手)。美股最小1股, 甚至支持碎股。"),
            TSection("涨跌停板(仅A股)",
                "主板±10%, 创业板/科创板±20%, 北交所±30%, ST股±5%。新股上市首日不设限(主板首日44%封顶)。",
                "涨停=想买的人排队也买不到; 跌停=想卖的人排队也卖不掉。港美股没有涨跌停, 一天跌90%都可能发生。"),
            TSection("委托方式",
                "市价单: 按当前市场价格立即成交, 快但价格不可控。限价单: 指定价格挂单, 到价才成交, 可控制成本但可能不成交。",
                "本App: 输入现价≈市价单; 输入其他价格=限价单(自动撮合, 可撤单)。"),
            TSection("竞价与撮合",
                "开盘价由集合竞价产生(9:15-9:25)。连续竞价阶段遵循\"价格优先、时间优先\": 买单出价高者先成交, 同价先挂者先成交。")
        )),
        TChapter("交易成本", "隐形的手续费, 吃掉你的利润", listOf(
            TSection("三笔费用",
                "① 佣金: 买卖都收, 万2.5左右, 单笔最低5元; ② 印花税: 仅卖出收, 万分之5(2023年减半后); ③ 过户费: 万分之0.1, 双向。",
                "港股: 佣金+双边印花税0.1%, 成本明显高于A股; 美股: 多为零佣金或按笔收费, 但有汇率兑换成本。"),
            TSection("算一笔账",
                "1万元买入再卖出: 佣金买5元(最低)+卖5元, 印花税卖5元, 过户费约0.2元, 合计约15元, 占本金0.15%。",
                "看起来不多? 如果你每周买卖一次, 一年交易成本≈本金的7.8%! 这就是\"频繁交易是大敌\"的数学原因。"),
            TSection("最低5元陷阱",
                "佣金不足5元按5元收。买1000元的股票, 佣金率实际是0.5%而不是万2.5。小资金频繁小额交易最吃亏。"),
            TSection("降低成本的方法",
                "① 降低交易频率, 拉长持有周期; ② 单笔金额做大(摊薄最低佣金); ③ 开户时就谈好低佣金。"),
            TSection(" ETF 的成本优势",
                "场内ETF免印花税、免过户费, 只收佣金, 且没有最低5元限制的券商也很多。长期定投ETF是成本最低的方式之一。")
        )),
        TChapter("看懂行情界面", "每个数字都是信息", listOf(
            TSection("核心报价",
                "现价: 最新成交价。涨跌/涨跌幅: 相对昨收。今开: 开盘第一笔成交价; 昨收: 昨日收盘价(涨跌的基准)。",
                "最高/最低: 当日波动范围, 反映多空争夺的激烈程度。"),
            TSection("换手率",
                "当日成交量÷流通股本。3%以下冷清, 3%-10%活跃, 10%以上高度活跃(可能有大事发生), 20%+通常是妖股或重大消息。"),
            TSection("总市值",
                "股价×总股本, 公司的\"标价\"。市值=多少亿和股价高低无关——茅台1800元市值2万亿, 某股2元市值也可能几十亿。看公司大小只看市值。"),
            TSection("买卖五档",
                "买一到买五: 愿意买入的排队价格和数量; 卖一到卖五: 愿意卖出的排队。买一价>卖一价不可能, 二者之间的差距叫\"价差\"。",
                "外盘(主动买)大于内盘(主动卖)偏强势, 反之偏弱。但主力会\"对倒\"骗人, 五档只能参考。"),
            TSection("市盈率PE在哪看",
                "行情软件按F10或\"资料\"页查看PE/PB。PE=市值÷年利润, 直观理解\"按当前盈利, 多少年回本\"。这在第8课详细讲。")
        )),
        TChapter("K线与分时图", "读懂价格的脚印", listOf(
            TSection("K线的构成",
                "一根K线记录一个周期(日/周/月)的四个价格: 开盘、收盘、最高、最低。",
                "收盘>开盘=阳线(红色); 收盘<开盘=阴线(绿色)。中间的矩形叫\"实体\", 上下细线叫\"影线\"。"),
            TSection("影线的含义",
                "上影线长: 冲高回落, 上方抛压重。下影线长: 探底回升, 下方有承接。长下影+缩量出现在下跌末期, 常是企稳信号。"),
            TSection("分时图",
                "白线: 当日每分钟价格; 黄线: 均价线。股价在均价线上方运行=强势; 反复跌破均价线=弱势。",
                "尾盘(14:30后)急拉或跳水, 往往有资金意图, 新手别参与尾盘博弈。"),
            TSection("常见K线组合",
                "锤子线(长下影): 见底信号。射击之星(长上影): 见顶信号。吞没形态: 后一根实体完全包住前一根, 反转意味强。",
                "重要提醒: 单根K线只是概率信号, 必须结合位置(高位/低位)和成交量, 准确率才高。"),
            TSection("K线的局限",
                "K线是历史记录不是预言。主力会画K线骗人(诱多/诱空)。新手阶段: 看懂即可, 别拿单根K线当买卖依据。")
        )),
        TChapter("均线与成交量", "趋势和能量的两面", listOf(
            TSection("什么是均线 MA",
                "N日收盘价的平均值连成线。MA5=5日均线(周线级别), MA20=月线, MA60=季线, MA250=年线(牛熊分界)。",
                "本App分时图下方可看到MA5/10/20/30。"),
            TSection("金叉与死叉",
                "短期均线上穿长期均线=金叉, 常视为买入信号; 下穿=死叉, 视为卖出信号。",
                "均线是滞后指标: 趋势行情好用, 震荡行情会被反复\"打脸\"(假金叉/假死叉)。"),
            TSection("多空头排列",
                "MA5>MA10>MA20>MA60 且都向上=多头排列, 趋势向上, 顺势持有。反之为空头排列, 空仓观望。"),
            TSection("量价关系",
                "量是股价的燃料。健康上涨: 价涨量增。危险信号: 高位放量滞涨(有人大量出货)。",
                "缩量下跌: 抛压衰竭, 可能接近底部; 放量下跌: 恐慌或出货, 别急着抄底。"),
            TSection("新手实践建议",
                "只做\"多头排列+放量突破\"形态的股票, 避开\"空头排列\"的。这一条纪律就能避开大部分深套。")
        )),
        TChapter("基本面分析入门", "买股票=买公司", listOf(
            TSection("三大报表",
                "资产负债表(家底)、利润表(赚钱能力)、现金流量表(真金白银)。新手重点看利润表+现金流: 利润可以修饰, 现金流很难造假。",
                "经营现金流长期低于净利润的公司要警惕。"),
            TSection("市盈率 PE",
                "PE=股价÷每股盈利=市值÷净利润。茅台25倍PE=按当前盈利25年回本。",
                "用法: 和自己的历史比(PE百分位)、和同行比。成长股容忍高PE, 银行地产天然低PE。PE为负=亏损, 无意义。"),
            TSection("市净率 PB",
                "PB=股价÷每股净资产。银行、钢铁等重资产行业看PB(0.5-1倍常见); 轻资产公司(互联网)PB意义不大。"),
            TSection("ROE: 巴菲特最爱的指标",
                "ROE=净利润÷净资产, 衡量\"用股东的钱赚钱的效率\"。连续多年ROE>15%的公司, 大概率是优秀公司(茅台、招行常年30%+/15%+)。"),
            TSection("财报去哪看",
                "巨潮资讯网(官方)、各行情软件F10。重点读: 主营构成、毛利率变化、应收账款、商誉。年报太厚就看\"摘要\"。"),
            TSection("基本面选股简化流程",
                "① 行业空间大不大? ② 公司在行业里排第几? ③ 近3年营收利润是否增长? ④ ROE>15%? ⑤ PE处于历史低位? 五个都过=进入观察池。")
        )),
        TChapter("港股与美股", "出海交易的规则差异", listOf(
            TSection("港股特点",
                "T+0交易、无涨跌停、每手股数各公司不同(本App会显示每手N股)。有仙股(几毛钱、流动性差), 别贪便宜。",
                "估值普遍低于A股(同一家公司H股常比A股便宜30%), 但散户占比低、机构主导, 炒小炒烂容易血本无归。"),
            TSection("港股通 vs 港股券商",
                "港股通: 内地券商开通, 门槛50万, 人民币结算, 只能买纳入标的; 港股券商: 无门槛, 港币结算, 标的全。本App模拟两者混合体验。"),
            TSection("美股特点",
                "T+0、无涨跌停、1股起买。交易时间=北京时间夜里(夏令21:30-4:00, 冬令22:30-5:00), 有盘前盘后交易。",
                "苹果/特斯拉/英伟达等核心资产长期趋势向上, 是全球资金的主战场。"),
            TSection("汇率影响",
                "港币挂钩美元, 人民币兑美元波动会直接影响你的实际收益: 股票不涨不跌, 人民币升值你就亏汇兑。本App用固定汇率模拟(1HKD=0.92, 1USD=7.15)。"),
            TSection("新手出海建议",
                "先从最熟悉的巨头开始(腾讯/苹果), 避开中概小票和妖股。港美股波动更剧烈, 仓位更要控制。")
        )),
        TChapter("ETF与场内基金", "懒人和新手的最佳工具", listOf(
            TSection("什么是ETF",
                "ETF=跟踪某个指数的一篮子股票, 像股票一样在场内买卖。买\"沪深300ETF\"=一次性买了A股最大的300家公司。",
                "个股会退市归零, 指数永生——这就是ETF对新手最大的意义。"),
            TSection("常见类型",
                "宽基: 沪深300/中证500/创业板ETF。行业: 券商/半导体/医药ETF。跨境: 纳指ETF/恒生科技ETF(本App都可交易, T+0的跨境品种规则特殊)。",
                "代码规则: 沪市51/56/58开头, 深市15开头, 都在本App的\"基金\"分类里。"),
            TSection("ETF vs 个股 vs 场外基金",
                "场内ETF: 实时交易、费率低、透明, 推荐。场外基金: 净值申赎、适合定投但滞后。个股: 收益上限高但风险集中, 需要研究能力。"),
            TSection("新手定投策略",
                "每月固定日期买入固定金额的宽基ETF(如沪深300), 不管涨跌坚持3年以上, 收益大概率跑赢自己的手动操作。\"无脑定投\"赢在纪律。")
        )),
        TChapter("风险管理", "活得久比赚得快重要", listOf(
            TSection("仓位管理: 最重要的技术",
                "永远不要满仓一只股票! 新手参考: 单只≤30%, 整体仓位牛市≤80%, 看不懂时≤50%。",
                "留有现金=留有主动权, 大跌时你才有子弹补仓, 而不是被迫割肉。"),
            TSection("止损: 保命的纪律",
                "买入前就设定止损位(常用-8%~-15%), 跌破无条件执行。亏损10%需要涨11%回本, 亏50%需要涨100%回本——小亏跑得掉, 大亏跑不掉。",
                "止损不是认输, 是花小钱买保险。可以错很多次小的, 不能错一次大的。"),
            TSection("止盈与移动止盈",
                "目标止盈: 到达预期涨幅(如+30%)分批卖。移动止盈: 涨后回撤8%即卖, 让利润奔跑。"),
            TSection("分散与相关性",
                "分散5-10只、跨行业配置。注意\"伪分散\": 买5只全是半导体股, 一跌全跌, 等于没分散。"),
            TSection("远离杠杆",
                "融资融券/场外配资会把波动放大数倍。A股一天±10%, 5倍杠杆一天±50%, 直接爆仓。新手阶段(前3年)任何杠杆都不要碰。"),
            TSection("仓位计算示例",
                "本金10万, 单只30%=3万。股价10元止损9元(-10%), 最多亏3000元=总资金3%。这就是\"亏小钱\"的具体操作。")
        )),
        TChapter("常见陷阱与避坑", "前人亏钱换来的教训", listOf(
            TSection("荐股群/直播间骗局",
                "任何\"老师带你买牛股, 稳赚\"\"内部消息\"都是骗局: 先给你甜头, 再诱导去假平台入金。记住: 真有稳赚的方法, 没人会几百块钱卖给你。"),
            TSection("杀猪盘与假平台",
                "网恋+荐股组合拳, 或\"山寨券商App\"(充值容易出金难)。只在正规持牌券商开户, 只在官方应用商店下载App。"),
            TSection("追涨杀跌",
                "涨了怕踏空追进去(买在山顶), 跌了恐慌割肉(卖在地板)。解法: 买入前写好计划(买价/止损/目标), 按计划执行, 不盘中情绪化操作。"),
            TSection("听消息炒股",
                "散户听到的\"消息\"是第N手的, 往往正是主力想让你听到的小道消息。消息只能当线索, 决策必须基于规则和逻辑。"),
            TSection("越跌越买的误区",
                "补仓摊低成本只适用于\"基本面没坏的优质股\", 垃圾股越补越深。补仓要设总仓位上限, 别把补仓变成\"加仓上瘾\"。"),
            TSection("把模拟当游戏",
                "模拟盘最大的坑: 因为是假钱, 乱操作也能赚钱, 养成坏习惯。正确用法: 模拟盘严格执行真实资金的管理纪律, 记录每笔买卖理由。"),
            TSection("毕业清单: 什么时候可以投真钱",
                "① 熟悉本App全部规则与操作; ② 有书面交易计划并执行过20笔以上; ③ 模拟盘经历一次完整回撤仍守住纪律; ④ 投入的是闲钱。满足后, 从小资金开始。")
        ))
    )
}

/* ---- 教程列表 ---- */
@Composable
fun TutorialScreen(store: Store, nav: androidx.navigation.NavHostController) {
    val ctx = LocalContext.current
    val listState = rememberScalingLazyListState()
    ScalingLazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item { ListHeader { Text("零基础炒股教程", fontSize = 15.sp, fontWeight = FontWeight.Medium) } }
        item { Text("12课 · 完整离线 · 从0到入门", fontSize = 10.sp, color = Dim) }
        items(Tutorial.chapters.size) { i ->
            val ch = Tutorial.chapters[i]
            StackedCard(listState, i, onClick = {
                buzz(ctx)
                store.curChapter = i
                nav.navigate("chapter")
            }) {
                Text("${i + 1}. ${ch.title}", fontSize = 14.sp, color = Color.White)
                Text(ch.sub, fontSize = 10.sp, color = Dim)
            }
        }
        item {
            Text("建议按顺序学习\n每课3分钟, 全程约40分钟", fontSize = 10.sp, color = Dim,
                textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
        }
    }
}

/* ---- 教程内容页 ---- */
@Composable
fun ChapterScreen(store: Store) {
    val ch = Tutorial.chapters[store.curChapter]
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item { ListHeader { Text("${store.curChapter + 1}/${Tutorial.chapters.size} ${ch.title}",
            fontSize = 14.sp, fontWeight = FontWeight.Medium) } }
        for (sec in ch.sections) {
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(sec.h, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Pri)
                    Spacer(Modifier.height(4.dp))
                    for (p in sec.body) {
                        Text(p, fontSize = 11.sp, color = Color(0xFFDADCE0),
                            textAlign = TextAlign.Center, lineHeight = 15.sp)
                        Spacer(Modifier.height(6.dp))
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
        item {
            Text("← 右滑返回课程列表", fontSize = 10.sp, color = Dim)
            Spacer(Modifier.height(20.dp))
        }
    }
}
