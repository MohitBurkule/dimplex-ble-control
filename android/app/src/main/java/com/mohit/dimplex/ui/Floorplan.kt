package com.mohit.dimplex.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

/** Placeholder. Planned: import a floorplan image, place heaters at (x, y), show estimated position. See docs/roadmap.md. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FloorplanScreen(nav: NavController) {
    Scaffold(topBar = {
        TopAppBar(title = { Text("Floorplan") },
            navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        Text("Coming soon: import a floorplan image, place each heater on it, and see your estimated position from RSSI.",
            Modifier.fillMaxSize().padding(pad).padding(16.dp))
    }
}
