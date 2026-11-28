package com.mohit.dimplex.ui

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.mohit.dimplex.app
import com.mohit.dimplex.ble.Dimplex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** "Export" button: CSV of readings + RSSI via SAF. [heaterId] non-null offers "this heater" or "all". */
@Composable
fun ExportButton(heaterId: String?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf<String?>(null) }
    var ask by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch {
            try {
                val n = withContext(Dispatchers.IO) { exportCsv(ctx, uri, target) }
                Toast.makeText(ctx, "Exported $n rows", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) { Toast.makeText(ctx, "Export failed: ${e.message}", Toast.LENGTH_LONG).show() }
        }
    }
    fun go(id: String?) {
        target = id
        val name = (id?.let { ctx.app.heaters.byId(it)?.label } ?: "all").replace(Regex("[^A-Za-z0-9_-]"), "_")
        launcher.launch("dimplex-$name-${java.time.LocalDate.now()}.csv")
    }
    TextButton(onClick = { if (heaterId == null) go(null) else ask = true }) { Text("Export") }
    if (ask) AlertDialog(onDismissRequest = { ask = false },
        title = { Text("Export history (CSV)") },
        confirmButton = { TextButton(onClick = { ask = false; go(heaterId) }) { Text("This heater") } },
        dismissButton = { TextButton(onClick = { ask = false; go(null) }) { Text("All heaters") } })
}

private fun csv(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

private suspend fun exportCsv(ctx: Context, uri: Uri, id: String?): Int {
    val app = ctx.app
    val labels = app.heaters.list.value.associate { it.id to it.label }
    val fmt = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault())
    fun t(ts: Long) = fmt.format(Instant.ofEpochMilli(ts))
    val readings = app.db.readings().all(id)
    val rssi = app.db.rssi().all(id)
    val out = ctx.contentResolver.openOutputStream(uri) ?: throw IllegalStateException("cannot open file")
    out.bufferedWriter().use { w ->
        w.write("type,time,heater,mode,setpoint,roomTemp,heating,watts,error,rssi,rssiSource\n")
        for (r in readings) {
            val mode = if (r.off) "Off" else if (r.mode == null) "" else Dimplex.modeName(r.mode)
            w.write(listOf("reading", t(r.ts), csv(labels[r.heaterId] ?: r.heaterId), mode, r.setpoint?.toString() ?: "",
                r.roomTemp?.toString() ?: "", r.heating?.toString() ?: "", r.watts?.toString() ?: "", r.error?.toString() ?: "", "", "").joinToString(","))
            w.write("\n")
        }
        for (r in rssi) {
            w.write(listOf("rssi", t(r.ts), csv(labels[r.heaterId] ?: r.heaterId), "", "", "", "", "", "", r.rssi.toString(), r.source).joinToString(","))
            w.write("\n")
        }
    }
    return readings.size + rssi.size
}
