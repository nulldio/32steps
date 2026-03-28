package com.thirtytwo.steps

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder

class AudioService : Service() {

    private lateinit var volumeController: VolumeController
    private lateinit var prefs: PrefsManager
    private lateinit var profileManager: SoundProfileManager
    private var overlay: VolumeOverlay? = null
    private var headphoneDetector: HeadphoneDetector? = null
    private var mediaSession: MediaSession? = null

    private val stepListener: (Int, Int) -> Unit = { step, total ->
        if (!appInForeground && !prefs.hideOverlay) {
            val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val label = when (am.mode) {
                android.media.AudioManager.MODE_IN_CALL,
                android.media.AudioManager.MODE_IN_COMMUNICATION -> "Call"
                android.media.AudioManager.MODE_RINGTONE -> "Ring"
                else -> "Media"
            }
            overlay?.show(step, total, label)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        prefs = PrefsManager(this)

        // Initialize audio — everything in try-catch so overlay always works
        try {
            val caps = DeviceCapabilities.getInstance(this)
            if (!caps.probed) caps.probe(this)
            VolumeController.configureForDevice(caps)
        } catch (e: Throwable) {
            android.util.Log.e("AudioService", "Probe failed", e)
        }

        try {
            volumeController = VolumeController.getInstance(this)
            profileManager = SoundProfileManager(this)
            volumeController.attachSession(0)
            volumeController.syncFromSystem()
            volumeController.startObserving()
            applySavedProfile()
        } catch (e: Throwable) {
            android.util.Log.e("AudioService", "Audio init failed", e)
            // Ensure these exist even if init failed
            if (!::volumeController.isInitialized) volumeController = VolumeController.getInstance(this)
            if (!::profileManager.isInitialized) profileManager = SoundProfileManager(this)
        }

        try { setupHeadphoneDetector() } catch (_: Throwable) {}

        // On TV: MediaSession takes over volume key handling from the system.
        // This prevents the system from also changing volume (fixes double-step issue).
        val isTv = packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
        if (isTv) {
            try { setupTvMediaSession() } catch (_: Throwable) {}
        }

        overlay = VolumeOverlay(this)
        overlay?.onSeekChanged = { step ->
            volumeController.setStep(step)
        }
        overlay?.onStreamChanged = {
            saveStreamVolumesToPreset()
        }
        volumeController.addStepListener(stepListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val sessionId = intent?.getIntExtra(EXTRA_SESSION_ID, -1) ?: -1
        if (sessionId > 0) {
            when (intent?.action) {
                ACTION_ATTACH_SESSION -> {
                    val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
                    volumeController.attachSession(sessionId, packageName)
                    if (volumeController.hasSoundProfile().not()) applySavedProfile()
                }
                ACTION_DETACH_SESSION -> volumeController.detachSession(sessionId)
            }
        }
        when (intent?.action) {
            ACTION_APPLY_PROFILE -> applySavedProfile()
            ACTION_CLEAR_PROFILE -> volumeController.setSoundProfile(null)
        }
        return START_STICKY
    }

    /**
     * On TV, create a MediaSession with a VolumeProvider that handles all volume control.
     * The system routes volume key presses to our VolumeProvider instead of changing
     * system volume directly. This prevents the double-step issue and hides the system
     * volume overlay. The AccessibilityService defers to this on TV (returns false).
     */
    private fun setupTvMediaSession() {
        val session = MediaSession(this, "32steps")
        session.setPlaybackState(
            PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                .build()
        )

        val vp = object : android.media.VolumeProvider(
            android.media.VolumeProvider.VOLUME_CONTROL_ABSOLUTE,
            prefs.totalSteps,
            volumeController.currentStep
        ) {
            override fun onSetVolumeTo(volume: Int) {
                volumeController.setStep(volume)
                setCurrentVolume(volumeController.currentStep)
            }

            override fun onAdjustVolume(direction: Int) {
                when (direction) {
                    android.media.AudioManager.ADJUST_RAISE -> volumeController.stepUp()
                    android.media.AudioManager.ADJUST_LOWER -> volumeController.stepDown()
                }
                setCurrentVolume(volumeController.currentStep)
            }
        }

        session.setPlaybackToRemote(vp)
        session.isActive = true
        mediaSession = session

        // Keep VolumeProvider's max/current in sync with step changes
        volumeController.addStepListener { step, total ->
            try {
                vp.setCurrentVolume(step)
            } catch (_: Throwable) {}
        }
    }

    private fun setupHeadphoneDetector() {
        headphoneDetector = HeadphoneDetector(this,
            onDeviceConnected = { deviceKey, deviceName ->
                if (!prefs.autoHeadphoneDetection) return@HeadphoneDetector

                var profileName = prefs.deviceProfileMappings[deviceKey]
                var profile = if (profileName != null) profileManager.findProfile(profileName) else null

                if (profile == null && deviceName.length >= 3) {
                    val matches = try { profileManager.searchProfiles(deviceName) } catch (_: Exception) { emptyList() }
                    if (matches.isNotEmpty()) {
                        profile = matches.firstOrNull {
                            it.name.contains(deviceName, ignoreCase = true) ||
                            deviceName.contains(it.name, ignoreCase = true)
                        } ?: matches.first()
                        profileName = profile.name

                        val mappings = prefs.deviceProfileMappings.toMutableMap()
                        mappings[deviceKey] = profileName
                        prefs.deviceProfileMappings = mappings
                    }
                }

                if (profile == null || profileName == null) return@HeadphoneDetector

                prefs.profileBeforeAutoSwitch = prefs.soundProfile
                prefs.soundProfile = profileName
                volumeController.setSoundProfile(profile)
            },
            onDeviceDisconnected = { _ ->
                if (!prefs.autoHeadphoneDetection) return@HeadphoneDetector
                val previous = prefs.profileBeforeAutoSwitch
                prefs.profileBeforeAutoSwitch = null
                if (previous != null) {
                    prefs.soundProfile = previous
                    val profile = try { profileManager.findProfile(previous) } catch (_: Exception) { null }
                    volumeController.setSoundProfile(profile)
                } else {
                    prefs.soundProfile = null
                    volumeController.setSoundProfile(null)
                }
            }
        )
        headphoneDetector?.register()
        headphoneDetector?.checkCurrentDevices()
    }

    private fun applySavedProfile() {
        val profileName = prefs.soundProfile ?: return
        val profile = profileManager.findProfile(profileName) ?: return
        volumeController.setSoundProfile(profile)
    }

    private fun saveStreamVolumesToPreset() {
        val activeProfile = prefs.soundProfile ?: return
        val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        prefs.addPreset(Preset(activeProfile, prefs.totalSteps,
            am.getStreamVolume(android.media.AudioManager.STREAM_RING),
            am.getStreamVolume(android.media.AudioManager.STREAM_NOTIFICATION),
            am.getStreamVolume(android.media.AudioManager.STREAM_ALARM),
            am.getStreamVolume(android.media.AudioManager.STREAM_VOICE_CALL)
        ))
    }

    override fun onDestroy() {
        mediaSession?.isActive = false
        mediaSession?.release()
        headphoneDetector?.unregister()
        volumeController.removeStepListener(stepListener)
        overlay?.hide()
        volumeController.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notificationManager(): NotificationManager {
        return if (Build.VERSION.SDK_INT >= 28) getSystemService(NotificationManager::class.java)
        else getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Volume Control", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps volume control active"
            setShowBadge(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        notificationManager().createNotificationChannel(channel)
    }

    private fun buildNotification(step: Int? = null, total: Int? = null): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val text = if (step != null && total != null) "Volume: $step / $total"
        else "Volume control active"

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("32steps")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(step: Int, total: Int) {
        notificationManager().notify(NOTIFICATION_ID, buildNotification(step, total))
    }

    companion object {
        const val CHANNEL_ID = "volume_control"
        const val NOTIFICATION_ID = 1
        const val ACTION_ATTACH_SESSION = "com.thirtytwo.steps.ATTACH_SESSION"
        const val ACTION_DETACH_SESSION = "com.thirtytwo.steps.DETACH_SESSION"
        const val ACTION_APPLY_PROFILE = "com.thirtytwo.steps.APPLY_PROFILE"
        const val ACTION_CLEAR_PROFILE = "com.thirtytwo.steps.CLEAR_PROFILE"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_PACKAGE_NAME = "package_name"

        @Volatile
        var appInForeground = false
    }
}
