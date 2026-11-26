package com.mohit.dimplex.data

import android.content.Context

class Settings(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var pollSeconds: Int
        get() = p.getInt("poll_s", 60)
        set(v) = p.edit().putInt("poll_s", v.coerceIn(15, 3600)).apply()
    var startOnBoot: Boolean
        get() = p.getBoolean("boot", false)
        set(v) = p.edit().putBoolean("boot", v).apply()
    var scanEnabled: Boolean
        get() = p.getBoolean("scan", true)
        set(v) = p.edit().putBoolean("scan", v).apply()
    /** Shared secret Tasker must send as extra "secret". Empty = receiver disabled. */
    var taskerSecret: String
        get() = p.getString("tasker_secret", "") ?: ""
        set(v) = p.edit().putString("tasker_secret", v).apply()
    /** Window for the dashboard average room temperature. */
    var avgHours: Int
        get() = p.getInt("avg_h", 24)
        set(v) = p.edit().putInt("avg_h", v.coerceIn(1, 24 * 30)).apply()
    /** Sync heater RTC after a poll when drift exceeds 2 minutes. */
    var autoSyncClock: Boolean
        get() = p.getBoolean("clock_sync", false)
        set(v) = p.edit().putBoolean("clock_sync", v).apply()
    var serviceEnabled: Boolean
        get() = p.getBoolean("service", false)
        set(v) = p.edit().putBoolean("service", v).apply()
}
