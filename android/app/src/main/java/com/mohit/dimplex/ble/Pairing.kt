package com.mohit.dimplex.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Automatic bonding using the heater's fixed PIN (LE Secure Connections, passkey entry).
 * A high-priority receiver answers ACTION_PAIRING_REQUEST with setPin() so the user never types it.
 * If the OS shows its own dialog anyway, [pinPrompt] exposes the PIN so the UI can display it.
 */
@SuppressLint("MissingPermission")
object Pairing {
    private const val TAG = "DimplexPairing"
    /** label/PIN the UI should show while a system pairing dialog may be visible; null = none. */
    val pinPrompt = MutableStateFlow<Pair<String, String>?>(null)

    // Hidden constants (BluetoothDevice.PAIRING_VARIANT_*)
    private const val VARIANT_PIN = 0
    private const val VARIANT_PASSKEY = 1
    private const val VARIANT_PASSKEY_CONFIRMATION = 2
    private const val VARIANT_CONSENT = 3

    fun isBonded(d: BluetoothDevice) = d.bondState == BluetoothDevice.BOND_BONDED

    /**
     * Bond over LE explicitly. A device made from a bare address has unknown type, and plain
     * createBond() may then try Classic Bluetooth, which these LE-only heaters never answer.
     * createBond(int transport) is hidden API but present on all Android versions we target.
     */
    private fun createBondLe(d: BluetoothDevice): Boolean = runCatching {
        d.javaClass.getMethod("createBond", Int::class.javaPrimitiveType)
            .invoke(d, BluetoothDevice.TRANSPORT_LE) as Boolean
    }.getOrElse {
        Log.w(TAG, "createBond(TRANSPORT_LE) unavailable, falling back", it)
        d.createBond()
    }

    suspend fun ensureBonded(ctx: Context, device: BluetoothDevice, label: String, pin: String): Boolean {
        if (isBonded(device)) return true
        var done = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val d: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33)
                    i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                else @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                if (d == null || d.address != device.address) return
                when (i.action) {
                    BluetoothDevice.ACTION_PAIRING_REQUEST -> {
                        val variant = i.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1)
                        Log.i(TAG, "pairing request variant=$variant for $label")
                        pinPrompt.value = label to pin
                        val handled = runCatching {
                            when (variant) {
                                VARIANT_PIN, VARIANT_PASSKEY -> d.setPin(pin.toByteArray(Charsets.US_ASCII))
                                VARIANT_PASSKEY_CONFIRMATION, VARIANT_CONSENT -> d.setPairingConfirmation(true) // needs BLUETOOTH_PRIVILEGED; may throw
                                else -> d.setPin(pin.toByteArray(Charsets.US_ASCII))
                            }
                        }.onFailure { Log.w(TAG, "auto-answer failed, user must enter PIN", it) }.getOrDefault(false)
                        if (handled && isOrderedBroadcast) runCatching { abortBroadcast() }
                    }
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        when (i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)) {
                            BluetoothDevice.BOND_BONDED -> done.complete(true)
                            BluetoothDevice.BOND_NONE -> if (i.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1) == BluetoothDevice.BOND_BONDING) done.complete(false)
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            priority = IntentFilter.SYSTEM_HIGH_PRIORITY - 1
        }
        ContextCompat.registerReceiver(ctx, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        return try {
            // Heaters often refuse the first pairing attempt (seen on BlueZ too); retry.
            for (attempt in 1..4) {
                if (isBonded(device)) return true
                val started = createBondLe(device)
                Log.i(TAG, "$label: createBond attempt $attempt started=$started")
                val ok = if (started) withTimeoutOrNull(45_000) { done.await() } ?: isBonded(device) else false
                if (ok) return true
                done = CompletableDeferred()
                kotlinx.coroutines.delay(2000L * attempt)
            }
            isBonded(device)
        } finally {
            runCatching { ctx.unregisterReceiver(receiver) }
            pinPrompt.value = null
        }
    }
}
