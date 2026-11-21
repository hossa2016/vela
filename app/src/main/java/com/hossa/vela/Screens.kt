package com.hossa.vela

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dateFmt = SimpleDateFormat("d MMM HH:mm", Locale("es", "CO"))
private fun date(t: Long) = dateFmt.format(Date(t))

private fun parseNum(s: String): Double? = s.replace("$", "").replace(" ", "").let {
    // Acepta "1.234,5" y "1234.5"
    val t = if (it.contains(',')) it.replace(".", "").replace(',', '.') else it
    t.toDoubleOrNull()
}

@Composable
private fun Screen(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content
    )
}

@Composable
private fun Header(title: String, sub: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Title(title)
            if (sub != null) Text(sub, color = C.Dim, fontSize = 13.sp)
        }
        Box(
            Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(C.Card).clickable { App.settings = true },
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Filled.Settings, "Ajustes", tint = C.Dim) }
    }
}

@Composable
private fun CoinChips(selected: String, onPick: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Market.COINS.forEach { c -> Chip(c.symbol, selected == c.symbol) { onPick(c.symbol) } }
    }
}

@Composable
private fun VirtualNote() =
    Text("Todo es con dinero virtual. Nada aquí usa ni mueve dinero real.", color = C.Faint, fontSize = 12.sp)

/* ================================================================== MERCADO */

@Composable
fun MarketScreen() {
    val ctx = LocalContext.current
    Screen {
        Header("Vela", if (App.online) "Mercado cripto en vivo · USDT" else "Sin conexión · reintentando…")
        val tick = App.tick
        val (cash, coins) = remember(tick, App.tickers) { App.portfolio(ctx) }
        val total = cash + coins
        val pnl = total - Store.deposits(ctx)
        Card(Modifier.clickable { App.tab = 1 }) {
            Label("Tu cuenta virtual")
            Spacer(Modifier.height(6.dp))
            Text(Fmt.money(total), color = C.Text, fontSize = 32.sp, fontWeight = FontWeight.Bold, fontFamily = Mono)
            Text("${Fmt.money(pnl)} (${Fmt.pct(pnl / Store.deposits(ctx) * 100)}) desde el inicio", color = upDown(pnl), fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
            Row {
                Stat("Efectivo", Fmt.money(cash), modifier = Modifier.weight(1f))
                Stat("En monedas", Fmt.money(coins), modifier = Modifier.weight(1f))
            }
        }
        Label("Monedas")
        Card(pad = 6.dp) {
            Market.COINS.forEachIndexed { i, c ->
                val t = App.tickers[c.symbol]
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { App.detail = c.symbol }
                        .padding(horizontal = 10.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CoinBadge(c.symbol)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.symbol, color = C.Text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(c.name, color = C.Dim, fontSize = 12.sp)
                    }
                    Sparkline(App.sparks[c.symbol] ?: emptyList(), upDown(t?.changePct ?: 0.0),
                        Modifier.width(64.dp).height(26.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(96.dp)) {
                        Text(t?.let { Fmt.usd(it.price) } ?: "—", color = C.Text, fontFamily = Mono, fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold)
                        Text(t?.let { Fmt.pct(it.changePct) } ?: "", color = upDown(t?.changePct ?: 0.0), fontSize = 12.sp)
                    }
                }
                if (i < Market.COINS.size - 1) Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(1.dp).background(C.Line))
            }
        }
        Text("Cambio de las últimas 24 horas. Toca una moneda para ver su gráfica.", color = C.Faint, fontSize = 12.sp)
    }
}

