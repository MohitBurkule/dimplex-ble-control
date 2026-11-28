package com.mohit.dimplex.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.util.Log
import com.mohit.dimplex.data.AppDb
import com.mohit.dimplex.data.HeaterConfig
import com.mohit.dimplex.data.HeaterStore
import com.mohit.dimplex.data.Reading
import com.mohit.dimplex.data.RssiSample
import com.mohit.dimplex.data.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class LinkStatus(
    val busy: Boolean = false, val lastError: String? = null, val failures: Int = 0, val model: String? = null,
    /** Heater clock (epoch ms, local zone) and the phone time when it was read. */
    val clock: Long? = null, val clockReadAt: Long? = null,
    val preemptive: Boolean? = null, val openWindow: Boolean? = null,
)

/**
 * All heater I/O goes through here. A single global mutex ensures only one GATT connection at a time;
 * each operation connects, does its work, and disconnects so other clients (the Dimplex phone app) can connect.
 */
@SuppressLint("MissingPermission")
class HeaterController(private val ctx: Context, private val db: AppDb, private val store: HeaterStore, private val settings: Settings) {
    private val lock = Mutex()
    private val _status = MutableStateFlow<Map<String, LinkStatus>>(emptyMap())
    val status: StateFlow<Map<String, LinkStatus>> = _status
    private val adapter get() = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private fun setStatus(id: String, f: (LinkStatus) -> LinkStatus) =
        _status.update { it + (id to f(it[id] ?: LinkStatus())) }

    private suspend fun <T> withHeater(h: HeaterConfig, block: suspend (GattSession) -> T): T = lock.withLock {
        val a = adapter ?: throw GattException("no bluetooth adapter")
        if (!a.isEnabled) throw GattException("bluetooth off")
        val device = a.getRemoteDevice(h.address)
        setStatus(h.id) { it.copy(busy = true) }
        try {
            if (!Pairing.ensureBonded(ctx, device, h.label, h.pin)) throw GattException("pairing failed")
            var last: Throwable? = null
            // First connection attempt sometimes fails; retry with short backoff.
            for (attempt in 0 until 3) {
                if (attempt > 0) delay(1_000L * attempt * attempt + 500)
                val s = try { GattSession.open(ctx, device) } catch (e: CancellationException) { throw e } catch (e: Throwable) {
                    last = e; Log.w(TAG, "${h.label}: connect attempt ${attempt + 1} failed: ${e.message}"); continue
                }
                try {
                    val r = block(s)
                    setStatus(h.id) { it.copy(lastError = null, failures = 0) }
                    return@withLock r
                } finally {
                    s.close()
                }
            }
            throw last ?: GattException("connect failed")
        } catch (e: Throwable) {
            if (e !is CancellationException) setStatus(h.id) { it.copy(lastError = e.message ?: e.javaClass.simpleName, failures = it.failures + 1) }
            throw e
        } finally {
            setStatus(h.id) { it.copy(busy = false) }
        }
    }

