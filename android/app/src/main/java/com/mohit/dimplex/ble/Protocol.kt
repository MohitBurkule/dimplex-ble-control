package com.mohit.dimplex.ble

import java.util.UUID

/** Glen Dimplex GATT protocol. See docs/ble-protocol notes in the task / philcluff/dimplex-qrad-smart-bridge. */
object Dimplex {
    fun uuid(short: Int): UUID = UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", short))

    val HEATING_MODE = uuid(0x1001)
    val TEMP_RANGE = uuid(0x1006)
    val ADVANCE = uuid(0x100d)
    val ERROR = uuid(0x1011)
    val TARGET_TEMP = uuid(0x1023)
    val BOOST_COUNTDOWN = uuid(0x1027)
    val BOOST_SETPOINT = uuid(0x1028)
    val AWAY = uuid(0x102a)
    val ROOM_TEMP = uuid(0x0008)
    val ELEMENT_STATE = uuid(0x0002)
    val CLOCK = uuid(0x0006)
    val MODEL = uuid(0x2005)
    val OPEN_WINDOW = uuid(0x1007)
    val PREEMPTIVE = uuid(0x1008)

    /** Writes to these are refused in code (OTA / factory characteristics). */
    private val FORBIDDEN_SHORT = setOf(0x2006, 0x2007, 0x2008, 0x200f)
    fun isForbiddenWrite(uuid: UUID): Boolean {
        val s = uuid.toString().lowercase()
        if (s.startsWith("00060000-f8ce")) return true
        if (!s.endsWith("-0000-1000-8000-00805f9b34fb")) return false
        val short = s.substring(4, 8).toInt(16)
        return short in FORBIDDEN_SHORT
    }
    fun isOtaService(uuid: UUID) = uuid.toString().lowercase().startsWith("00060000-f8ce")

    const val MODE_TIMER = 1
    const val MODE_MANUAL = 2
    const val MODE_ECO = 3
    const val MODE_FROST = 4
    const val MIN_TEMP = 7
    const val MAX_TEMP = 30

    fun modeName(m: Int?): String = when (m) {
        MODE_TIMER -> "Timer"
        MODE_MANUAL -> "Manual"
        MODE_ECO -> "Eco"
        MODE_FROST -> "Frost"
        null -> "?"
        else -> "Mode $m"
    }

    fun modeFromName(s: String): Int? = when (s.trim().lowercase()) {
        "timer", "1" -> MODE_TIMER
        "manual", "2" -> MODE_MANUAL
        "eco", "3" -> MODE_ECO
        "frost", "4" -> MODE_FROST
        else -> null
    }

    val SCHEDULE = uuid(0x1002)
    val POWER_LOADING = uuid(0x000a)

    /** Timer sub-mode 3 = User Timer. */
    fun timerCmd() = byteArrayOf(MODE_TIMER.toByte(), 3, 0, 0, 0, 0)

    /**
     * True "off": User Timer with four 1-minute 7 °C slots at 03:00-03:04 (the heater rejects empty
     * slots). Outside slots a timer heater's target is 0 °C and the elements stay off.
     * Write format: [1, sub=3, day, 4 x [startH,startM,endH,endM,temp,0], 0,0,0,0].
     */
    fun offScheduleCmd(day: Int): ByteArray {
        val slots = (0..3).flatMap { i -> listOf(3, i, 3, i + 1, 7, 0) }
        return (listOf(1, 3, day) + slots + listOf(0, 0, 0, 0)).map { it.toByte() }.toByteArray()
    }

    /** One timer slot, minutes since midnight. */
    data class Slot(val start: Int, val end: Int, val temp: Int) {
        val isDummy get() = end - start == 1 && temp == MIN_TEMP
    }

    /** Parse a 31-byte schedule read-back into 4 slots (null if too short). */
    fun parseSchedule(b: ByteArray?): List<Slot>? {
        if (b == null || b.size < 27) return null
        val v = b.map { it.toInt() and 0xff }
        return (0..3).map { i -> val o = 3 + i * 6; Slot(v[o] * 60 + v[o + 1], v[o + 2] * 60 + v[o + 3], v[o + 4]) }
    }

    /**
     * Pad user slots to 4 with 1-minute 7 °C dummies at free minutes (the heater rejects empty slots),
     * sorted chronologically.
     */
    fun padSlots(user: List<Slot>): List<Slot> {
        require(user.size <= 4)
        val out = user.toMutableList()
        fun free(m: Int) = out.none { m < it.end && m + 1 > it.start }
        var m = 180
        var guard = 0
        while (out.size < 4 && guard++ < 1440) {
            if (free(m)) out += Slot(m, m + 1, MIN_TEMP)
            m = (m + 1) % 1439
        }
        return out.sortedBy { it.start }
    }

    fun scheduleWriteCmd(day: Int, slots: List<Slot>): ByteArray {
        val bytes = padSlots(slots).flatMap { listOf(it.start / 60, it.start % 60, it.end / 60, it.end % 60, it.temp, 0) }
        return (listOf(1, 3, day) + bytes + listOf(0, 0, 0, 0)).map { it.toByte() }.toByteArray()
    }

    fun scheduleReadCmd(day: Int) = byteArrayOf(0, 3, day.toByte())

    /** True if a schedule read-back (31 bytes) is our off schedule. */
    fun isOffSchedule(b: ByteArray?): Boolean {
        if (b == null || b.size < 27) return false
        val want = offScheduleCmd(b[2].toInt())
        return (3 until 27).all { b[it] == want[it] }
    }

    fun modeCmd(mode: Int) = byteArrayOf(mode.toByte(), 0, 0, 0, 0, 0)
    fun tempCmd(t: Int) = byteArrayOf(t.toByte(), 0)
    val ROOM_TEMP_REQ = byteArrayOf(1, 0, 0)
    // Elements are numbered from 1 (PLX has two). Reply: [n, active, demand%]
    fun elementReq(n: Int) = byteArrayOf(n.toByte(), 0, 0)

    /** 0x0006 [20, YY, MM, DD, hh, mm, dst] -> epoch ms in local zone, or null. */
    fun clock(b: ByteArray?): Long? {
        if (b == null || b.size < 6) return null
        val v = b.map { it.toInt() and 0xff }
        return runCatching {
            java.time.LocalDateTime.of(v[0] * 100 + v[1], v[2], v[3], v[4], v[5])
                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()
    }
    fun clockCmd(now: java.time.ZonedDateTime = java.time.ZonedDateTime.now()): ByteArray {
        val dst = if (java.util.TimeZone.getDefault().inDaylightTime(java.util.Date.from(now.toInstant()))) 1 else 0
        return byteArrayOf(20, (now.year % 100).toByte(), now.monthValue.toByte(), now.dayOfMonth.toByte(),
            now.hour.toByte(), now.minute.toByte(), dst.toByte())
    }

    fun u8(b: ByteArray?, i: Int): Int? = b?.getOrNull(i)?.toInt()?.and(0xff)
    fun le16(b: ByteArray?): Int? = if (b == null || b.size < 2) null else (b[0].toInt() and 0xff) or ((b[1].toInt() and 0xff) shl 8)
    fun roomTemp(b: ByteArray?): Double? {
        if (b == null || b.size < 3) return null
        return (b[1].toInt() and 0xff) + (b[2].toInt() and 0xff) / 10.0
    }
    fun model(b: ByteArray?): String? = b?.takeWhile { it != 0.toByte() }?.toByteArray()?.toString(Charsets.US_ASCII)
}