@Composable
fun DetailScreen(symbol: String) {
    var iv by remember { mutableStateOf("4h") }
    var candles by remember { mutableStateOf<List<Candle>>(emptyList()) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(symbol, iv) {
        err = null
        try { candles = withContext(Dispatchers.IO) { Market.candles(symbol, iv, 160) } }
        catch (e: Exception) { err = "No pude cargar la gráfica" }
    }
    val closes = candles.map { it.close }
    val ma = remember(candles) { Ind.sma(closes, 50) }
    val rsi = remember(candles) { Ind.rsi(closes, 14) }
    val show = 90
    val t = App.tickers[symbol]
    val coin = Market.coin(symbol)
    Screen {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(C.Card).clickable { App.detail = null },
                contentAlignment = Alignment.Center) { Icon(Icons.Filled.ArrowBack, "Atrás", tint = C.Text) }
            Spacer(Modifier.width(12.dp))
            CoinBadge(symbol, 40.dp)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(coin.name, color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("$symbol / USDT", color = C.Dim, fontSize = 12.sp)
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(t?.let { Fmt.usd(it.price) } ?: "—", color = C.Text, fontSize = 34.sp, fontWeight = FontWeight.Bold, fontFamily = Mono)
            Spacer(Modifier.width(10.dp))
            Text(t?.let { Fmt.pct(it.changePct) + " 24h" } ?: "", color = upDown(t?.changePct ?: 0.0), fontSize = 14.sp,
                modifier = Modifier.padding(bottom = 6.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("1h" to "1H", "4h" to "4H", "1d" to "1D").forEach { (k, l) -> Chip(l, iv == k) { iv = k } }
        }
        Card(pad = 12.dp) {
            if (candles.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(230.dp), contentAlignment = Alignment.Center) {
                    Text(err ?: "Cargando…", color = C.Dim)
                }
            } else {
                CandleChart(candles.takeLast(show), ma.takeLast(show), Modifier.fillMaxWidth().height(230.dp))
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp, 3.dp).background(C.Gold))
                    Text("  Media de 50 velas", color = C.Dim, fontSize = 11.sp)
                }
                Spacer(Modifier.height(10.dp))
                Label("RSI 14")
                RsiChart(rsi.takeLast(show), Modifier.fillMaxWidth().height(70.dp).padding(top = 4.dp))
            }
        }
        if (candles.isNotEmpty()) {
            val last = closes.last()
            val m = ma.last()
            val r = rsi.last()
            Card {
                Label("Lectura simple")
                Spacer(Modifier.height(8.dp))
                if (m != null) {
                    val above = last >= m
                    Text(if (above) "▲ El precio está por encima de su media: tendencia de subida en esta escala."
                        else "▼ El precio está por debajo de su media: tendencia de bajada en esta escala.",
                        color = if (above) C.Green else C.Red, fontSize = 14.sp)
                    Spacer(Modifier.height(6.dp))
                }
                if (r != null) {
                    val txt = when {
                        r >= 70 -> "RSI ${r.toInt()}: sobrecompra. Subió rápido; a veces le sigue un descanso."
                        r <= 30 -> "RSI ${r.toInt()}: sobreventa. Bajó rápido; a veces le sigue un rebote."
                        else -> "RSI ${r.toInt()}: zona neutral, sin exceso de compras ni ventas."
                    }
                    Text(txt, color = C.Text, fontSize = 14.sp)
                }
                Spacer(Modifier.height(6.dp))
                Text("Son indicadores, no predicciones.", color = C.Faint, fontSize = 12.sp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn("Simular compra", modifier = Modifier.weight(1f)) { App.simCoin = symbol; App.tab = 1 }
            Btn("Alerta", C.Gold, Modifier.weight(1f), filled = false) { App.alertCoin = symbol; App.tab = 3 }
        }
        Btn("Pregúntale a la IA por $symbol", C.Blue, Modifier.fillMaxWidth(), filled = false) {
            App.pendingAsk = "¿Cómo ves $symbol ahora mismo? Explícame la tendencia y el RSI."
            App.tab = 4
        }
    }
}

/* ================================================================== SIMULADOR */

