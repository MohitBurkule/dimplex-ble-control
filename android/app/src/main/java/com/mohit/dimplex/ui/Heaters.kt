package com.mohit.dimplex.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.mohit.dimplex.app
import com.mohit.dimplex.data.HeaterConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeatersScreen(nav: NavController) {
    val ctx = LocalContext.current
    val store = ctx.app.heaters
    val list by store.list.collectAsState()
    var editing by remember { mutableStateOf<HeaterConfig?>(null) }
    var adding by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<HeaterConfig?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val msg = runCatching {
            val text = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
            "Imported ${store.import(text)} heater(s)"
        }.getOrElse { "Import failed: ${it.message}" }
        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Heaters") },
            navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                IconButton(onClick = { picker.launch(arrayOf("application/json", "text/*", "*/*")) }) { Icon(Icons.Default.FileOpen, "Import JSON") }
                IconButton(onClick = { adding = true }) { Icon(Icons.Default.Add, "Add") }
            })
    }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("Import a file like {\"heaters\":[{\"label\":\"Kitchen\",\"address\":\"AA:BB:…\",\"pin\":\"123456\"}]}. Stored privately on this device.", style = MaterialTheme.typography.bodySmall) }
            items(list, key = { it.id }) { h ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(h.label, style = MaterialTheme.typography.titleMedium)
                            Text("${h.address} · PIN ••••${h.pin.takeLast(2)}", style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { editing = h }) { Icon(Icons.Default.Edit, "Edit") }
                        IconButton(onClick = { confirmDelete = h }) { Icon(Icons.Default.Delete, "Delete") }
                    }
                }
            }
        }
    }

    if (adding || editing != null) {
        HeaterDialog(editing, onDismiss = { adding = false; editing = null }) { new ->
            store.upsert(new, previousId = editing?.id)
            adding = false; editing = null
        }
    }
    confirmDelete?.let { h ->
        AlertDialog(onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${h.label}?") },
            confirmButton = { TextButton(onClick = { store.delete(h.id); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } })
    }
}

@Composable
private fun HeaterDialog(initial: HeaterConfig?, onDismiss: () -> Unit, onSave: (HeaterConfig) -> Unit) {
    var label by remember { mutableStateOf(initial?.label ?: "") }
    var address by remember { mutableStateOf(initial?.address ?: "") }
    var pin by remember { mutableStateOf(initial?.pin ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add heater" else "Edit heater") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(label, { label = it }, label = { Text("Label") }, singleLine = true)
                OutlinedTextField(address, { address = it }, label = { Text("BLE address") }, singleLine = true)
                OutlinedTextField(pin, { pin = it }, label = { Text("6-digit PIN") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                runCatching { HeaterStore_validate(HeaterConfig(label, address, pin, initial?.x, initial?.y)) }
                    .onSuccess(onSave).onFailure { error = it.message }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

private fun HeaterStore_validate(h: HeaterConfig) = com.mohit.dimplex.data.HeaterStore.validate(h)
