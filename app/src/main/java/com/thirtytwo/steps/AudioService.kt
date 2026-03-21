package com.thirtytwo.steps

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

class AudioService : Service() {

    private lateinit var volumeController: VolumeController
    private var overlay: VolumeOverlay? = null

    private val stepListener: (Int, Int) -> Unit = { step, total ->
        // Only show overlay when the app is NOT in the foreground
        if (!appInForeground) {
            overlay?.show(step, total)
        }
        updateNotification(step, total)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        volumeController = VolumeController.getInstance(this)
        volumeController.attachSession(0)
        volumeController.syncFromSystem()
        volumeController.startObserving()

        overlay = VolumeOverlay(this)
        volumeController.addStepListener(stepListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ATTACH_SESSION -> {
                val sessionId = intent.getIntExtra(EXTRA_SESSION_ID, -1)
                if (sessionId > 0) {
                    volumeController.attachSession(sessionId)
                }
            }
            ACTION_DETACH_SESSION -> {
                val sessionId = intent.getIntExtra(EXTRA_SESSION_ID, -1)
                if (sessionId > 0) {
                    volumeController.detachSession(sessionId)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        volumeController.removeStepListener(stepListener)
        overlay?.hide()
        volumeController.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Volume Control",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps volume control active"
            setShowBadge(false)
            setSound(null, null)
        }
        val nm = if (Build.VERSION.SDK_INT >= 28) getSystemService(NotificationManager::class.java)
            else getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(step: Int? = null, total: Int? = null): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val text = if (step != null && total != null) {
            "Volume: $step / $total"
        } else {
            "Volume control active"
        }

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("32steps")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(step: Int, total: Int) {
        val nm = if (Build.VERSION.SDK_INT >= 28) getSystemService(NotificationManager::class.java)
            else getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(step, total))
    }

    companion object {
        const val CHANNEL_ID = "volume_control"
        const val NOTIFICATION_ID = 1
        const val ACTION_ATTACH_SESSION = "com.thirtytwo.steps.ATTACH_SESSION"
        const val ACTION_DETACH_SESSION = "com.thirtytwo.steps.DETACH_SESSION"
        const val EXTRA_SESSION_ID = "session_id"

        // Tracks whether MainActivity is visible
        @Volatile
        var appInForeground = false
    }
}
