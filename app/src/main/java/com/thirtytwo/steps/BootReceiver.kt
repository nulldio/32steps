package com.thirtytwo.steps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = PrefsManager(context)
            if (prefs.enabled) {
                val serviceIntent = Intent(context, AudioService::class.java)
                context.startForegroundService(serviceIntent)
            }
        }
    }
}
