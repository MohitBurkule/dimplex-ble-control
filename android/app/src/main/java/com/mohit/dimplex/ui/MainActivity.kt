package com.mohit.dimplex.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mohit.dimplex.app
import com.mohit.dimplex.service.TrackerService

class MainActivity : ComponentActivity() {
    private val enableBt = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (app.settings.serviceEnabled) TrackerService.start(this)
    }
    private var askedBt = false

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (!ensureBluetoothOn() && app.settings.serviceEnabled) TrackerService.start(this)
    }

    /** Apps can't switch Bluetooth on silently (Android 13+); this shows the system "Turn on?" prompt. */
    private fun ensureBluetoothOn(): Boolean {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return false
        if (adapter.isEnabled || askedBt) return false
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return false
        askedBt = true
        enableBt.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        return true
    }

    // Ask once each time the app comes to the foreground; declining isn't re-asked until next time.
    override fun onStart() {
        super.onStart()
        askedBt = false
        ensureBluetoothOn()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        permLauncher.launch(requiredPermissions())
        setContent { AppTheme { AppNav() } }
    }

    companion object {
        fun requiredPermissions(): Array<String> = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 31) { add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_CONNECT) }
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun AppNav() {
    val nav = rememberNavController()
    NavHost(nav, startDestination = "dashboard") {
        composable("dashboard") { DashboardScreen(nav) }
        composable("detail/{id}") { DetailScreen(nav, it.arguments?.getString("id") ?: "") }
        composable("heaters") { HeatersScreen(nav) }
        composable("settings") { SettingsScreen(nav) }
        composable("floorplan") { FloorplanScreen(nav) }
        composable("timeline") { TimelineScreen(nav) }
    }
}
