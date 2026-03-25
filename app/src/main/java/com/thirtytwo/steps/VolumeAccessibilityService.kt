package com.thirtytwo.steps

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

class VolumeAccessibilityService : AccessibilityService() {

    private lateinit var volumeController: VolumeController
    private lateinit var prefs: PrefsManager
    private val handler = Handler(Looper.getMainLooper())

    // Hold-to-repeat state
    private var holdingKey: Int? = null
    private var repeatCount = 0
    private var lastActionDownTime = 0L

    // Initial delay before repeat starts, then accelerates
    private val initialRepeatDelayMs = 400L
    private val repeatIntervalMs = 80L
    private val fastRepeatIntervalMs = 40L
    private val fastRepeatAfter = 8

    private val repeatRunnable = object : Runnable {
        override fun run() {
            // Safety: if screen is no longer interactive (power pressed), stop repeating
            val pm = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            if (!pm.isInteractive) {
                holdingKey = null
                return
            }
            holdingKey?.let { key ->
                when (key) {
                    KeyEvent.KEYCODE_VOLUME_UP -> volumeController.stepUp()
                    KeyEvent.KEYCODE_VOLUME_DOWN -> volumeController.stepDown()
                }
                repeatCount++
                val interval = if (repeatCount > fastRepeatAfter) fastRepeatIntervalMs else repeatIntervalMs
                handler.postDelayed(this, interval)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        volumeController = VolumeController.getInstance(this)
        prefs = PrefsManager(this)

        val intent = Intent(this, AudioService::class.java)
        startForegroundService(intent)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!prefs.enabled) return false

        val isVolumeKey = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN

        if (!isVolumeKey) return false

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                lastActionDownTime = System.currentTimeMillis()
                if (holdingKey == null) {
                    holdingKey = event.keyCode
                    repeatCount = 0
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_VOLUME_UP -> volumeController.stepUp()
                        KeyEvent.KEYCODE_VOLUME_DOWN -> volumeController.stepDown()
                    }
                    handler.postDelayed(repeatRunnable, initialRepeatDelayMs)
                }
            }
            KeyEvent.ACTION_UP -> {
                holdingKey = null
                handler.removeCallbacks(repeatRunnable)
            }
        }

        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        holdingKey = null
        handler.removeCallbacks(repeatRunnable)
    }
}