@Composable
fun SimScreen() {
    val ctx = LocalContext.current
    val tick = App.tick
    val positions = remember(tick) { Store.positions(ctx) }
    val history = remember(tick) { Store.history(ctx).reversed() }
    val (cash, coins) = remember(tick, App.tickers) { App.portfolio(ctx) }
    val total = cash + coins
    val pnl = total - Store.deposits(ctx)
    var mode by remember { mutableStateOf(true) } // true compra
    var amount by remember { mutableStateOf("") }
    var confirmReset by remember { mutableStateOf(false) }
    val sym = App.simCoin
    val price = App.price(sym)
    val pos = positions.firstOrNull { it.symbol == sym }

    Screen {
        Header("Simulador", "Opera tú mismo con dinero virtual")
        Card {
            Label("Valor total")
            Spacer(Modifier.height(6.dp))
            Text(Fmt.money(total), color = C.Text, fontSize = 32.sp, fontWeight = FontWeight.Bold, fontFamily = Mono)
            Text("${Fmt.money(pnl)} (${Fmt.pct(pnl / Store.deposits(ctx) * 100)}) desde que empezaste con ${Fmt.money(Store.deposits(ctx))}",
                color = upDown(pnl), fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
            Row {
                Stat("Efectivo", Fmt.money(cash), modifier = Modifier.weight(1f))
                Stat("En monedas", Fmt.money(coins), modifier = Modifier.weight(1f))
            }
        }

        Card {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Comprar", mode, C.Green) { mode = true; amount = "" }
                Chip("Vender", !mode, C.Red) { mode = false; amount = "" }
            }
            Spacer(Modifier.height(12.dp))
            CoinChips(sym) { App.simCoin = it }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Precio actual", color = C.Dim, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text(price?.let { Fmt.usd(it) } ?: "cargando…", color = C.Text, fontFamily = Mono, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
            if (mode) {
                Field(amount, { amount = it }, "Monto en dólares", Modifier.fillMaxWidth(), number = true, prefix = "$")
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(25, 50, 100).forEach { p ->
                        Chip("$p%", false) { amount = String.format(Locale.US, "%.2f", cash * p / 100.0 - 0.004).let { if (it.startsWith("-")) "0" else it } }
                    }
                }
                val usd = parseNum(amount) ?: 0.0
                if (price != null && usd > 0) {
                    Spacer(Modifier.height(10.dp))
                    Text("Recibes ≈ ${Fmt.qty(usd * (1 - Engine.FEE) / price)} $sym · comisión ${Fmt.money(usd * Engine.FEE)}",
                        color = C.Dim, fontSize = 12.sp)
                }
                Spacer(Modifier.height(12.dp))
                Btn("Comprar $sym", C.Green, Modifier.fillMaxWidth(), enabled = price != null) {
                    val e = Store.buy(ctx, sym, usd, price!!)
                    if (e != null) Toast.makeText(ctx, e, Toast.LENGTH_SHORT).show()
                    else { amount = ""; App.refresh(); Toast.makeText(ctx, "Compra simulada hecha", Toast.LENGTH_SHORT).show() }
                }
            } else {
                Text(if (pos == null) "No tienes $sym todavía." else "Tienes ${Fmt.qty(pos.qty)} $sym (≈ ${Fmt.money(pos.qty * (price ?: pos.avgCost))})",
                    color = C.Text, fontSize = 14.sp)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(25, 50, 100).forEach { p ->
                        Btn(if (p == 100) "Todo" else "$p%", C.Red, Modifier.weight(1f), filled = false, enabled = pos != null && price != null) {
                            val e = Store.sell(ctx, sym, p / 100.0, price!!)
                            if (e != null) Toast.makeText(ctx, e, Toast.LENGTH_SHORT).show()
                            else { App.refresh(); Toast.makeText(ctx, "Venta simulada hecha", Toast.LENGTH_SHORT).show() }
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("Comisión simulada de 0,1% por operación, como en un exchange real.", color = C.Faint, fontSize = 12.sp)
        }

        if (positions.isNotEmpty()) {
            Label("Tus posiciones")
            Card(pad = 6.dp) {
                positions.forEach { p ->
                    val now = App.price(p.symbol) ?: p.avgCost
                    val value = p.qty * now
                    val gain = (now - p.avgCost) * p.qty
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { App.simCoin = p.symbol; mode = false }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CoinBadge(p.symbol, 34.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${Fmt.qty(p.qty)} ${p.symbol}", color = C.Text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text("Compraste a ${Fmt.usd(p.avgCost)}", color = C.Dim, fontSize = 12.sp)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(Fmt.money(value), color = C.Text, fontFamily = Mono, fontSize = 14.sp)
                            Text("${Fmt.money(gain)} · ${Fmt.pct((now / p.avgCost - 1) * 100)}", color = upDown(gain), fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        if (history.isNotEmpty()) {
            Label("Historial")
            Card(pad = 6.dp) {
                history.take(25).forEach { h ->
                    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (h.buy) "COMPRA" else "VENTA", color = if (h.buy) C.Green else C.Red, fontSize = 11.sp,
                            fontWeight = FontWeight.Bold, modifier = Modifier.width(62.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${Fmt.qty(h.qty)} ${h.symbol} a ${Fmt.usd(h.price)}", color = C.Text, fontSize = 13.sp)
                            Text(date(h.time), color = C.Faint, fontSize = 11.sp)
                        }
                        h.pnl?.let { Text(Fmt.money(it), color = upDown(it), fontFamily = Mono, fontSize = 13.sp) }
                    }
                }
            }
        }
        Btn("Reiniciar cuenta a $1.000", C.Dim, Modifier.fillMaxWidth(), filled = false) { confirmReset = true }
        VirtualNote()
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            containerColor = C.Card2,
            title = { Text("¿Reiniciar la cuenta?", color = C.Text) },
            text = { Text("Se borran tus posiciones e historial y vuelves a tener $1.000 virtuales. Los bots no se tocan.", color = C.Dim) },
            confirmButton = { TextButton({ Store.resetAccount(ctx); App.refresh(); confirmReset = false }) { Text("Reiniciar", color = C.Red) } },
            dismissButton = { TextButton({ confirmReset = false }) { Text("Cancelar", color = C.Dim) } }
        )
    }
}

/* ================================================================== BOTS */

private data class ParamSpec(val label: String, val min: Double, val max: Double, val step: Double, val suffix: String = "")

private fun specs(type: String): List<ParamSpec> = when (type) {
    "ma" -> listOf(ParamSpec("Media rápida", 5.0, 100.0, 5.0, " velas"), ParamSpec("Media lenta", 10.0, 200.0, 10.0, " velas"))
    "rsi" -> listOf(ParamSpec("Compra si RSI baja de", 10.0, 45.0, 5.0), ParamSpec("Vende si RSI sube de", 55.0, 90.0, 5.0))
    "dca" -> listOf(ParamSpec("Cuánto compra cada vez", 5.0, 50.0, 5.0, "%"), ParamSpec("Cada cuántas velas", 1.0, 200.0, 1.0, " velas"))
    else -> listOf(ParamSpec("Rango arriba y abajo", 2.0, 40.0, 2.0, "%"), ParamSpec("Escalones", 4.0, 20.0, 2.0))
}

@Composable
private fun Stepper(spec: ParamSpec, value: Double, onChange: (Double) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(spec.label, color = C.Dim, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(C.Card2)
            .clickable { onChange((value - spec.step).coerceAtLeast(spec.min)) }, contentAlignment = Alignment.Center) {
            Text("−", color = C.Text, fontSize = 18.sp)
        }
        Text("${value.toInt()}${spec.suffix}", color = C.Text, fontFamily = Mono, fontWeight = FontWeight.Bold,
            fontSize = 14.sp, modifier = Modifier.width(92.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(C.Card2)
            .clickable { onChange((value + spec.step).coerceAtMost(spec.max)) }, contentAlignment = Alignment.Center) {
            Text("+", color = C.Text, fontSize = 18.sp)
        }
    }
}

@Composable
fun BotsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var type by remember { mutableStateOf("ma") }
    var p1 by remember { mutableStateOf(20.0) }
    var p2 by remember { mutableStateOf(50.0) }
    var sym by remember { mutableStateOf("BTC") }
    var months by remember { mutableStateOf(6) }
    var iv by remember { mutableStateOf("4h") }
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<BacktestResult?>(null) }
    var resultCfg by remember { mutableStateOf<Pair<StrategyConfig, String>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var updating by remember { mutableStateOf(false) }
    val tick = App.tick
    val live = remember(tick) { Store.bots(ctx).reversed() }
    val cfg = StrategyConfig(type, p1, p2)

    Screen {
        Header("Bots", "¿Cómo me habría ido? Pruébalo sin riesgo")

        Label("1 · Estrategia")
        StrategyConfig.DEFAULTS.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { d ->
                    val sel = d.type == type
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                            .background(if (sel) C.Green.copy(alpha = 0.12f) else C.Card)
                            .clickable { type = d.type; p1 = d.p1; p2 = d.p2; result = null }
                            .padding(14.dp)
                    ) {
                        Text(d.title, color = if (sel) C.Green else C.Text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(Modifier.height(3.dp))
                        Text(when (d.type) {
                            "ma" -> "Sigue la tendencia"
                            "rsi" -> "Compra caídas fuertes"
                            "dca" -> "Compra poco a poco"
                            else -> "Gana con el sube y baja"
                        }, color = C.Dim, fontSize = 12.sp)
                    }
                }
            }
        }
        Card {
            Text(cfg.summary, color = C.Text, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            val sp = specs(type)
            Stepper(sp[0], p1) { p1 = it; result = null }
            Stepper(sp[1], p2) { p2 = it; result = null }
        }

        Label("2 · Moneda y periodo")
        CoinChips(sym) { sym = it; result = null }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(3, 6, 12).forEach { m -> Chip("$m meses", months == m) { months = m; result = null } }
        }
        Text("Cada vela dura:", color = C.Dim, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("1h" to "1 hora", "4h" to "4 horas", "1d" to "1 día").forEach { (k, l) -> Chip(l, iv == k, C.Blue) { iv = k; result = null } }
        }

        Btn(if (running) "Simulando…" else "Simular con $1.000 virtuales", modifier = Modifier.fillMaxWidth(), enabled = !running) {
            running = true; err = null
            scope.launch {
                try {
                    val count = (months * 30L * 86_400_000L / Market.intervalMs(iv)).toInt()
                    val c = withContext(Dispatchers.IO) { Market.candles(sym, iv, count) }
                    if (c.size < 60) throw Exception("pocos datos")
                    result = withContext(Dispatchers.Default) { Engine.backtest(cfg, c, Store.START_CASH) }
                    resultCfg = cfg to "$sym · $months meses · velas de ${iv.replace("h", " h").replace("1d", "1 día")}"
                } catch (e: Exception) {
                    err = "No se pudo simular: ${e.message ?: "sin conexión"}"
                }
                running = false
            }
        }
        err?.let { Text(it, color = C.Red, fontSize = 13.sp) }

        result?.let { r ->
            val (rc, desc) = resultCfg ?: (cfg to "")
            Card {
                Label("Resultado · ${rc.title}")
                Text(desc, color = C.Faint, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(Fmt.money(r.finalValue), color = C.Text, fontSize = 30.sp, fontWeight = FontWeight.Bold, fontFamily = Mono)
                    Spacer(Modifier.width(8.dp))
                    Text(Fmt.pct(r.returnPct), color = upDown(r.returnPct), fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 5.dp))
                }
                Text("Si solo hubieras comprado y esperado: ${Fmt.pct(r.holdPct)}", color = C.Dim, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                EquityChart(r.equity, r.hold, Store.START_CASH, Modifier.fillMaxWidth().height(150.dp))
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp, 3.dp).background(upDown(r.returnPct)))
                    Text("  Bot     ", color = C.Dim, fontSize = 11.sp)
                    Box(Modifier.size(10.dp, 3.dp).background(C.Dim))
                    Text("  Comprar y esperar", color = C.Dim, fontSize = 11.sp)
                }
                Spacer(Modifier.height(14.dp))
                Row {
                    Stat("Operaciones", "${r.trades}", modifier = Modifier.weight(1f))
                    Stat("Ventas ganadoras", r.winRate?.let { "${it.toInt()}%" } ?: "—", modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                Row {
                    Stat("Peor caída", Fmt.pct(r.maxDrawdownPct), C.Red, Modifier.weight(1f))
                    Stat("Comisiones", Fmt.money(r.fees), modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(14.dp))
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Card2).padding(12.dp)) {
                    Text(r.verdict(), color = C.Text, fontSize = 14.sp)
                }
                Spacer(Modifier.height(12.dp))
                Btn("Probar en vivo con $1.000 virtuales", C.Blue, Modifier.fillMaxWidth()) {
                    Bots.create(ctx, sym, iv, rc, Store.START_CASH)
                    MarketWorker.schedule(ctx)
                    App.refresh()
                    Toast.makeText(ctx, "Bot en vivo creado. Opera solo con dinero virtual.", Toast.LENGTH_LONG).show()
                }
                Spacer(Modifier.height(6.dp))
                Text("En vivo, el bot decide solo cada vez que se cierra una vela y te avisa con una notificación.",
                    color = C.Faint, fontSize = 12.sp)
            }
        }

        if (live.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Label("Bots en vivo") }
                Text(if (updating) "Actualizando…" else "Actualizar", color = C.Blue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable(enabled = !updating) {
                        updating = true
                        scope.launch { withContext(Dispatchers.IO) { Bots.step(ctx, notify = false) }; App.refresh(); updating = false }
                    })
            }
            live.forEach { b ->
                val price = App.price(b.symbol) ?: b.lastPrice
                val value = if (price > 0) b.value(price) else b.cash
                val ret = (value / b.startCash - 1) * 100
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CoinBadge(b.symbol, 34.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${b.cfg.title} · ${b.symbol}", color = C.Text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("Desde ${date(b.created)}" + if (!b.active) " · pausado" else "",
                                color = C.Dim, fontSize = 12.sp, maxLines = 1)
                            Text("Velas de ${b.interval.replace("h", " h").replace("1d", "1 día")}", color = C.Faint, fontSize = 12.sp, maxLines = 1)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(Fmt.money(value), color = C.Text, fontFamily = Mono, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Text(Fmt.pct(ret), color = upDown(ret), fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(when {
                        b.trades.isEmpty() -> when (b.cfg.type) {
                            "rsi" -> "Aún no ha operado: espera a que el RSI baje de ${b.cfg.p1.toInt()} para comprar. Puede tardar días."
                            "ma" -> "Aún no ha operado: espera a que las medias se crucen hacia arriba. Puede tardar días."
                            else -> "Aún no ha operado: actúa cuando se cierre la próxima vela."
                        }
                        else -> "${b.trades.size} operaciones · última: " + b.trades.last().let {
                            (if (it.buy) "compró" else "vendió") + " a ${Fmt.usd(it.price)} el ${date(it.time)}"
                        }
                    }, color = C.Dim, fontSize = 12.sp)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Btn(if (b.active) "Pausar" else "Reanudar", C.Gold, Modifier.weight(1f), filled = false) {
                            val all = Store.bots(ctx)
                            all.firstOrNull { it.id == b.id }?.let { it.active = !it.active }
                            Store.saveBots(ctx, all); App.refresh()
                        }
                        Btn("Eliminar", C.Red, Modifier.weight(1f), filled = false) {
                            Store.saveBots(ctx, Store.bots(ctx).filter { it.id != b.id }); App.refresh()
                        }
                    }
                }
            }
        }
        Text("Un buen resultado en el pasado no garantiza el futuro. Simulación con datos reales de Binance y comisión de 0,1%.",
            color = C.Faint, fontSize = 12.sp)
    }
}

