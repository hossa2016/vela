package com.hossa.vela

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text

object C {
    val Bg = Color(0xFF0B0D10)
    val Card = Color(0xFF13171D)
    val Card2 = Color(0xFF1A1F27)
    val Line = Color(0xFF242B35)
    val Text = Color(0xFFE9EDF2)
    val Dim = Color(0xFF8A94A3)
    val Faint = Color(0xFF5A6370)
    val Green = Color(0xFF3DDC97)
    val Red = Color(0xFFF0616D)
    val Gold = Color(0xFFF5C26B)
    val Blue = Color(0xFF8EA8FF)
}

val Mono = FontFamily.Monospace

fun upDown(v: Double) = if (v >= 0) C.Green else C.Red

@Composable
fun Card(modifier: Modifier = Modifier, pad: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(C.Card)
            .border(1.dp, C.Line, RoundedCornerShape(20.dp))
            .padding(pad),
        content = content
    )
}

@Composable
fun Label(t: String, color: Color = C.Dim) =
    Text(t.uppercase(), color = color, fontSize = 11.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.SemiBold)

@Composable
fun Title(t: String) = Text(t, color = C.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)

@Composable
fun Chip(text: String, selected: Boolean, accent: Color = C.Green, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) accent.copy(alpha = 0.16f) else C.Card2)
            .border(1.dp, if (selected) accent.copy(alpha = 0.6f) else C.Line, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(text, color = if (selected) accent else C.Dim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun Btn(text: String, color: Color = C.Green, modifier: Modifier = Modifier, filled: Boolean = true,
        enabled: Boolean = true, onClick: () -> Unit) {
    val c = if (enabled) color else C.Faint
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (filled) c else Color.Transparent)
            .border(1.dp, if (filled) c else c.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (filled) Color(0xFF0B0D10) else c, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

@Composable
fun Field(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier,
          number: Boolean = false, single: Boolean = true, prefix: String = "") {
    Row(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(C.Card2)
            .border(1.dp, C.Line, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (prefix.isNotEmpty()) Text(prefix, color = C.Dim, fontSize = 16.sp, modifier = Modifier.padding(end = 4.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(hint, color = C.Faint, fontSize = 16.sp)
            BasicTextField(
                value, onChange,
                singleLine = single,
                textStyle = TextStyle(color = C.Text, fontSize = 16.sp),
                cursorBrush = SolidColor(C.Green),
                keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun CoinBadge(symbol: String, size: Dp = 38.dp) {
    val c = Market.coin(symbol)
    Box(
        Modifier.size(size).clip(CircleShape).background(Color(c.color).copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center
    ) {
        Text(c.glyph, color = Color(c.color), fontWeight = FontWeight.Bold, fontSize = (size.value * 0.42f).sp)
    }
}

@Composable
fun Stat(label: String, value: String, color: Color = C.Text, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, color = C.Dim, fontSize = 12.sp)
        Spacer(Modifier.height(3.dp))
        Text(value, color = color, fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = Mono)
    }
}

/* ------------------------------------------------------------------ Gráficas */

@Composable
fun Sparkline(values: List<Double>, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val mn = values.min(); val mx = values.max()
        val r = (mx - mn).takeIf { it > 0 } ?: 1.0
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = size.width * i / (values.size - 1)
            val y = (size.height * (1 - (v - mn) / r)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val fill = Path().apply { addPath(path); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.25f), Color.Transparent)))
        drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
fun CandleChart(candles: List<Candle>, ma: List<Double?>, modifier: Modifier) {
    Canvas(modifier) {
        if (candles.isEmpty()) return@Canvas
        var mn = candles.minOf { it.low }; var mx = candles.maxOf { it.high }
        ma.filterNotNull().forEach { mn = minOf(mn, it); mx = maxOf(mx, it) }
        val r = (mx - mn).takeIf { it > 0 } ?: 1.0
        fun y(v: Double) = (size.height * (1 - (v - mn) / r)).toFloat()
        // líneas guía
        for (k in 1..3) {
            val gy = size.height * k / 4
            drawLine(C.Line, Offset(0f, gy), Offset(size.width, gy), 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f)))
        }
        val w = size.width / candles.size
        candles.forEachIndexed { i, c ->
            val col = if (c.close >= c.open) C.Green else C.Red
            val cx = w * i + w / 2
            drawLine(col, Offset(cx, y(c.high)), Offset(cx, y(c.low)), maxOf(1f, w * 0.12f))
            val top = y(maxOf(c.open, c.close)); val bot = y(minOf(c.open, c.close))
            drawRect(col, Offset(cx - w * 0.34f, top), Size(w * 0.68f, maxOf(1.5f, bot - top)))
        }
        val p = Path(); var started = false
        ma.forEachIndexed { i, v ->
            if (v == null) return@forEachIndexed
            val x = w * i + w / 2
            if (!started) { p.moveTo(x, y(v)); started = true } else p.lineTo(x, y(v))
        }
        drawPath(p, C.Gold, style = Stroke(width = 1.8.dp.toPx()))
    }
}

@Composable
fun RsiChart(rsi: List<Double?>, modifier: Modifier) {
    Canvas(modifier) {
        fun y(v: Double) = (size.height * (1 - v / 100)).toFloat()
        drawRect(C.Blue.copy(alpha = 0.06f), Offset(0f, y(70.0)), Size(size.width, y(30.0) - y(70.0)))
        listOf(30.0, 70.0).forEach {
            drawLine(C.Line, Offset(0f, y(it)), Offset(size.width, y(it)), 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f)))
        }
        val w = size.width / maxOf(1, rsi.size)
        val p = Path(); var started = false
        rsi.forEachIndexed { i, v ->
            if (v == null) return@forEachIndexed
            val x = w * i + w / 2
            if (!started) { p.moveTo(x, y(v)); started = true } else p.lineTo(x, y(v))
        }
        drawPath(p, C.Blue, style = Stroke(width = 1.8.dp.toPx()))
    }
}

/** Curva del bot (verde/rojo) contra comprar y esperar (gris punteado). */
@Composable
fun EquityChart(equity: List<Double>, hold: List<Double>, start: Double, modifier: Modifier) {
    Canvas(modifier) {
        if (equity.size < 2) return@Canvas
        val step = maxOf(1, equity.size / 400)
        val e = equity.filterIndexed { i, _ -> i % step == 0 } + equity.last()
        val h = hold.filterIndexed { i, _ -> i % step == 0 } + hold.last()
        val mn = minOf(e.min(), h.min(), start); val mx = maxOf(e.max(), h.max(), start)
        val r = (mx - mn).takeIf { it > 0 } ?: 1.0
        fun y(v: Double) = (size.height * (1 - (v - mn) / r)).toFloat()
        fun path(v: List<Double>) = Path().apply {
            v.forEachIndexed { i, d ->
                val x = size.width * i / (v.size - 1)
                if (i == 0) moveTo(x, y(d)) else lineTo(x, y(d))
            }
        }
        drawLine(C.Faint, Offset(0f, y(start)), Offset(size.width, y(start)), 1f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f)))
        drawPath(path(h), C.Dim, style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
        val col = if (e.last() >= start) C.Green else C.Red
        val ep = path(e)
        val fill = Path().apply { addPath(ep); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
        drawPath(fill, Brush.verticalGradient(listOf(col.copy(alpha = 0.22f), Color.Transparent)))
        drawPath(ep, col, style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round))
    }
}
