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
        MODE_FROST -> "Off (Frost)"
        null -> "?"
        else -> "Mode $m"
    }

    fun modeFromName(s: String): Int? = when (s.trim().lowercase()) {
        "timer", "1" -> MODE_TIMER
        "manual", "2" -> MODE_MANUAL
        "eco", "3" -> MODE_ECO
        "frost", "off", "4" -> MODE_FROST
        else -> null
    }

    fun modeCmd(mode: Int) = byteArrayOf(mode.toByte(), 0, 0, 0, 0, 0)
    fun tempCmd(t: Int) = byteArrayOf(t.toByte(), 0)
    val ROOM_TEMP_REQ = byteArrayOf(1, 0, 0)
    val ELEMENT_REQ = byteArrayOf(0, 0, 0)

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