/* ================================================================== ALERTAS */

@Composable
fun AlertsScreen() {
    val ctx = LocalContext.current
    val tick = App.tick
    val alerts = remember(tick) { Store.alerts(ctx).reversed() }
    val sym = App.alertCoin
    var above by remember { mutableStateOf(true) }
    var priceTxt by remember(sym) { mutableStateOf("") }
    val now = App.price(sym)

    Screen {
        Header("Alertas", "Te aviso cuando el precio llegue")
        Card {
            Label("Nueva alerta")
            Spacer(Modifier.height(10.dp))
            CoinChips(sym) { App.alertCoin = it }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Si sube a", above, C.Green) { above = true }
                Chip("Si baja a", !above, C.Red) { above = false }
            }
            Spacer(Modifier.height(10.dp))
            Field(priceTxt, { priceTxt = it }, now?.let { "Ahora: ${Fmt.usd(it)}" } ?: "Precio", Modifier.fillMaxWidth(),
                number = true, prefix = "$")
            if (now != null) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(2, 5, 10).forEach { p ->
                        Chip("${if (above) "+" else "−"}$p%", false, if (above) C.Green else C.Red) {
                            val v = now * (1 + (if (above) p else -p) / 100.0)
                            priceTxt = String.format(Locale.US, if (v >= 1) "%.2f" else "%.6f", v)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Btn("Crear alerta", C.Gold, Modifier.fillMaxWidth()) {
                val p = parseNum(priceTxt)
                if (p == null || p <= 0) Toast.makeText(ctx, "Escribe un precio", Toast.LENGTH_SHORT).show()
                else {
                    Store.addAlert(ctx, sym, above, p); priceTxt = ""; App.refresh()
                    Toast.makeText(ctx, "Alerta creada", Toast.LENGTH_SHORT).show()
                }
            }
        }
        if (alerts.isEmpty()) {
            Text("No tienes alertas. También puedes pedirle una a la IA: “avísame si BTC baja de 90.000”.", color = C.Dim, fontSize = 13.sp)
        } else {
            Label("Tus alertas")
            Card(pad = 6.dp) {
                alerts.forEach { a ->
                    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        CoinBadge(a.symbol, 32.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${a.symbol} ${if (a.above) "sube a" else "baja a"} ${Fmt.usd(a.price)}",
                                color = if (a.fired) C.Dim else C.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(if (a.fired) "Ya se cumplió ✓" else App.price(a.symbol)?.let {
                                "Falta ${Fmt.pct((a.price / it - 1) * 100, sign = false)}"
                            } ?: "Activa", color = if (a.fired) C.Green else C.Faint, fontSize = 12.sp)
                        }
                        if (a.fired) Text("Reactivar", color = C.Blue, fontSize = 13.sp, modifier = Modifier.padding(end = 12.dp).clickable {
                            Store.saveAlerts(ctx, Store.alerts(ctx).map { if (it.id == a.id) it.copy(fired = false) else it }); App.refresh()
                        })
                        Text("✕", color = C.Dim, fontSize = 18.sp, modifier = Modifier.clickable {
                            Store.saveAlerts(ctx, Store.alerts(ctx).filter { it.id != a.id }); App.refresh()
                        }.padding(6.dp))
                    }
                }
            }
        }
        Text("Con la app cerrada reviso cada 15 minutos aproximadamente; con la app abierta, cada 15 segundos.",
            color = C.Faint, fontSize = 12.sp)
    }
}

