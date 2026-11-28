package com.mohit.dimplex.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.mohit.dimplex.app
import com.mohit.dimplex.ble.Dimplex
import com.mohit.dimplex.ble.HeaterController
import com.mohit.dimplex.data.HeaterConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private data class SlotText(val start: String, val end: String, val temp: String)

private fun hhmm(m: Int) = "%02d:%02d".format(m / 60, m % 60)
private fun parseHhmm(s: String): Int? {
    val p = s.trim().split(":")
    if (p.size != 2) return null
    val h = p[0].toIntOrNull() ?: return null; val m = p[1].toIntOrNull() ?: return null
    return if (h in 0..23 && m in 0..59) h * 60 + m else if (h == 24 && m == 0) 1439 else null
}

/** Validate a day's rows; returns slots or an error message. */
private fun toSlots(rows: List<SlotText>): Pair<List<Dimplex.Slot>?, String?> {
    val out = rows.mapIndexed { i, r ->
        val s = parseHhmm(r.start) ?: return null to "Slot ${i + 1}: bad start '${r.start}' (HH:MM)"
        val e = parseHhmm(r.end) ?: return null to "Slot ${i + 1}: bad end '${r.end}' (HH:MM)"
        val t = r.temp.trim().toIntOrNull()?.takeIf { it in Dimplex.MIN_TEMP..Dimplex.MAX_TEMP }
            ?: return null to "Slot ${i + 1}: temperature must be ${Dimplex.MIN_TEMP}–${Dimplex.MAX_TEMP}"
        if (e <= s) return null to "Slot ${i + 1}: end must be after start"
        Dimplex.Slot(s, e, t)
    }.sortedBy { it.start }
    for (i in 1 until out.size) if (out[i].start < out[i - 1].end) return null to "Slots overlap"
    if (out.any { it.isDummy }) return null to "1-minute 7 °C slots are reserved; use 2+ minutes"
    return out to null
}

private val PRESETS = listOf(
    "Cheap-rate morning 05:00–07:20 @30" to listOf(SlotText("05:00", "07:20", "30")),
    "Morning + evening 21°" to listOf(SlotText("06:30", "08:30", "21"), SlotText("17:00", "22:00", "21")),
    "Night 00:30–04:30 @25" to listOf(SlotText("00:30", "04:30", "25")),
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TimerScreen(nav: NavController, id: String) {
    val app = LocalContext.current.app
    val h = app.heaters.byId(id)
    val scope = rememberCoroutineScope()
    val days = remember { List(7) { mutableStateListOf<SlotText>() } }
    var perDay by remember { mutableStateOf(false) }
    var day by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("Reading schedule…") }
    var busy by remember { mutableStateOf(true) }
    val results = remember { mutableStateListOf<String>() }

    suspend fun load() {
        if (h == null) { status = "Unknown heater"; busy = false; return }
        busy = true; status = "Reading schedule…"
        try {
            val sch = app.controller.readSchedule(h)
            sch.forEachIndexed { d, slots ->
                days[d].clear(); days[d].addAll(slots.filterNot { it.isDummy || it.end <= it.start }.map { SlotText(hhmm(it.start), hhmm(it.end), it.temp.toString()) })
            }
            perDay = (1..6).any { days[it].toList() != days[0].toList() }
            status = if (sch.all { s -> s.all { it.isDummy } }) "Current schedule: empty (Off)" else "Loaded current schedule"
        } catch (e: CancellationException) { throw e } catch (e: Exception) { status = "Read failed: ${e.message}" }
        busy = false
    }
    LaunchedEffect(id) { load() }

    fun save(targets: List<HeaterConfig>) {
        val plan = ArrayList<List<Dimplex.Slot>>()
        for (d in 0..6) {
            val (slots, err) = toSlots(if (perDay) days[d] else days[0])
            if (err != null) { status = (if (perDay) "${HeaterController.DAYS[d]}: " else "") + err; return }
            plan += slots!!
        }
        if (plan.all { it.isEmpty() }) { status = "Add at least one slot (use Off on the dashboard to turn off)"; return }
        results.clear()
        scope.launch {
            busy = true
            for (t in targets) {
                status = "Writing ${t.label}…"
                val r = try { app.controller.writeSchedule(t, plan) ?: "OK (verified)" }
                    catch (e: CancellationException) { throw e } catch (e: Exception) { "failed: ${e.message}" }
                results += "${t.label}: $r"
            }
            status = "Done"; busy = false
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Timer · ${h?.label ?: id}") }, navigationIcon = {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        }, actions = { TextButton(enabled = !busy, onClick = { scope.launch { load() } }) { Text("Reload") } })
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(status, style = MaterialTheme.typography.titleSmall)
            Text("Up to 4 heating periods per day. Outside them the heater is off.", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Different schedule per day", Modifier.weight(1f))
                Switch(checked = perDay, onCheckedChange = { on ->
                    if (on) for (d in 1..6) { days[d].clear(); days[d].addAll(days[0]) }
                    perDay = on; day = 0
                })
            }
            if (perDay) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                HeaterController.DAYS.forEachIndexed { i, n -> FilterChip(selected = day == i, onClick = { day = i }, label = { Text(n) }) }
            }
            val rows = days[if (perDay) day else 0]
            Text("Presets", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PRESETS.forEach { (name, p) -> OutlinedButton(onClick = { rows.clear(); rows.addAll(p) }) { Text(name) } }
            }
            rows.forEachIndexed { i, r ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(r.start, { rows[i] = r.copy(start = it) }, label = { Text("Start") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(r.end, { rows[i] = r.copy(end = it) }, label = { Text("End") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(r.temp, { rows[i] = r.copy(temp = it) }, label = { Text("°C") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(0.8f))
                    TextButton(onClick = { rows.removeAt(i) }) { Text("✕") }
                }
            }
            if (rows.size < 4) OutlinedButton(onClick = { rows.add(SlotText("07:00", "09:00", "21")) }) { Text("Add period") }
            if (perDay) TextButton(onClick = { for (d in 0..6) if (d != day) { days[d].clear(); days[d].addAll(rows) } }) {
                Text("Copy ${HeaterController.DAYS[day]} to all days")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy && h != null, onClick = { save(listOfNotNull(h)) }) { Text("Save & use timer") }
                OutlinedButton(enabled = !busy, onClick = { save(app.heaters.list.value) }) { Text("Apply to all heaters") }
            }
            results.forEach { Text(it) }
        }
    }
}
