package com.hossa.vela

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Posición manual en el simulador. */
data class Position(val symbol: String, var qty: Double, var avgCost: Double)

data class ManualTrade(val time: Long, val symbol: String, val buy: Boolean, val price: Double, val qty: Double, val fee: Double, val pnl: Double?)

/** Bot simulado corriendo "en vivo" desde que lo creaste. */
data class LiveBot(
    val id: String,
    val symbol: String,
    val interval: String,
    val cfg: StrategyConfig,
    val startCash: Double,
    val created: Long,
    var cash: Double,
    var qty: Double,
    var avgCost: Double,
    var lastCandle: Long,
    var lastBuyIndex: Int,
    var gridAnchor: Double,
    var gridLevel: Int,
    var lastPrice: Double,
    val trades: MutableList<Trade>,
    var active: Boolean = true
) {
    fun value(price: Double = lastPrice) = cash + qty * price
    fun returnPct(price: Double = lastPrice) = (value(price) / startCash - 1) * 100
}

data class Alert(val id: String, val symbol: String, val above: Boolean, val price: Double, var fired: Boolean = false)

object Store {
    const val START_CASH = 1000.0
    private fun p(ctx: Context) = ctx.getSharedPreferences("vela", Context.MODE_PRIVATE)

    // ---------------- Lista de monedas
    fun watchlist(ctx: Context): List<String> =
        p(ctx).getString("watch", null)?.split(",")?.filter { it.isNotBlank() } ?: Market.COINS.map { it.symbol }

    // ---------------- Cuenta manual
    fun cash(ctx: Context) = p(ctx).getFloat("cash", START_CASH.toFloat()).toDouble()
    fun setCash(ctx: Context, v: Double) = p(ctx).edit().putFloat("cash", v.toFloat()).apply()
    fun deposits(ctx: Context) = p(ctx).getFloat("deposits", START_CASH.toFloat()).toDouble()