/* ================================================================== ASISTENTE */

private fun buildContext(ctx: android.content.Context, question: String): String {
    val sb = StringBuilder()
    App.tickers.values.forEach { sb.append("${it.symbol}: ${Fmt.usd(it.price)} (${Fmt.pct(it.changePct)} 24h)\n") }
    val q = question.uppercase()
    val mentioned = Market.COINS.filter { q.contains(it.symbol) || q.contains(it.name.uppercase()) }.ifEmpty { listOf(Market.COINS[0]) }
    mentioned.take(3).forEach { c ->
        try {
            val cl = Market.candles(c.symbol, "4h", 120).map { it.close }
            val r = Ind.rsi(cl).last(); val m = Ind.sma(cl, 50).last()
            sb.append("${c.symbol} velas 4h: RSI14=${r?.toInt()}, media50=${m?.let { Fmt.usd(it) }}, " +
                "cambio 7 días=${Fmt.pct((cl.last() / cl[cl.size - 43] - 1) * 100)}\n")
        } catch (e: Exception) { }
    }
    val pos = Store.positions(ctx)
    sb.append("Cuenta virtual del usuario: efectivo ${Fmt.money(Store.cash(ctx))}; posiciones: " +
        (if (pos.isEmpty()) "ninguna" else pos.joinToString { "${Fmt.qty(it.qty)} ${it.symbol} compradas a ${Fmt.usd(it.avgCost)}" }) + "\n")
    return sb.toString()
}

