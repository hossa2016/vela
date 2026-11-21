package com.hossa.vela

import org.json.JSONObject

/** Señal de una estrategia en una vela cerrada. */
enum class Signal { BUY, SELL, HOLD }

/**
 * Estrategias de bots. Todas trabajan sobre precios de cierre.
 * [p1], [p2], [p3] son los parámetros ajustables de cada tipo.
 */
data class StrategyConfig(
    val type: String,          // "ma", "rsi", "dca", "grid"
    val p1: Double,
    val p2: Double,
    val p3: Double = 0.0
) {
    val title: String get() = when (type) {
        "ma" -> "Cruce de medias"
        "rsi" -> "RSI"
        "dca" -> "Compras periódicas (DCA)"
        "grid" -> "Grid"
        else -> type
    }

    val summary: String get() = when (type) {
        "ma" -> "Compra cuando la media de ${p1.toInt()} cruza hacia arriba la de ${p2.toInt()} y vende cuando cruza hacia abajo."
        "rsi" -> "Compra cuando el RSI baja de ${p1.toInt()} (sobreventa) y vende cuando sube de ${p2.toInt()} (sobrecompra)."
        "dca" -> "Compra un ${p1.toInt()}% de lo disponible cada ${p2.toInt()} velas, sin importar el precio, y no vende."
        "grid" -> "Divide un rango de ±${p1.toInt()}% en ${p2.toInt()} escalones: compra en cada bajada de escalón y vende en cada subida."
        else -> ""
    }

    fun toJson() = JSONObject().put("type", type).put("p1", p1).put("p2", p2).put("p3", p3)

    companion object {
        fun fromJson(o: JSONObject) = StrategyConfig(o.getString("type"), o.getDouble("p1"), o.getDouble("p2"), o.optDouble("p3", 0.0))

        val DEFAULTS = listOf(
            StrategyConfig("ma", 20.0, 50.0),
            StrategyConfig("rsi", 30.0, 70.0),
            StrategyConfig("dca", 10.0, 42.0),   // 42 velas de 4h ≈ 1 semana
            StrategyConfig("grid", 10.0, 8.0)
        )
    }
}

/** Estado de una cartera simulada del bot (USD + moneda). */
data class BotWallet(var cash: Double, var qty: Double = 0.0, var avgCost: Double = 0.0)

data class Trade(val time: Long, val buy: Boolean, val price: Double, val qty: Double, val fee: Double, val pnl: Double?)

data class BacktestResult(
    val finalValue: Double,
    val returnPct: Double,
    val holdPct: Double,
    val trades: Int,
    val winRate: Double?,      // null si no hubo ventas
    val maxDrawdownPct: Double,
    val fees: Double,
    val equity: List<Double>,
    val hold: List<Double>,
    val times: List<Long>,
    val tradeList: List<Trade>
) {
    fun verdict(): String {
        val r = returnPct
        val h = holdPct
        return when {
            trades == 0 -> "El bot no encontró señales en este periodo: no hizo ninguna operación."
            r < 0 && h < 0 && r > h -> "El bot perdió, pero menos que solo comprar y esperar: limitó la caída."
            r < 0 -> "El bot perdió dinero en este periodo. Así como está no conviene."
            r >= h -> "El bot ganó y superó a solo comprar y esperar. Igual, un buen pasado no garantiza el futuro."
            r > 0 && maxDrawdownPct > -(kotlin.math.abs(h) / 2) ->
                "El bot ganó, pero menos que solo comprar y esperar. A cambio, arriesgó menos (caída más pequeña)."
            else -> "El bot ganó, pero menos que solo comprar y esperar."
        }
    }
}

object Engine {
    const val FEE = 0.001 // 0,1% por operación

    /**
     * Estado interno de una estrategia que necesita memoria (grid, dca).
     */
    class State(var lastBuyIndex: Int = -100000, var gridAnchor: Double = 0.0, var gridLevel: Int = 0)

