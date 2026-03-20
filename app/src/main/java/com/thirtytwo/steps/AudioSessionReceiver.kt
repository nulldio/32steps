package com.thirtytwo.steps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect

/**
 * Listens for media apps opening/closing audio sessions.
 * Attaches our equalizer to each session for system-wide volume control
 * as a fallback for devices where session 0 (global mix) doesn't work.
 */
class AudioSessionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
        if (sessionId <= 0) return

        val serviceIntent = Intent(context, AudioService::class.java)

        when (intent.action) {
            AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> {
                serviceIntent.action = AudioService.ACTION_ATTACH_SESSION
                serviceIntent.putExtra(AudioService.EXTRA_SESSION_ID, sessionId)
                context.startForegroundService(serviceIntent)
            }
            AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> {
                serviceIntent.action = AudioService.ACTION_DETACH_SESSION
                serviceIntent.putExtra(AudioService.EXTRA_SESSION_ID, sessionId)
                try {
                    context.startService(serviceIntent)
                } catch (_: Exception) {}
            }
        }
    }
}