@Composable
fun AssistantScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var thinking by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()

    fun send(text: String) {
        if (text.isBlank() || thinking) return
        App.chat.add(ChatMsg(true, text.trim()))
        input = ""
        thinking = true
        scope.launch {
            val reply = withContext(Dispatchers.IO) {
                val c = buildContext(ctx, text)
                Ai.ask(ctx, App.chat.takeLast(12), c)
            }
            var t = reply.text
            reply.alert?.let { a ->
                Store.addAlert(ctx, a.symbol, a.above, a.price)
                App.refresh()
                t += "\n\n✓ Alerta creada: ${a.symbol} ${if (a.above) "sube a" else "baja a"} ${Fmt.usd(a.price)}"
            }
            App.chat.add(ChatMsg(false, t))
            thinking = false
        }
    }

    LaunchedEffect(App.pendingAsk) {
        App.pendingAsk?.let { q -> App.pendingAsk = null; send(q) }
    }
    LaunchedEffect(App.chat.size, thinking) { scroll.animateScrollTo(scroll.maxValue) }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Header("Asistente", "Te explica el mercado en sencillo")
            if (App.chat.isEmpty()) {
                Card {
                    Text("Pregúntame lo que quieras sobre cripto: qué está pasando con una moneda, qué significa un indicador, " +
                        "por qué ganó o perdió un bot… También puedo crearte alertas.", color = C.Text, fontSize = 14.sp)
                }
                listOf("¿Cómo está Bitcoin hoy?", "Explícame qué es el RSI", "¿Qué estrategia de bot es más segura?",
                    "Avísame si ETH baja 5%").forEach { s ->
                    Box(Modifier.clip(RoundedCornerShape(14.dp)).background(C.Card).clickable { send(s) }.padding(12.dp)) {
                        Text(s, color = C.Blue, fontSize = 14.sp)
                    }
                }
            }
            App.chat.forEach { m ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.fromUser) Arrangement.End else Arrangement.Start) {
                    Column(
                        Modifier.widthIn(max = 300.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(if (m.fromUser) C.Green.copy(alpha = 0.16f) else C.Card)
                            .padding(12.dp)
                    ) {
                        Text(m.text, color = C.Text, fontSize = 14.sp, lineHeight = 20.sp)
                        if (!m.fromUser) {
                            Spacer(Modifier.height(6.dp))
                            Text("Esto es explicación, no recomendación de inversión.", color = C.Faint, fontSize = 11.sp)
                        }
                    }
                }
            }
            if (thinking) Text("Pensando…", color = C.Dim, fontSize = 13.sp)
        }
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Field(input, { input = it }, "Escribe tu pregunta…", Modifier.weight(1f), single = false)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(if (thinking) C.Faint else C.Green)
                .clickable(enabled = !thinking) { send(input) }, contentAlignment = Alignment.Center) {
                Text("➤", color = C.Bg, fontSize = 18.sp)
            }
        }
    }
}

