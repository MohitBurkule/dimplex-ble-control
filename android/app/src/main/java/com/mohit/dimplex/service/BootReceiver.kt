package com.mohit.dimplex.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mohit.dimplex.app

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val s = context.app.settings
        if (s.startOnBoot && s.serviceEnabled) runCatching { TrackerService.start(context) }
    }
}
