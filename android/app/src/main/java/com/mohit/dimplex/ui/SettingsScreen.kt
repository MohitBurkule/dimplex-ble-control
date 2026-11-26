package com.mohit.dimplex.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.mohit.dimplex.app
import com.mohit.dimplex.service.TrackerService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(nav: NavController) {
    val ctx = LocalContext.current
    val s = ctx.app.settings
    var service by remember { mutableStateOf(s.serviceEnabled) }
    var boot by remember { mutableStateOf(s.startOnBoot) }
    var scan by remember { mutableStateOf(s.scanEnabled) }
    var clockSync by remember { mutableStateOf(s.autoSyncClock) }
    var poll by remember { mutableStateOf(s.pollSeconds.toString()) }
    var avg by remember { mutableStateOf(s.avgHours.toString()) }
    var secret by remember { mutableStateOf(s.taskerSecret) }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Settings") },
            navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            com.mohit.dimplex.update.UpdateCard(always = true)
            Toggle("Background tracking service", service) {
                service = it; s.serviceEnabled = it
                if (it) TrackerService.start(ctx) else TrackerService.stop(ctx)
            }
            Toggle("Start on boot", boot) { boot = it; s.startOnBoot = it }
            Toggle("Continuous RSSI scanning (presence)", scan) { scan = it; s.scanEnabled = it; if (service) { TrackerService.stop(ctx); TrackerService.start(ctx) } }
            Toggle("Auto-sync heater clocks (drift > 2 min)", clockSync) { clockSync = it; s.autoSyncClock = it }
            OutlinedTextField(poll, { poll = it; it.toIntOrNull()?.let { v -> s.pollSeconds = v } }, label = { Text("Poll interval (seconds, 15–3600)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(avg, { avg = it; it.toIntOrNull()?.let { v -> s.avgHours = v } }, label = { Text("Average temperature window (hours)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(secret, { secret = it; s.taskerSecret = it }, label = { Text("Tasker shared secret (empty = disabled)") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Tasker intents: see docs/tasker.md. Poll interval changes apply after the next cycle.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}
