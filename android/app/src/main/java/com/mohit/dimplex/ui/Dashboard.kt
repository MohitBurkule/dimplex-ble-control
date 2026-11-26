package com.mohit.dimplex.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.mohit.dimplex.app
import com.mohit.dimplex.ble.Dimplex
import com.mohit.dimplex.ble.LinkStatus
import com.mohit.dimplex.ble.Pairing
import com.mohit.dimplex.data.HeaterConfig
import com.mohit.dimplex.data.HeaterRssi
import com.mohit.dimplex.data.Reading
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

@Composable
fun rememberNow(periodMs: Long = 10_000): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(periodMs) { while (true) { delay(periodMs); now = System.currentTimeMillis() } }
    return now
}

fun ageText(ts: Long, now: Long): String {
    val s = (now - ts) / 1000
    return when { s < 60 -> "${s}s ago"; s < 3600 -> "${s / 60}m ago"; s < 86400 -> "${s / 3600}h ago"; else -> "${s / 86400}d ago" }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalCoroutinesApi::class)
@Composable
fun DashboardScreen(nav: NavController) {
    val ctx = LocalContext.current
    val app = ctx.app
    val heaters by app.heaters.list.collectAsState()
    val latest by app.db.readings().latest().collectAsState(initial = emptyList())
    val status by app.controller.status.collectAsState()
    val rssiFlow = remember {
        // Re-query the 2-minute window every 15 s.
        flow { while (true) { emit(System.currentTimeMillis() - 120_000); delay(15_000) } }
            .flatMapLatest { app.db.rssi().recent(it) }
    }
    val rssi by rssiFlow.collectAsState(initial = emptyList())
    val prompt by Pairing.pinPrompt.collectAsState()
    val scope = rememberCoroutineScope()
    val now = rememberNow()

    val avgHours = app.settings.avgHours
    val avgFlow = remember(avgHours) {
        flow { while (true) { emit(System.currentTimeMillis() - avgHours * 3600_000L); delay(60_000) } }
            .flatMapLatest { app.db.readings().avgRoomTemp(it) }
    }
    val avgs by avgFlow.collectAsState(initial = emptyList())
    val nearest = rssi.filter { r -> heaters.any { it.id == r.heaterId } }.maxByOrNull { it.rssi }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Dimplex Heaters") }, actions = {
            IconButton(onClick = { scope.launch { app.controller.pollAll() } }) { Icon(Icons.Default.Refresh, "Refresh all") }
            IconButton(onClick = { nav.navigate("timeline") }) { Icon(Icons.Default.Timeline, "Room timeline") }
            IconButton(onClick = { nav.navigate("floorplan") }) { Icon(Icons.Default.Map, "Floorplan") }
            IconButton(onClick = { nav.navigate("heaters") }) { Icon(Icons.Default.List, "Heaters") }
            IconButton(onClick = { nav.navigate("settings") }) { Icon(Icons.Default.Settings, "Settings") }
        })
    }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                val label = nearest?.let { n -> heaters.firstOrNull { it.id == n.heaterId }?.label }
                Text(
                    if (label != null) "You are probably near: $label (%.0f dBm)".format(nearest!!.rssi) else "Nearest heater: no recent RSSI",
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp),
                )
            }
            item { com.mohit.dimplex.update.UpdateCard() }
            if (heaters.isEmpty()) item { Text("No heaters configured. Tap the list icon to import a JSON config or add one.") }
            items(heaters, key = { it.id }) { h ->
                HeaterCard(h, latest.firstOrNull { it.heaterId == h.id }, status[h.id], rssi.firstOrNull { it.heaterId == h.id },
                    avgs.firstOrNull { it.heaterId == h.id }?.avg, avgHours, now, scope,
                    onOpen = { nav.navigate("detail/${h.id}") })
            }
        }
    }

    prompt?.let { (label, pin) ->
        AlertDialog(onDismissRequest = {}, confirmButton = { TextButton(onClick = { Pairing.pinPrompt.value = null }) { Text("OK") } },
            title = { Text("Pairing $label") },
            text = { Text("If Android asks for a PIN, enter:\n\n$pin", style = MaterialTheme.typography.titleLarge) })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeaterCard(h: HeaterConfig, r: Reading?, st: LinkStatus?, rssi: HeaterRssi?, avg: Double?, avgHours: Int, now: Long, scope: CoroutineScope, onOpen: () -> Unit) {
    val ctx = LocalContext.current
    val ctl = ctx.app.controller
    var slider by remember(r?.setpoint) { mutableFloatStateOf((r?.setpoint ?: 20).toFloat()) }

    fun run(desc: String, block: suspend () -> Unit) = scope.launch {
        try { block() } catch (e: Exception) { Toast.makeText(ctx, "$desc failed: ${e.message}", Toast.LENGTH_LONG).show() }
    }

    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(h.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(rssi?.let { "%.0f dBm".format(it.rssi) } ?: "— dBm", style = MaterialTheme.typography.labelMedium)
            }
            if (st?.busy == true) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (r == null) Text("No readings yet") else {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(r.roomTemp?.let { "%.1f °C".format(it) } ?: "? °C", style = MaterialTheme.typography.displaySmall)
                    Spacer(Modifier.width(12.dp))
                    Text("avg ${avgHours}h: " + (avg?.let { "%.1f °C".format(it) } ?: "—"), style = MaterialTheme.typography.titleMedium)
                }
                Text(buildString {
                    append("Target ${r.setpoint ?: "?"} °C  ·  ${Dimplex.modeName(r.mode)}")
                    if (r.heating == true) append("  ·  heating")
                    if ((r.error ?: 0) != 0) append("  ·  ERROR ${r.error}")
                }, style = MaterialTheme.typography.bodyLarge)
                Text("updated ${ageText(r.ts, now)}${st?.model?.let { " · $it" } ?: ""}", style = MaterialTheme.typography.labelSmall)
            }
            st?.lastError?.let { Text("Last error: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { slider = (slider - 1).coerceAtLeast(7f); run("Set temp") { ctl.setTemp(h, slider.toInt()) } }) { Text("−") }
                Slider(value = slider, onValueChange = { slider = it.toInt().toFloat() }, valueRange = 7f..30f, steps = 22,
                    onValueChangeFinished = { run("Set temp") { ctl.setTemp(h, slider.toInt()) } }, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                OutlinedButton(onClick = { slider = (slider + 1).coerceAtMost(30f); run("Set temp") { ctl.setTemp(h, slider.toInt()) } }) { Text("+") }
            }
            Text("Target ${slider.toInt()} °C", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(Dimplex.MODE_MANUAL to "Manual", Dimplex.MODE_ECO to "Eco", Dimplex.MODE_FROST to "Frost (Off)", Dimplex.MODE_TIMER to "Timer").forEach { (m, name) ->
                    FilterChip(selected = r?.mode == m, onClick = { run("Set mode") { ctl.setMode(h, m) } }, label = { Text(name) })
                }
                Spacer(Modifier.width(4.dp))
                OutlinedButton(onClick = { run("Off") { ctl.off(h) } }) { Text("Off") }
                TextButton(onClick = { run("Refresh") { ctl.poll(h) } }) { Text("Refresh") }
            }
        }
    }
}
