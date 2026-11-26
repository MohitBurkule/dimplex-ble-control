package com.mohit.dimplex.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.UUID

class GattException(msg: String) : IOException(msg)

/**
 * Minimal coroutine wrapper around BluetoothGatt: one outstanding operation at a time.
 * Characteristics are looked up by UUID across all (non-OTA) services.
 */
@SuppressLint("MissingPermission")
class GattSession private constructor(private val device: BluetoothDevice) : BluetoothGattCallback() {
    private var gatt: BluetoothGatt? = null
    private val opLock = Mutex()
    private var connected = CompletableDeferred<Boolean>()
    private var servicesDone = CompletableDeferred<Int>()
    private var pending: CompletableDeferred<Pair<Int, Any?>>? = null
    @Volatile var disconnected = false
        private set

    companion object {
        const val OP_TIMEOUT = 8_000L

        suspend fun open(ctx: Context, device: BluetoothDevice, timeoutMs: Long = 15_000): GattSession {
            val s = GattSession(device)
            s.gatt = device.connectGatt(ctx, false, s, BluetoothDevice.TRANSPORT_LE)
                ?: throw GattException("connectGatt returned null")
            try {
                withTimeout(timeoutMs) {
                    if (!s.connected.await()) throw GattException("connect failed")
                    s.gatt!!.discoverServices()
                    val st = s.servicesDone.await()
                    if (st != BluetoothGatt.GATT_SUCCESS) throw GattException("discovery status $st")
                }
            } catch (e: Throwable) {
                s.close(); throw e
            }
            return s
        }
    }

    override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
        if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
            connected.complete(true)
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            disconnected = true
            connected.complete(false)
            servicesDone.complete(-1)
            pending?.complete(-1 to null)
        }
    }

    override fun onServicesDiscovered(g: BluetoothGatt, status: Int) { servicesDone.complete(status) }

    @Deprecated("pre-33 callback")
    override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
        @Suppress("DEPRECATION")
        pending?.complete(status to c.value?.clone())
    }

    override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
        pending?.complete(status to value.clone())
    }

    override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
        pending?.complete(status to null)
    }

    override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
        pending?.complete(status to rssi)
    }

    private fun find(uuid: UUID): BluetoothGattCharacteristic? =
        gatt?.services?.asSequence()
            ?.filterNot { Dimplex.isOtaService(it.uuid) }
            ?.mapNotNull { it.getCharacteristic(uuid) }
            ?.firstOrNull()

    fun has(uuid: UUID) = find(uuid) != null

    private suspend fun op(start: (BluetoothGatt) -> Boolean): Pair<Int, Any?> = opLock.withLock {
        val g = gatt ?: throw GattException("closed")
        if (disconnected) throw GattException("disconnected")
        val d = CompletableDeferred<Pair<Int, Any?>>()
        pending = d
        try {
            if (!start(g)) throw GattException("operation not started")
            val r = withTimeout(OP_TIMEOUT) { d.await() }
            if (r.first != BluetoothGatt.GATT_SUCCESS) throw GattException("gatt status ${r.first}")
            r
        } finally {
            pending = null
        }
    }

    suspend fun read(uuid: UUID): ByteArray {
        val c = find(uuid) ?: throw GattException("char $uuid not found")
        return op { it.readCharacteristic(c) }.second as? ByteArray ?: ByteArray(0)
    }

    suspend fun write(uuid: UUID, value: ByteArray) {
        check(!Dimplex.isForbiddenWrite(uuid)) { "refusing write to protected characteristic $uuid" }
        val c = find(uuid) ?: throw GattException("char $uuid not found")
        val type = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT // with response
        op { g ->
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, value, type) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run { c.writeType = type; c.value = value; g.writeCharacteristic(c) }
            }
        }
    }

    suspend fun rssi(): Int = op { it.readRemoteRssi() }.second as Int

    fun close() {
        gatt?.let { runCatching { it.disconnect() }; runCatching { it.close() } }
        gatt = null
        disconnected = true
    }
}
