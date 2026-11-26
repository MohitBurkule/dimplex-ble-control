package com.mohit.dimplex.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import com.mohit.dimplex.data.AppDb
import com.mohit.dimplex.data.RssiSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Continuous BLE scan filtered to known heater addresses; stores advertisement RSSI.
 * Samples are rate-limited per heater to one every [minIntervalMs]. Note heaters stop advertising
 * while connected, so there are gaps during polls.
 */
@SuppressLint("MissingPermission")
class RssiScanner(private val ctx: Context, private val db: AppDb, private val scope: CoroutineScope, private val minIntervalMs: Long = 5_000) {
    private val last = HashMap<String, Long>()
    private var running = false
    private var addresses: Set<String> = emptySet()

    private val cb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = record(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::record)
        override fun onScanFailed(errorCode: Int) { Log.w("DimplexScan", "scan failed $errorCode"); running = false }
    }

    private fun record(r: ScanResult) {
        val id = r.device.address.uppercase()
        val now = System.currentTimeMillis()
        synchronized(last) {
            if (now - (last[id] ?: 0) < minIntervalMs) return
            last[id] = now
        }
        scope.launch { db.rssi().insert(RssiSample(heaterId = id, ts = now, rssi = r.rssi, source = "adv")) }
    }

    fun start(addrs: Set<String>) {
        if (running && addrs == addresses) return
        stop()
        addresses = addrs
        if (addrs.isEmpty()) return
        val scanner = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.bluetoothLeScanner ?: return
        // Filters are required for scanning to continue with the screen off.
        val filters = addrs.map { ScanFilter.Builder().setDeviceAddress(it).build() }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()
        runCatching { scanner.startScan(filters, settings, cb); running = true }
            .onFailure { Log.w("DimplexScan", "startScan failed", it) }
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.bluetoothLeScanner?.stopScan(cb) }
    }
}