    /** Read everything of interest from an open session and persist it. */
    private suspend fun readAll(h: HeaterConfig, s: GattSession): Reading {
        suspend fun <T> opt(f: suspend () -> T): T? = try { f() } catch (e: CancellationException) { throw e } catch (e: Exception) {
            Log.w(TAG, "${h.label}: ${e.message}"); null
        }
        if (_status.value[h.id]?.model == null) {
            val m = opt { Dimplex.model(s.read(Dimplex.MODEL)) }
            setStatus(h.id) { it.copy(model = m) }
        }
        val mode = opt { Dimplex.u8(s.read(Dimplex.HEATING_MODE), 0) }
        val sp = opt { Dimplex.le16(s.read(Dimplex.TARGET_TEMP)) }
        val room = opt { s.write(Dimplex.ROOM_TEMP, Dimplex.ROOM_TEMP_REQ); Dimplex.roomTemp(s.read(Dimplex.ROOM_TEMP)) }
        // Elements 1..2 reply [n, active, demand%]
        val elems = opt {
            (1..2).mapNotNull { n ->
                s.write(Dimplex.ELEMENT_STATE, Dimplex.elementReq(n))
                val r = s.read(Dimplex.ELEMENT_STATE)
                if (r.size >= 3 && r[0].toInt() == n) Pair(r[1].toInt() != 0, r[2].toInt() and 0xff) else null
            }
        }
        val heating = elems?.any { it.first }
        val rated = opt { Dimplex.le16(s.read(Dimplex.POWER_LOADING)) }
        val watts = if (rated != null && elems != null) rated * (elems.filter { it.first }.maxOfOrNull { it.second } ?: 0) / 100 else null
        val off = mode == Dimplex.MODE_TIMER && (opt {
            s.write(Dimplex.SCHEDULE, Dimplex.scheduleReadCmd(0)); kotlinx.coroutines.delay(400)
            Dimplex.isOffSchedule(s.read(Dimplex.SCHEDULE))
        } ?: false)
        val err = opt { Dimplex.u8(s.read(Dimplex.ERROR), 0) }
        val clock = opt { Dimplex.clock(s.read(Dimplex.CLOCK)) }
        val pre = opt { Dimplex.u8(s.read(Dimplex.PREEMPTIVE), 0)?.let { it != 0 } }
        val owd = opt { Dimplex.u8(s.read(Dimplex.OPEN_WINDOW), 0)?.let { it != 0 } }
        val readAt = System.currentTimeMillis()
        setStatus(h.id) { it.copy(clock = clock, clockReadAt = readAt, preemptive = pre, openWindow = owd) }
        if (settings.autoSyncClock && (clock == null || kotlin.math.abs(clock - readAt) > 2 * 60_000)) {
            opt { s.write(Dimplex.CLOCK, Dimplex.clockCmd()); Dimplex.clock(s.read(Dimplex.CLOCK)) }?.let { c ->
                setStatus(h.id) { it.copy(clock = c, clockReadAt = System.currentTimeMillis()) }
            }
        }
        opt { s.rssi() }?.let { db.rssi().insert(RssiSample(heaterId = h.id, ts = System.currentTimeMillis(), rssi = it, source = "conn")) }
        if (mode == null && sp == null && room == null) throw GattException("no data read (not paired?)")
        val r = Reading(heaterId = h.id, ts = System.currentTimeMillis(), mode = mode, setpoint = sp, roomTemp = room, heating = heating, error = err, watts = watts, off = off)
        db.readings().insert(r)
        broadcastState(h, r)
        return r
    }

    suspend fun poll(h: HeaterConfig): Reading = withHeater(h) { readAll(h, it) }

    /** Set target temperature (leaves Frost first by switching to Eco), then read back. */
    suspend fun setTemp(h: HeaterConfig, temp: Int): Reading = withHeater(h) { s ->
        val t = temp.coerceIn(Dimplex.MIN_TEMP, Dimplex.MAX_TEMP)
        val mode = runCatching { Dimplex.u8(s.read(Dimplex.HEATING_MODE), 0) }.getOrNull()
        if (mode == Dimplex.MODE_FROST || mode == Dimplex.MODE_TIMER) s.write(Dimplex.HEATING_MODE, Dimplex.modeCmd(Dimplex.MODE_ECO))
        s.write(Dimplex.TARGET_TEMP, Dimplex.tempCmd(t))
        readAll(h, s)
    }

    /**
     * Set mode. Manual/Eco are distinguished by setpoint on these heaters, so we also nudge the setpoint
     * into the right band if needed (Manual >= 20, Eco <= 19).
     */
    suspend fun setMode(h: HeaterConfig, mode: Int): Reading = withHeater(h) { s ->
        s.write(Dimplex.HEATING_MODE, Dimplex.modeCmd(mode))
        if (mode == Dimplex.MODE_MANUAL || mode == Dimplex.MODE_ECO) {
            val sp = runCatching { Dimplex.le16(s.read(Dimplex.TARGET_TEMP)) }.getOrNull()
            if (sp != null) {
                if (mode == Dimplex.MODE_MANUAL && sp < 20) s.write(Dimplex.TARGET_TEMP, Dimplex.tempCmd(20))
                if (mode == Dimplex.MODE_ECO && sp > 19) s.write(Dimplex.TARGET_TEMP, Dimplex.tempCmd(19))
            }
        }
        readAll(h, s)
    }

    suspend fun syncClock(h: HeaterConfig): Reading = withHeater(h) { s ->
        s.write(Dimplex.CLOCK, Dimplex.clockCmd())
        readAll(h, s)
    }

