package com.mohit.dimplex.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Update banner. Checks GitHub once when first shown; [always] keeps it visible (Settings) with a
 * manual "Check for updates" button, otherwise it only appears when an update exists (Dashboard).
 */
@Composable
fun UpdateCard(always: Boolean = false) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val avail by Updater.available.collectAsState()
    val st by Updater.state.collectAsState()
    var checked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { Updater.check(ctx); checked = true }
    if (!always && avail == null && st.phase == "idle") return

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            val cur = Updater.versionCode(ctx)
            Text(
                when {
                    avail != null -> "Update available: build ${avail!!.build} (installed: $cur)"
                    checked -> "Up to date (build $cur)"
                    else -> "Checking for updates…"
                },
                style = MaterialTheme.typography.titleSmall,
            )
            when (st.phase) {
                "download" -> LinearProgressIndicator(progress = { st.progress.toFloat() }, Modifier.fillMaxWidth().padding(top = 8.dp))
                "install", "confirm" -> Text("Installing…", Modifier.padding(top = 8.dp))
                "error" -> Text("Update failed: ${st.message}", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            }
            Row(Modifier.padding(top = 8.dp)) {
                avail?.let { r ->
                    Button(onClick = {
                        if (!Updater.canInstall(ctx)) Updater.openInstallPermission(ctx) else Updater.install(ctx, r.apkUrl)
                    }) { Text(if (Updater.canInstall(ctx)) "Install update" else "Allow installs, then tap again") }
                    Spacer(Modifier.width(8.dp))
                }
                if (always) OutlinedButton(onClick = { checked = false; scope.launch { Updater.check(ctx); checked = true } }) {
                    Text("Check for updates")
                }
            }
        }
    }
}
