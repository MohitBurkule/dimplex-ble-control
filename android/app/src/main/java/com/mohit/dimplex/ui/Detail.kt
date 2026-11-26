package com.mohit.dimplex.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.mohit.dimplex.app
import kotlinx.coroutines.launch
import java.text.DateFormat
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(nav: NavController, id: String) {
    val ctx = LocalContext.current
    val app = ctx.app
    val h = app.heaters.byId(id)
    val ranges = listOf("6h" to 6, "24h" to 24, "7d" to 168, "30d" to 720)
    var hours by remember { mutableIntStateOf(24) }
    val now = rememberNow(30_000)
    val since = remember(hours, now / 60_000) { System.currentTimeMillis() - hours * 3600_000L }
    val readings by remember(id, since) { app.db.readings().history(id, since) }.collectAsState(initial = emptyList())
    val rssi by remember(id, since) { app.db.rssi().history(id, since) }.collectAsState(initial = emptyList())
    val status by app.controller.status.collectAsState()
    val st = status[id]
    val scope = rememberCoroutineScope()
    fun run(desc: String, block: suspend () -> Unit) = scope.launch {
        try { block() } catch (e: Exception) { Toast.makeText(ctx, "$desc failed: ${e.message}", Toast.LENGTH_LONG).show() }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text(h?.label ?: id) }, navigationIcon = {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        })
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ranges.forEach { (n, v) -> FilterChip(selected = hours == v, onClick = { hours = v }, label = { Text(n) }) }
            }
            val temps = readings.mapNotNull { it.roomTemp }
            Text(
                if (temps.isEmpty()) "Room temp: no data in range"
                else "Room temp  min %.1f · avg %.1f · max %.1f °C  (%d samples)".format(temps.min(), temps.average(), temps.max(), temps.size),
                style = MaterialTheme.typography.titleSmall,
            )
            LineChart("Temperature", listOf(
                Series("room", Color(0xFFE53935), readings.mapNotNull { r -> r.roomTemp?.let { r.ts to it } }),
                Series("setpoint", Color(0xFF1E88E5), readings.mapNotNull { r -> r.setpoint?.let { r.ts to it.toDouble() } }, stepped = true),
            ), since, now, "°C")
            LineChart("RSSI", listOf(
                Series("adv", Color(0xFF43A047), rssi.filter { it.source == "adv" }.map { it.ts to it.rssi.toDouble() }),
                Series("connected", Color(0xFFFB8C00), rssi.filter { it.source == "conn" }.map { it.ts to it.rssi.toDouble() }),
            ), since, now, "dBm")

            if (h != null) {
                Text("Heater clock", style = MaterialTheme.typography.titleMedium)
                val clock = st?.clock; val at = st?.clockReadAt
                if (clock == null || at == null) Text("Not read yet (refresh)")
                else {
                    val drift = (clock - at) / 1000
                    val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    Text("${fmt.format(clock)}  ·  drift ${if (drift >= 0) "+" else "−"}${fmtDuration(abs(drift))}  (read ${ageText(at, now)})")
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { run("Sync clock") { app.controller.syncClock(h) } }) { Text("Sync clock") }
                    OutlinedButton(onClick = { run("Refresh") { app.controller.poll(h) } }) { Text("Refresh") }
                }

                Text("Adaptive settings", style = MaterialTheme.typography.titleMedium)
                ToggleRow("Adaptive start (pre-emptive heating)", st?.preemptive) { on -> run("Adaptive start") { app.controller.setPreemptive(h, on) } }
                ToggleRow("Open window detection", st?.openWindow) { on -> run("Open window") { app.controller.setOpenWindow(h, on) } }
                st?.model?.let { Text("Model: $it · ${h.address}", style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, value: Boolean?, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label + if (value == null) " (unknown)" else "", Modifier.weight(1f))
        Switch(checked = value == true, enabled = value != null, onCheckedChange = onChange)
    }
}

fun fmtDuration(s: Long): String = when {
    s < 120 -> "${s}s"
    s < 7200 -> "${s / 60}m"
    s < 172800 -> "${s / 3600}h"
    s < 86400L * 730 -> "${s / 86400}d"
    else -> "${s / (86400L * 365)}y"
}