    fun positions(ctx: Context): MutableList<Position> {
        val a = JSONArray(p(ctx).getString("positions", "[]"))
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Position(o.getString("s"), o.getDouble("q"), o.getDouble("c"))
        }.toMutableList()
    }

    fun savePositions(ctx: Context, list: List<Position>) {
        val a = JSONArray()
        list.filter { it.qty > 1e-12 }.forEach { a.put(JSONObject().put("s", it.symbol).put("q", it.qty).put("c", it.avgCost)) }
        p(ctx).edit().putString("positions", a.toString()).apply()
    }

    fun history(ctx: Context): MutableList<ManualTrade> {
        val a = JSONArray(p(ctx).getString("history", "[]"))
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            ManualTrade(o.getLong("t"), o.getString("s"), o.getBoolean("b"), o.getDouble("p"), o.getDouble("q"),
                o.getDouble("f"), if (o.has("pnl")) o.getDouble("pnl") else null)
        }.toMutableList()
    }

    private fun saveHistory(ctx: Context, list: List<ManualTrade>) {
        val a = JSONArray()
        list.takeLast(300).forEach {
            val o = JSONObject().put("t", it.time).put("s", it.symbol).put("b", it.buy).put("p", it.price).put("q", it.qty).put("f", it.fee)
            it.pnl?.let { v -> o.put("pnl", v) }
            a.put(o)
        }
        p(ctx).edit().putString("history", a.toString()).apply()
    }

    /** Compra manual por [usd] dólares. Devuelve un error o null. */
    fun buy(ctx: Context, symbol: String, usd: Double, price: Double): String? {
        val cash = cash(ctx)
        if (usd <= 0) return "Escribe un monto"
        if (usd > cash + 1e-9) return "No te alcanza: tienes ${Fmt.money(cash)}"
        val fee = usd * Engine.FEE
        val q = (usd - fee) / price
        val list = positions(ctx)
        val pos = list.firstOrNull { it.symbol == symbol } ?: Position(symbol, 0.0, 0.0).also { list.add(it) }
        pos.avgCost = (pos.avgCost * pos.qty + price * q) / (pos.qty + q)
        pos.qty += q
        savePositions(ctx, list)
        setCash(ctx, cash - usd)
        saveHistory(ctx, history(ctx).apply { add(ManualTrade(System.currentTimeMillis(), symbol, true, price, q, fee, null)) })
        return null
    }

    /** Venta manual de una fracción (0..1) de la posición. */
    fun sell(ctx: Context, symbol: String, frac: Double, price: Double): String? {
        val list = positions(ctx)
        val pos = list.firstOrNull { it.symbol == symbol } ?: return "No tienes $symbol"
        val q = pos.qty * frac.coerceIn(0.0, 1.0)
        if (q <= 0) return "Nada para vender"
        val gross = q * price
        val fee = gross * Engine.FEE
        val pnl = (price - pos.avgCost) * q - fee
        pos.qty -= q
        savePositions(ctx, list)
        setCash(ctx, cash(ctx) + gross - fee)
        saveHistory(ctx, history(ctx).apply { add(ManualTrade(System.currentTimeMillis(), symbol, false, price, q, fee, pnl)) })
        return null
    }

    fun resetAccount(ctx: Context) {
        p(ctx).edit().remove("positions").remove("history").putFloat("cash", START_CASH.toFloat())
            .putFloat("deposits", START_CASH.toFloat()).apply()
    }

    // ---------------- Bots en vivo
    fun bots(ctx: Context): MutableList<LiveBot> {
        val a = JSONArray(p(ctx).getString("bots", "[]"))
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val ta = o.optJSONArray("trades") ?: JSONArray()
            val trades = (0 until ta.length()).map {
                val t = ta.getJSONObject(it)
                Trade(t.getLong("t"), t.getBoolean("b"), t.getDouble("p"), t.getDouble("q"), t.getDouble("f"),
                    if (t.has("pnl")) t.getDouble("pnl") else null)
            }.toMutableList()
            LiveBot(o.getString("id"), o.getString("s"), o.getString("iv"), StrategyConfig.fromJson(o.getJSONObject("cfg")),
                o.getDouble("start"), o.getLong("created"), o.getDouble("cash"), o.getDouble("qty"), o.getDouble("avg"),
                o.getLong("last"), o.getInt("lbi"), o.getDouble("ga"), o.getInt("gl"), o.optDouble("lp", 0.0), trades,
                o.optBoolean("active", true))
        }.toMutableList()
    }

    fun saveBots(ctx: Context, list: List<LiveBot>) {
        val a = JSONArray()
        list.forEach { b ->
            val ta = JSONArray()
            b.trades.takeLast(200).forEach { t ->
                val o = JSONObject().put("t", t.time).put("b", t.buy).put("p", t.price).put("q", t.qty).put("f", t.fee)
                t.pnl?.let { o.put("pnl", it) }
                ta.put(o)
            }
            a.put(JSONObject().put("id", b.id).put("s", b.symbol).put("iv", b.interval).put("cfg", b.cfg.toJson())
                .put("start", b.startCash).put("created", b.created).put("cash", b.cash).put("qty", b.qty)
                .put("avg", b.avgCost).put("last", b.lastCandle).put("lbi", b.lastBuyIndex).put("ga", b.gridAnchor)
                .put("gl", b.gridLevel).put("lp", b.lastPrice).put("trades", ta).put("active", b.active))
        }
        p(ctx).edit().putString("bots", a.toString()).apply()
    }

    // ---------------- Alertas
    fun alerts(ctx: Context): MutableList<Alert> {
        val a = JSONArray(p(ctx).getString("alerts", "[]"))
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Alert(o.getString("id"), o.getString("s"), o.getBoolean("above"), o.getDouble("p"), o.optBoolean("fired"))
        }.toMutableList()
    }

    fun saveAlerts(ctx: Context, list: List<Alert>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("s", it.symbol).put("above", it.above).put("p", it.price).put("fired", it.fired)) }
        p(ctx).edit().putString("alerts", a.toString()).apply()
    }

    fun addAlert(ctx: Context, symbol: String, above: Boolean, price: Double) {
        saveAlerts(ctx, alerts(ctx).apply { add(Alert(System.currentTimeMillis().toString(), symbol, above, price)) })
    }

    // ---------------- IA
    fun geminiKey(ctx: Context) = p(ctx).getString("gemini", "") ?: ""
    fun groqKey(ctx: Context) = p(ctx).getString("groq", "") ?: ""
    fun setKeys(ctx: Context, gemini: String, groq: String) =
        p(ctx).edit().putString("gemini", gemini.trim()).putString("groq", groq.trim()).apply()
}