    suspend fun setPreemptive(h: HeaterConfig, on: Boolean): Reading = withHeater(h) { s ->
        s.write(Dimplex.PREEMPTIVE, byteArrayOf(if (on) 1 else 0))
        readAll(h, s)
    }

    /** Read-modify-write: only byte0 (enabled) of the 9-byte OpenWindowDetection value changes. */
    suspend fun setOpenWindow(h: HeaterConfig, on: Boolean): Reading = withHeater(h) { s ->
        val cur = s.read(Dimplex.OPEN_WINDOW)
        if (cur.isEmpty()) throw GattException("empty open-window value")
        val v = cur.clone(); v[0] = if (on) 1 else 0
        s.write(Dimplex.OPEN_WINDOW, v)
        readAll(h, s)
    }

    /** True off: write the off schedule to all 7 days, then switch to User Timer. Overwrites the timer schedule. */
    suspend fun off(h: HeaterConfig): Reading = withHeater(h) { s ->
        for (day in 0..6) { s.write(Dimplex.SCHEDULE, Dimplex.offScheduleCmd(day)); kotlinx.coroutines.delay(300) }
        s.write(Dimplex.HEATING_MODE, Dimplex.timerCmd())
        kotlinx.coroutines.delay(500)
        readAll(h, s)
    }

    /** Read the 7-day schedule (day 0..6), raw slots including dummies. */
    suspend fun readSchedule(h: HeaterConfig): List<List<Dimplex.Slot>> = withHeater(h) { s -> readDays(s) }

    private suspend fun readDays(s: GattSession): List<List<Dimplex.Slot>> = (0..6).map { day ->
        s.write(Dimplex.SCHEDULE, Dimplex.scheduleReadCmd(day)); delay(400)
        Dimplex.parseSchedule(s.read(Dimplex.SCHEDULE)) ?: throw GattException("short schedule read (day $day)")
    }

    /**
     * Write user slots (per day, real slots only, max 4) then switch to User Timer and verify read-back.
     * Returns null on success or a mismatch description.
     */
    suspend fun writeSchedule(h: HeaterConfig, days: List<List<Dimplex.Slot>>): String? = withHeater(h) { s ->
        for (day in 0..6) { s.write(Dimplex.SCHEDULE, Dimplex.scheduleWriteCmd(day, days[day])); delay(300) }
        s.write(Dimplex.HEATING_MODE, Dimplex.timerCmd())
        delay(500)
        val back = readDays(s)
        val bad = (0..6).filter { d -> back[d] != Dimplex.padSlots(days[d]) }
        val mode = Dimplex.u8(s.read(Dimplex.HEATING_MODE), 0)
        readAll(h, s)
        when {
            bad.isNotEmpty() -> "schedule not accepted for day(s) ${bad.joinToString { DAYS[it] }}"
            mode != Dimplex.MODE_TIMER -> "mode is ${Dimplex.modeName(mode)}, not Timer"
            else -> null
        }
    }

    /** Poll all heaters sequentially; failures are recorded in [status] and skipped. */
    suspend fun pollAll(skip: (HeaterConfig) -> Boolean = { false }) {
        for (h in store.list.value) {
            if (skip(h)) continue
            try { poll(h) } catch (e: CancellationException) { throw e } catch (e: Throwable) { Log.w(TAG, "${h.label} poll failed: ${e.message}") }
        }
    }

    private fun broadcastState(h: HeaterConfig, r: Reading) {
        ctx.sendBroadcast(Intent(ACTION_STATE).apply {
            putExtra("label", h.label)
            putExtra("address", h.address)
            putExtra("ts", r.ts)
            r.mode?.let { putExtra("mode", it); putExtra("mode_name", if (r.off) "Off" else Dimplex.modeName(it)) }
            putExtra("off", r.off)
            r.setpoint?.let { putExtra("setpoint", it) }
            r.roomTemp?.let { putExtra("room_temp", it) }
            r.heating?.let { putExtra("heating", it) }
            r.error?.let { putExtra("error", it) }
        })
    }

    companion object {
        const val TAG = "DimplexCtl"
        val DAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        const val ACTION_STATE = "com.mohit.dimplex.STATE"
    }
}