    /**
     * Decide qué hacer en la vela [i] (ya cerrada). Devuelve la señal y la
     * fracción del efectivo (compra) o de la posición (venta) a usar.
     */
    fun decide(cfg: StrategyConfig, closes: List<Double>, i: Int, w: BotWallet, st: State,
               smaF: List<Double?>?, smaS: List<Double?>?, rsi: List<Double?>?): Pair<Signal, Double> {
        val price = closes[i]
        when (cfg.type) {
            "ma" -> {
                val f0 = smaF?.getOrNull(i - 1); val s0 = smaS?.getOrNull(i - 1)
                val f1 = smaF?.getOrNull(i); val s1 = smaS?.getOrNull(i)
                if (f0 == null || s0 == null || f1 == null || s1 == null) return Signal.HOLD to 0.0
                if (f0 <= s0 && f1 > s1 && w.qty == 0.0) return Signal.BUY to 1.0
                if (f0 >= s0 && f1 < s1 && w.qty > 0.0) return Signal.SELL to 1.0
            }
            "rsi" -> {
                val r = rsi?.getOrNull(i) ?: return Signal.HOLD to 0.0
                if (r < cfg.p1 && w.qty == 0.0) return Signal.BUY to 1.0
                if (r > cfg.p2 && w.qty > 0.0) return Signal.SELL to 1.0
            }
            "dca" -> {
                if (i - st.lastBuyIndex >= cfg.p2.toInt() && w.cash > 1.0) {
                    st.lastBuyIndex = i
                    return Signal.BUY to (cfg.p1 / 100.0)
                }
            }
            "grid" -> {
                val steps = cfg.p2.toInt().coerceAtLeast(2)
                if (st.gridAnchor == 0.0) { st.gridAnchor = price; st.gridLevel = 0 }
                val stepPct = (cfg.p1 / 100.0) / (steps / 2.0)
                val level = Math.floor(Math.log(price / st.gridAnchor) / Math.log(1 + stepPct)).toInt()
                    .coerceIn(-steps / 2, steps / 2)
                if (level < st.gridLevel && w.cash > 1.0) {
                    val n = st.gridLevel - level
                    st.gridLevel = level
                    // Compra una porción por cada escalón bajado
                    val levelsLeft = (level + steps / 2 + 1).coerceAtLeast(1)
                    return Signal.BUY to (n.toDouble() / levelsLeft).coerceAtMost(1.0)
                }
                if (level > st.gridLevel && w.qty > 0.0) {
                    val n = level - st.gridLevel
                    st.gridLevel = level
                    val levelsLeft = (steps / 2 - level + n).coerceAtLeast(1)
                    return Signal.SELL to (n.toDouble() / levelsLeft).coerceAtMost(1.0)
                }
                st.gridLevel = level
            }
        }
        return Signal.HOLD to 0.0
    }

    /** Aplica una señal a la cartera y devuelve la operación hecha (o null). */
    fun execute(sig: Signal, frac: Double, price: Double, time: Long, w: BotWallet): Trade? {
        if (sig == Signal.BUY) {
            val spend = w.cash * frac
            if (spend < 1.0) return null
            val fee = spend * FEE
            val q = (spend - fee) / price
            w.avgCost = if (w.qty + q > 0) (w.avgCost * w.qty + price * q) / (w.qty + q) else price
            w.qty += q
            w.cash -= spend
            return Trade(time, true, price, q, fee, null)
        }
        if (sig == Signal.SELL) {
            val q = w.qty * frac
            if (q <= 0) return null
            val gross = q * price
            val fee = gross * FEE
            val pnl = (price - w.avgCost) * q - fee
            w.qty -= q
            if (w.qty < 1e-12) { w.qty = 0.0; w.avgCost = 0.0 }
            w.cash += gross - fee
            return Trade(time, false, price, q, fee, pnl)
        }
        return null
    }

    fun indicators(cfg: StrategyConfig, closes: List<Double>): Triple<List<Double?>?, List<Double?>?, List<Double?>?> = when (cfg.type) {
        "ma" -> Triple(Ind.sma(closes, cfg.p1.toInt()), Ind.sma(closes, cfg.p2.toInt()), null)
        "rsi" -> Triple(null, null, Ind.rsi(closes, 14))
        else -> Triple(null, null, null)
    }

    fun backtest(cfg: StrategyConfig, candles: List<Candle>, startCash: Double): BacktestResult {
        val closes = candles.map { it.close }
        val (f, s, r) = indicators(cfg, closes)
        val w = BotWallet(startCash)
        val st = State()
        val equity = ArrayList<Double>()
        val hold = ArrayList<Double>()
        val times = ArrayList<Long>()
        val trades = ArrayList<Trade>()
        var fees = 0.0
        val first = closes.first()
        var peak = startCash
        var maxDd = 0.0
        for (i in closes.indices) {
            val (sig, frac) = decide(cfg, closes, i, w, st, f, s, r)
            execute(sig, frac, closes[i], candles[i].time, w)?.let { trades.add(it); fees += it.fee }
            val v = w.cash + w.qty * closes[i]
            equity.add(v)
            hold.add(startCash * (1 - FEE) * closes[i] / first)
            times.add(candles[i].time)
            if (v > peak) peak = v
            val dd = (v - peak) / peak * 100
            if (dd < maxDd) maxDd = dd
        }
        val sells = trades.filter { !it.buy }
        val wins = sells.count { (it.pnl ?: 0.0) > 0 }
        val finalV = equity.last()
        return BacktestResult(
            finalValue = finalV,
            returnPct = (finalV / startCash - 1) * 100,
            holdPct = (hold.last() / startCash - 1) * 100,
            trades = trades.size,
            winRate = if (sells.isEmpty()) null else wins * 100.0 / sells.size,
            maxDrawdownPct = maxDd,
            fees = fees,
            equity = equity, hold = hold, times = times, tradeList = trades
        )
    }
}
