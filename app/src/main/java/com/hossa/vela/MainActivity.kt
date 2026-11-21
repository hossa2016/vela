package com.hossa.vela

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Estado compartido de la interfaz. */
object App {
    var tickers by mutableStateOf<Map<String, Ticker>>(emptyMap())
    var sparks by mutableStateOf<Map<String, List<Double>>>(emptyMap())
    var online by mutableStateOf(true)
    var tab by mutableStateOf(0)
    var detail by mutableStateOf<String?>(null)
    var simCoin by mutableStateOf("BTC")
    var alertCoin by mutableStateOf("BTC")
    var settings by mutableStateOf(false)
    var tick by mutableStateOf(0)
    var pendingAsk by mutableStateOf<String?>(null)
    val chat: SnapshotStateList<ChatMsg> = mutableStateListOf()

    fun price(s: String) = tickers[s]?.price
    fun refresh() { tick++ }

    fun portfolio(ctx: Context): Pair<Double, Double> {
        val cash = Store.cash(ctx)
        val inCoins = Store.positions(ctx).sumOf { it.qty * (price(it.symbol) ?: it.avgCost) }
        return cash to inCoins
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MarketWorker.schedule(this)
        setContent { VelaApp() }
    }
}

@Composable
fun VelaApp() {
    val ctx = LocalContext.current
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) perm.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    // Precios en vivo mientras la app está abierta
    LaunchedEffect(Unit) {
        var n = 0
        while (true) {
            try {
                val t = withContext(Dispatchers.IO) { Market.tickers() }
                App.tickers = t.associateBy { it.symbol }
                App.online = true
                withContext(Dispatchers.IO) { Alerts.check(ctx, App.tickers.mapValues { it.value.price }) }
                if (n % 20 == 0) {
                    val s = withContext(Dispatchers.IO) {
                        Market.COINS.associate { c ->
                            c.symbol to try { Market.candles(c.symbol, "1h", 24).map { it.close } } catch (e: Exception) { emptyList() }
                        }
                    }
                    App.sparks = s
                }
                n++
            } catch (e: Exception) {
                App.online = false
            }
            delay(15_000)
        }
    }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { Bots.step(ctx, notify = false) }
        App.refresh()
    }

    BackHandler(enabled = App.settings || App.detail != null || App.tab != 0) {
        when {
            App.settings -> App.settings = false
            App.detail != null -> App.detail = null
            else -> App.tab = 0
        }
    }

    Column(Modifier.fillMaxSize().background(C.Bg)) {
        Box(Modifier.weight(1f)) {
            if (App.settings) SettingsScreen()
            else when (App.tab) {
                0 -> if (App.detail != null) DetailScreen(App.detail!!) else MarketScreen()
                1 -> SimScreen()
                2 -> BotsScreen()
                3 -> AlertsScreen()
                else -> AssistantScreen()
            }
        }
        if (!App.settings) BottomBar()
    }
}

@Composable
private fun BottomBar() {
    val items = listOf<Pair<String, ImageVector>>(
        "Mercado" to Icons.Filled.Home,
        "Simular" to Icons.Filled.ShoppingCart,
        "Bots" to Icons.Filled.Build,
        "Alertas" to Icons.Filled.Notifications,
        "IA" to Icons.Filled.Face
    )
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(C.Line))
        Row(Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Color(0xFF0F1216)).padding(vertical = 8.dp)) {
            items.forEachIndexed { i, (label, icon) ->
                val sel = App.tab == i
                Column(
                    Modifier.weight(1f).clickable { App.tab = i; if (i == 0) App.detail = null },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        Modifier
                            .background(if (sel) C.Green.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Transparent,
                                androidx.compose.foundation.shape.RoundedCornerShape(50))
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    ) { Icon(icon, label, tint = if (sel) C.Green else C.Dim, modifier = Modifier.size(22.dp)) }
                    Spacer(Modifier.height(3.dp))
                    Text(label, color = if (sel) C.Text else C.Dim, fontSize = 11.sp,
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}
