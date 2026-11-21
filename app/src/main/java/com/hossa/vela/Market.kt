package com.hossa.vela

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Coin(val symbol: String, val name: String, val glyph: String, val color: Long)

data class Ticker(val symbol: String, val price: Double, val changePct: Double)

data class Candle(val time: Long, val open: Double, val high: Double, val low: Double, val close: Double, val volume: Double)

/** Datos públicos de mercado de Binance (sin cuenta ni clave). */
object Market {
    val COINS = listOf(
        Coin("BTC", "Bitcoin", "₿", 0xFFF7931A),
        Coin("ETH", "Ethereum", "Ξ", 0xFF8EA8FF),
        Coin("SOL", "Solana", "S", 0xFFB48CFF),
        Coin("BNB", "BNB", "B", 0xFFF3BA2F),
        Coin("XRP", "XRP", "X", 0xFF9AA4B0),
        Coin("ADA", "Cardano", "A", 0xFF5DB0FF),
        Coin("DOGE", "Dogecoin", "D", 0xFFC2A633),
        Coin("LINK", "Chainlink", "L", 0xFF4F7CFF)
    )

    fun coin(symbol: String) = COINS.firstOrNull { it.symbol == symbol } ?: COINS[0]

    private val HOSTS = listOf("https://data-api.binance.vision", "https://api.binance.com")

    private fun get(path: String): String {
        var last: Exception? = null
        for (h in HOSTS) {
            try {
                val c = URL(h + path).openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 12000
                try {
                    if (c.responseCode != 200) throw Exception("HTTP ${c.responseCode}")
                    return c.inputStream.bufferedReader().use { it.readText() }
                } finally { c.disconnect() }
            } catch (e: Exception) { last = e }
        }
        throw last ?: Exception("sin conexión")
    }

    fun tickers(symbols: List<String> = COINS.map { it.symbol }): List<Ticker> {
        val arr = symbols.joinToString(",", "[", "]") { "\"${it}USDT\"" }
        val j = JSONArray(get("/api/v3/ticker/24hr?symbols=" + URLEncoder.encode(arr, "UTF-8")))
        return (0 until j.length()).map {
            val o = j.getJSONObject(it)
            Ticker(o.getString("symbol").removeSuffix("USDT"), o.getString("lastPrice").toDouble(),
                o.getString("priceChangePercent").toDouble())
        }
    }

    fun price(symbol: String): Double {
        val o = org.json.JSONObject(get("/api/v3/ticker/price?symbol=${symbol}USDT"))
        return o.getString("price").toDouble()
    }

    /** Velas; si [count] > 1000 se piden en varias tandas hacia atrás. */
    fun candles(symbol: String, interval: String, count: Int): List<Candle> {
        val out = ArrayList<Candle>()
        var endTime: Long? = null
        var remaining = count
        while (remaining > 0) {
            val lim = minOf(1000, remaining)
            val q = "/api/v3/klines?symbol=${symbol}USDT&interval=$interval&limit=$lim" +
                (endTime?.let { "&endTime=$it" } ?: "")
            val j = JSONArray(get(q))
            if (j.length() == 0) break
            val batch = (0 until j.length()).map {
                val a = j.getJSONArray(it)
                Candle(a.getLong(0), a.getString(1).toDouble(), a.getString(2).toDouble(),
                    a.getString(3).toDouble(), a.getString(4).toDouble(), a.getString(5).toDouble())
            }
            out.addAll(0, batch)
            remaining -= batch.size
            endTime = batch.first().time - 1
            if (batch.size < lim) break
        }
        return out
    }

    fun intervalMs(interval: String): Long = when (interval) {
        "15m" -> 15 * 60_000L
        "1h" -> 3_600_000L
        "4h" -> 4 * 3_600_000L
        "1d" -> 86_400_000L
        else -> 3_600_000L
    }
}

object Ind {
    fun sma(v: List<Double>, n: Int): List<Double?> {
        val out = ArrayList<Double?>(v.size)
        var sum = 0.0
        for (i in v.indices) {
            sum += v[i]
            if (i >= n) sum -= v[i - n]
            out.add(if (i >= n - 1) sum / n else null)
        }
        return out
    }

    /** RSI de Wilder. */
    fun rsi(v: List<Double>, n: Int = 14): List<Double?> {
        val out = MutableList<Double?>(v.size) { null }
        if (v.size <= n) return out
        var gain = 0.0
        var loss = 0.0
        for (i in 1..n) {
            val d = v[i] - v[i - 1]
            if (d > 0) gain += d else loss -= d
        }
        gain /= n; loss /= n
        out[n] = if (loss == 0.0) 100.0 else 100 - 100 / (1 + gain / loss)
        for (i in n + 1 until v.size) {
            val d = v[i] - v[i - 1]
            gain = (gain * (n - 1) + maxOf(d, 0.0)) / n
            loss = (loss * (n - 1) + maxOf(-d, 0.0)) / n
            out[i] = if (loss == 0.0) 100.0 else 100 - 100 / (1 + gain / loss)
        }
        return out
    }
}

object Fmt {
    private val es = java.util.Locale("es", "CO")
    fun usd(v: Double): String {
        val digits = when {
            v >= 1000 -> 0
            v >= 1 -> 2
            v >= 0.01 -> 4
            else -> 6
        }
        val nf = java.text.NumberFormat.getNumberInstance(es).apply {
            minimumFractionDigits = digits; maximumFractionDigits = digits
        }
        return "$" + nf.format(v)
    }
    fun money(v: Double): String {
        val nf = java.text.NumberFormat.getNumberInstance(es).apply { minimumFractionDigits = 2; maximumFractionDigits = 2 }
        return (if (v < 0) "−$" else "$") + nf.format(kotlin.math.abs(v))
    }
    fun pct(v: Double, sign: Boolean = true): String {
        val nf = java.text.NumberFormat.getNumberInstance(es).apply { minimumFractionDigits = 1; maximumFractionDigits = 1 }
        val s = nf.format(kotlin.math.abs(v)) + "%"
        return if (!sign) s else if (v >= 0) "+$s" else "−$s"
    }
    fun qty(v: Double): String {
        val nf = java.text.NumberFormat.getNumberInstance(es).apply { maximumFractionDigits = if (v >= 100) 2 else 6 }
        return nf.format(v)
    }
}
