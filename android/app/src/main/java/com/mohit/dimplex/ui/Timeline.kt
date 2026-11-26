package com.mohit.dimplex.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.mohit.dimplex.app
import com.mohit.dimplex.data.RssiSample
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A stay in one room; heaterId null = no heater heard well enough (away / unknown). */
data class Stay(val heaterId: String?, val start: Long, val end: Long)

/**
 * Turns raw RSSI samples into room stays:
 * 1. 1-minute windows; per heater, the window's mean RSSI.
 * 2. The window's room is the loudest heater, if it is above [floorDbm] and beats the runner-up by
 *    [marginDb] (otherwise the previous room is kept, as the signal is ambiguous).
 * 3. Hysteresis: a new room must win [confirm] windows in a row before we switch.
 * 4. Windows with no samples at all for more than [gapMin] minutes become "unknown".
 */
fun computeStays(
    samples: List<RssiSample>, windowMs: Long = 60_000, floorDbm: Double = -92.0,
    marginDb: Double = 3.0, confirm: Int = 2, gapMin: Int = 5,
): List<Stay> {
    if (samples.isEmpty()) return emptyList()
    val byWindow = samples.groupBy { it.ts / windowMs }
    val first = byWindow.keys.min(); val last = byWindow.keys.max()
    val rooms = ArrayList<Pair<Long, String?>>()
    var current: String? = null; var candidate: String? = null; var streak = 0; var empty = 0
    for (w in first..last) {
        val win = byWindow[w]
        if (win == null) {
            if (++empty > gapMin) current = null
            rooms += w to current; continue
        }
        empty = 0
        val means = win.groupBy { it.heaterId }.mapValues { (_, v) -> v.map { it.rssi }.average() }
            .entries.sortedByDescending { it.value }
        val best = means.first()
        val clear = best.value >= floorDbm && (means.size < 2 || best.value - means[1].value >= marginDb)
        val winner = when {
            best.value < floorDbm -> null
            clear -> best.key
            else -> current // ambiguous: stay put
        }
        if (winner == current) { candidate = null; streak = 0 }
        else if (winner == candidate) { if (++streak >= confirm) { current = winner; candidate = null; streak = 0 } }
        else { candidate = winner; streak = 1; if (confirm <= 1) { current = winner; candidate = null; streak = 0 } }
        rooms += w to current
    }
    val out = ArrayList<Stay>()
    for ((w, r) in rooms) {
        val s = w * windowMs; val e = s + windowMs
        val prev = out.lastOrNull()
        if (prev != null && prev.heaterId == r) out[out.size - 1] = prev.copy(end = e) else out += Stay(r, s, e)
    }
    return out
}

private val palette = listOf(
    Color(0xFF4E79A7), Color(0xFFF28E2B), Color(0xFF59A14F), Color(0xFFE15759),
    Color(0xFF76B7B2), Color(0xFFB07AA1), Color(0xFFEDC948), Color(0xFF9C755F),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineScreen(nav: NavController) {
    val app = LocalContext.current.app
    var hours by remember { mutableIntStateOf(6) }
    val heaters by app.heaters.list.collectAsState()
    val since = remember(hours) { System.currentTimeMillis() - hours * 3_600_000L }
    val samples by remember(since) { app.db.rssi().allSince(since) }.collectAsState(initial = emptyList())
    val stays = remember(samples) { computeStays(samples) }
    val color = { id: String? -> id?.let { i -> palette[heaters.indexOfFirst { it.id == i }.coerceAtLeast(0) % palette.size] } ?: Color.Gray }
    val name = { id: String? -> id?.let { i -> heaters.firstOrNull { it.id == i }?.label ?: "?" } ?: "Away / unknown" }
    val fmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Room timeline") },
            navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 6, 24, 72).forEach { h -> FilterChip(hours == h, { hours = h }, { Text("${h}h") }) }
            }
            if (stays.isEmpty()) {
                Text("No signal data yet. Keep the background service and RSSI scanning on (Settings).")
                return@Column
            }
            // Colour bar across the selected range
            val t0 = stays.first().start; val t1 = stays.last().end
            Canvas(Modifier.fillMaxWidth().height(28.dp)) {
                stays.forEach { s ->
                    val x = size.width * (s.start - t0) / (t1 - t0).coerceAtLeast(1)
                    val w = size.width * (s.end - s.start) / (t1 - t0).coerceAtLeast(1)
                    drawRect(color(s.heaterId), Offset(x, 0f), Size(w, size.height))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(fmt.format(Date(t0)), style = MaterialTheme.typography.bodySmall)
                Text(fmt.format(Date(t1)), style = MaterialTheme.typography.bodySmall)
            }
            // Time per room
            val totals = stays.groupBy { it.heaterId }.mapValues { (_, v) -> v.sumOf { it.end - it.start } }
                .entries.sortedByDescending { it.value }
            totals.forEach { (id, ms) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).background(color(id), CircleShape)); Spacer(Modifier.width(8.dp))
                    Text("${name(id)}: ${ms / 3_600_000}h ${(ms / 60_000) % 60}m")
                }
            }
            Text("Stays (newest first)", style = MaterialTheme.typography.titleSmall)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(stays.reversed().filter { it.end - it.start >= 60_000 }) { s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(color(s.heaterId), CircleShape)); Spacer(Modifier.width(8.dp))
                        Text("${fmt.format(Date(s.start))}–${fmt.format(Date(s.end))}  ${name(s.heaterId)}  (${(s.end - s.start) / 60_000} min)")
                    }
                }
            }
        }
    }
}
