package com.mohit.dimplex.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import com.mohit.dimplex.ble.Pairing
import com.mohit.dimplex.data.HeaterConfig
import kotlinx.coroutines.launch

private data class Found(val address: String, val name: String, val rssi: Int, val seen: Long)

/**
 * Add-heater flow: scan for anything advertising "<Dimplex>", show the ones not yet configured,
 * take one PIN, and try it on the selected heaters loudest-first until one bonds. A PIN belongs to
 * one heater, so we stop at the first success and ask for its room name.
 */
@SuppressLint("MissingPermission")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(nav: NavController) {
    val ctx = LocalContext.current
    val app = ctx.app
    val scope = rememberCoroutineScope()
    val known by app.heaters.list.collectAsState()
    val found = remember { mutableStateMapOf<String, Found>() }
    val selected = remember { mutableStateMapOf<String, Boolean>() }
    var pin by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var naming by remember { mutableStateOf<Found?>(null) }
    var pairedPin by remember { mutableStateOf("") }

    val adapter = remember { ctx.getSystemService(BluetoothManager::class.java)?.adapter }
    DisposableEffect(Unit) {
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                val name = r.scanRecord?.deviceName ?: runCatching { r.device.name }.getOrNull() ?: return
                if (!name.contains("Dimplex", ignoreCase = true)) return
                val a = r.device.address
                found[a] = Found(a, name.substringBefore("<").ifBlank { name }, r.rssi, System.currentTimeMillis())
                if (a !in selected) selected[a] = true
            }
        }
        val scanner = adapter?.bluetoothLeScanner
        runCatching {
            scanner?.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        }
        onDispose { runCatching { scanner?.stopScan(cb) } }
    }

    val knownIds = known.map { it.id }.toSet()
    val candidates = found.values.filter { it.address.uppercase() !in knownIds }.sortedByDescending { it.rssi }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Find heaters") },
            navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Scanning for Dimplex heaters… Close the Dimplex app (a heater accepts one connection). " +
                "Put the heater in pairing mode so it shows its 6-digit PIN, enter it below, and tap Pair. " +
                "The PIN is tried on the ticked heaters, strongest signal first.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(pin, { if (it.length <= 6 && it.all(Char::isDigit)) pin = it }, label = { Text("PIN shown on the heater") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), singleLine = true,
                modifier = Modifier.fillMaxWidth(), enabled = busy == null)
            Button(
                enabled = busy == null && pin.length == 6 && candidates.any { selected[it.address] == true },
                onClick = {
                    val order = candidates.filter { selected[it.address] == true }
                    scope.launch {
                        message = null
                        for (f in order) {
                            busy = "Trying ${f.name} (${f.rssi} dBm)…"
                            val dev: BluetoothDevice = adapter!!.getRemoteDevice(f.address)
                            if (Pairing.ensureBonded(ctx, dev, f.name, pin, attempts = 2)) {
                                pairedPin = pin; naming = f; busy = null; pin = ""; return@launch
                            }
                        }
                        busy = null
                        message = "No heater accepted that PIN. Check it's in pairing mode and close enough, then retry."
                    }
                }) { Text("Pair") }
            busy?.let { Text(it); LinearProgressIndicator(Modifier.fillMaxWidth()) }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (candidates.isEmpty()) Text("No new heaters found yet. Already configured: ${known.size}.")
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(candidates, key = { it.address }) { f ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(selected[f.address] == true, { selected[f.address] = it }, enabled = busy == null)
                            Column(Modifier.weight(1f)) {
                                Text(f.name, style = MaterialTheme.typography.titleMedium)
                                Text("${f.address}  ·  ${f.rssi} dBm", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }

    naming?.let { f ->
        var label by remember(f) { mutableStateOf("") }
        AlertDialog(onDismissRequest = {},
            title = { Text("Paired ${f.name}") },
            text = {
                OutlinedTextField(label, { label = it }, label = { Text("Room name, e.g. Bedroom") }, singleLine = true)
            },
            confirmButton = {
                TextButton(enabled = label.isNotBlank(), onClick = {
                    app.heaters.upsert(HeaterConfig(label = label.trim(), address = f.address.uppercase(), pin = pairedPin))
                    naming = null; found.remove(f.address)
                    app.controller.let { c -> scope.launch { runCatching { c.pollAll() } } }
                }) { Text("Save") }
            })
    }
}
