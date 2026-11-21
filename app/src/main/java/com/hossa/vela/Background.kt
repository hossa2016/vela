package com.hossa.vela

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Avanza los bots en vivo con las velas nuevas cerradas. Bloquea (red). */
object Bots {
    fun step(ctx: Context, notify: Boolean): Boolean {
        val bots = Store.bots(ctx)
        if (bots.none { it.active }) return false
        var changed = false
        for (b in bots.filter { it.active }) {
            try {
                // Suficientes velas para calcular indicadores (hasta 250 atrás)
                val all = Market.candles(b.symbol, b.interval, 300)
                if (all.size < 3) continue
                val closedUntil = System.currentTimeMillis() - Market.intervalMs(b.interval)
                val candles = all.filter { it.time <= closedUntil }
                val closes = candles.map { it.close }
                val (f, s, r) = Engine.indicators(b.cfg, closes)
                val w = BotWallet(b.cash, b.qty, b.avgCost)
                // Índice "global" estable para DCA: número de velas desde la creación
                val st = Engine.State(b.lastBuyIndex, b.gridAnchor, b.gridLevel)
                for (i in candles.indices) {
                    val c = candles[i]
                    if (c.time <= b.lastCandle) continue
                    val globalIndex = ((c.time - b.created) / Market.intervalMs(b.interval)).toInt()
                    // DCA usa índices globales
                    val (sig, frac) = if (b.cfg.type == "dca") {
                        if (globalIndex - st.lastBuyIndex >= b.cfg.p2.toInt() && w.cash > 1.0) {
                            st.lastBuyIndex = globalIndex; Signal.BUY to b.cfg.p1 / 100.0
                        } else Signal.HOLD to 0.0
                    } else Engine.decide(b.cfg, closes, i, w, st, f, s, r)
                    Engine.execute(sig, frac, c.close, c.time, w)?.let { t ->
                        b.trades.add(t)
                        if (notify) Notify.show(ctx, "Bot ${b.cfg.title} · ${b.symbol}",
                            (if (t.buy) "Compró " else "Vendió ") + "${Fmt.qty(t.qty)} ${b.symbol} a ${Fmt.usd(t.price)}" +
                                (t.pnl?.let { " · resultado ${Fmt.money(it)}" } ?: "") + " (dinero virtual)")
                    }
                    b.lastCandle = c.time
                    changed = true
                }
                b.cash = w.cash; b.qty = w.qty; b.avgCost = w.avgCost
                b.lastBuyIndex = st.lastBuyIndex; b.gridAnchor = st.gridAnchor; b.gridLevel = st.gridLevel
                b.lastPrice = all.last().close
                changed = true
            } catch (e: Exception) { }
        }
        if (changed) Store.saveBots(ctx, bots)
        return changed
    }

    fun create(ctx: Context, symbol: String, interval: String, cfg: StrategyConfig, cash: Double): LiveBot {
        val now = System.currentTimeMillis()
        val b = LiveBot(now.toString(), symbol, interval, cfg, cash, now, cash, 0.0, 0.0,
            lastCandle = now - Market.intervalMs(interval), lastBuyIndex = -100000, gridAnchor = 0.0, gridLevel = 0,
            lastPrice = 0.0, trades = mutableListOf())
        Store.saveBots(ctx, Store.bots(ctx).apply { add(b) })
        return b
    }
}

object Alerts {
    fun check(ctx: Context, prices: Map<String, Double>) {
        val list = Store.alerts(ctx)
        var changed = false
        for (a in list.filter { !it.fired }) {
            val p = prices[a.symbol] ?: continue
            if ((a.above && p >= a.price) || (!a.above && p <= a.price)) {
                a.fired = true
                changed = true
                Notify.show(ctx, "Alerta ${a.symbol}",
                    "${a.symbol} ${if (a.above) "subió por encima de" else "bajó de"} ${Fmt.usd(a.price)}: ahora ${Fmt.usd(p)}")
            }
        }
        if (changed) Store.saveAlerts(ctx, list)
    }
}

object Notify {
    private const val CH = "vela"
    fun show(ctx: Context, title: String, text: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null) {
            nm.createNotificationChannel(NotificationChannel(CH, "Alertas y bots", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val pi = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CH)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try { nm.notify((System.currentTimeMillis() % 100000).toInt(), n) } catch (e: SecurityException) { }
    }
}

/** Cada 15 minutos: alertas y bots en vivo. */
class MarketWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val ctx = applicationContext
        try {
            val syms = (Store.alerts(ctx).filter { !it.fired }.map { it.symbol } +
                Store.bots(ctx).filter { it.active }.map { it.symbol }).distinct()
            if (syms.isNotEmpty()) {
                val prices = Market.tickers(syms).associate { it.symbol to it.price }
                Alerts.check(ctx, prices)
            }
            Bots.step(ctx, notify = true)
        } catch (e: Exception) {
            return Result.retry()
        }
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<MarketWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("mercado", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
