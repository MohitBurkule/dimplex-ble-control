package com.mohit.dimplex.tasker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.mohit.dimplex.app
import com.mohit.dimplex.ble.Dimplex
import com.mohit.dimplex.data.HeaterConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.security.MessageDigest

/**
 * Tasker / automation API. See docs/tasker.md.
 * Actions: SET_TEMP(label,temp), SET_MODE(label,mode), OFF(label), REFRESH([label]). All require extra "secret".
 * label "all" (or omitted for REFRESH) targets every heater.
 */
class TaskerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.app
        val secret = app.settings.taskerSecret
        val given = intent.getStringExtra("secret") ?: ""
        if (secret.isEmpty() || !MessageDigest.isEqual(secret.toByteArray(), given.toByteArray())) {
            Log.w(TAG, "rejected ${intent.action}: bad or unset secret"); return
        }
        val label = intent.getStringExtra("label")
        val targets: List<HeaterConfig> =
            if (label == null || label.equals("all", true)) app.heaters.list.value
            else listOfNotNull(app.heaters.byLabel(label))
        if (targets.isEmpty()) { Log.w(TAG, "no heater matches '$label'"); return }

        val action = intent.action?.substringAfterLast('.') ?: return
        val temp = intent.getStringExtra("temp")?.toIntOrNull() ?: intent.getIntExtra("temp", -1)
        val mode = intent.getStringExtra("mode")?.let { Dimplex.modeFromName(it) } ?: intent.getIntExtra("mode", -1).takeIf { it in 1..4 }

        val pending = goAsync()
        scope.launch {
            try {
                for (h in targets) runCatching {
                    when (action) {
                        "SET_TEMP" -> if (temp in Dimplex.MIN_TEMP..Dimplex.MAX_TEMP) app.controller.setTemp(h, temp) else Log.w(TAG, "bad temp $temp")
                        "SET_MODE" -> if (mode != null) app.controller.setMode(h, mode) else Log.w(TAG, "bad mode")
                        "OFF" -> app.controller.off(h)
                        "REFRESH" -> app.controller.poll(h)
                        else -> Unit
                    }
                }.onFailure { Log.w(TAG, "$action ${h.label} failed: ${it.message}") }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val TAG = "DimplexTasker"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