/* ================================================================== AJUSTES */

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    var gemini by remember { mutableStateOf(Store.geminiKey(ctx)) }
    var groq by remember { mutableStateOf(Store.groqKey(ctx)) }
    Screen {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(C.Card).clickable { App.settings = false },
                contentAlignment = Alignment.Center) { Icon(Icons.Filled.ArrowBack, "Atrás", tint = C.Text) }
            Spacer(Modifier.width(12.dp))
            Title("Ajustes")
        }
        Card {
            Label("Asistente con IA")
            Spacer(Modifier.height(8.dp))
            Text("Pega tus claves gratis. Uso Gemini primero y, si falla o se agota, Groq.", color = C.Dim, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            Text("Clave de Gemini (aistudio.google.com)", color = C.Text, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Field(gemini, { gemini = it }, "AIza…", Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Text("Clave de Groq (console.groq.com)", color = C.Text, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Field(groq, { groq = it }, "gsk_…", Modifier.fillMaxWidth())
            Spacer(Modifier.height(14.dp))
            Btn("Guardar", modifier = Modifier.fillMaxWidth()) {
                Store.setKeys(ctx, gemini, groq)
                Toast.makeText(ctx, "Claves guardadas", Toast.LENGTH_SHORT).show()
                App.settings = false
            }
        }
        Card {
            Label("Sobre Vela")
            Spacer(Modifier.height(8.dp))
            Text("Versión ${ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName}", color = C.Text, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            Text("Precios públicos de Binance. Todo el dinero es virtual: la app no se conecta a ninguna cuenta ni puede comprar de verdad.",
                color = C.Dim, fontSize = 13.sp)
        }
    }
}
