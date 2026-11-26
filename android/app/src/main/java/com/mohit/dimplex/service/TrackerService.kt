package com.mohit.dimplex.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.mohit.dimplex.app
import com.mohit.dimplex.ble.RssiScanner
import com.mohit.dimplex.ui.MainActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.min

/** Foreground service: periodic polling of all heaters + continuous RSSI scanning. */
class TrackerService : LifecycleService() {
    private lateinit var scanner: RssiScanner
    private var pollJob: Job? = null
    /** heaterId -> earliest time we try it again after failures (exponential backoff). */
    private val nextTry = HashMap<String, Long>()

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
        ServiceCompat.startForeground(
            this, NOTIF_ID, notification("Starting…"),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
        )
        scanner = RssiScanner(this, app.db, lifecycleScope)
        lifecycleScope.launch {
            app.heaters.list.collectLatest { list ->
                if (app.settings.scanEnabled) scanner.start(list.map { it.address }.toSet()) else scanner.stop()
            }
        }
        lifecycleScope.launch {
            while (true) {
                val week = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
                app.db.readings().prune(week); app.db.rssi().prune(week)
                delay(6 * 3600 * 1000L)
            }
        }
        startPolling()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_REFRESH) { nextTry.clear(); startPolling() }
        return START_STICKY
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                app.controller.pollAll(skip = { (nextTry[it.id] ?: 0) > now })
                val st = app.controller.status.value
                for (h in app.heaters.list.value) {
                    val f = st[h.id]?.failures ?: 0
                    if (f == 0) nextTry.remove(h.id)
                    else nextTry[h.id] = System.currentTimeMillis() + min(30 * 60_000L, 30_000L shl min(f - 1, 6))
                }
                val errs = st.values.count { it.lastError != null }
                update("${app.heaters.list.value.size} heaters · last poll ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(System.currentTimeMillis())}" +
                    if (errs > 0) " · $errs failing" else "")
                delay(app.settings.pollSeconds * 1000L)
            }
        }
    }

    private fun notification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Dimplex heater tracking")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()

    private fun update(text: String) =
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text))

    override fun onDestroy() {
        scanner.stop()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "tracker"
        const val NOTIF_ID = 1
        const val ACTION_REFRESH = "com.mohit.dimplex.service.REFRESH"

        fun createChannel(ctx: Context) {
            ctx.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, "Heater tracking", NotificationManager.IMPORTANCE_LOW))
        }

        fun start(ctx: Context, refresh: Boolean = false) {
            val i = Intent(ctx, TrackerService::class.java)
            if (refresh) i.action = ACTION_REFRESH
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, TrackerService::class.java))
    }
}
