package com.thirtytwo.steps

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: PrefsManager
    private lateinit var statusText: TextView
    private lateinit var stepsInput: EditText
    private lateinit var volumeSeekbar: SeekBar
    private lateinit var volumeCounter: TextView
    private lateinit var volumeController: VolumeController
    private lateinit var setupBtn: Button

    private val stepListener: (Int, Int) -> Unit = { step, total ->
        runOnUiThread {
            volumeSeekbar.max = total
            volumeSeekbar.progress = step
            volumeCounter.text = "$step/$total"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = PrefsManager(this)
        volumeController = VolumeController.getInstance(this)
        statusText = findViewById(R.id.status_text)
        stepsInput = findViewById(R.id.steps_input)
        volumeSeekbar = findViewById(R.id.volume_seekbar)
        volumeCounter = findViewById(R.id.volume_counter)
        setupBtn = findViewById(R.id.btn_setup)

        stepsInput.setText(prefs.totalSteps.toString())
        stepsInput.filters = arrayOf(android.text.InputFilter.LengthFilter(4))
        updateVolumeBar()

        stepsInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val raw = s?.toString()?.toIntOrNull() ?: return
                if (raw > 1000) {
                    stepsInput.setText("1000")
                    stepsInput.setSelection(4)
                    return
                }
                if (raw >= 2) {
                    prefs.totalSteps = raw.coerceIn(2, 1000)
                    volumeController.syncFromSystem()
                    updateVolumeBar()
                }
            }
        })

        volumeSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    volumeController.setStep(progress)
                    volumeCounter.text = "$progress/${prefs.totalSteps}"
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        setupBtn.setOnClickListener {
            openNextSetupStep()
        }
    }

    override fun onResume() {
        super.onResume()
        AudioService.appInForeground = true
        volumeController.addStepListener(stepListener)
        volumeController.syncFromSystem()
        updateVolumeBar()
        updateStatus()

        if (pendingSetup) {
            pendingSetup = false
            val next = nextMissingPermission()
            if (next != null) {
                setupBtn.postDelayed({ openPermission(next) }, 400)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        AudioService.appInForeground = false
        volumeController.removeStepListener(stepListener)
    }

    private var pendingSetup = false

    private enum class Permission { ACCESSIBILITY, OVERLAY, BATTERY }

    private fun nextMissingPermission(): Permission? {
        if (!isAccessibilityEnabled()) return Permission.ACCESSIBILITY
        if (!Settings.canDrawOverlays(this)) return Permission.OVERLAY
        if (!prefs.batterySetupDone) return Permission.BATTERY
        return null
    }

    private fun openNextSetupStep() {
        val next = nextMissingPermission()
        if (next != null) openPermission(next)
    }

    private fun openPermission(permission: Permission) {
        pendingSetup = true
        when (permission) {
            Permission.ACCESSIBILITY ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Permission.OVERLAY ->
                startActivity(Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                ))
            Permission.BATTERY -> {
                prefs.batterySetupDone = true
                try {
                    startActivity(Intent().apply {
                        component = android.content.ComponentName(
                            "com.huawei.systemmanager",
                            "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                        )
                    })
                } catch (_: Exception) {
                    try {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    } catch (_: Exception) {
                        startActivity(Intent(Settings.ACTION_SETTINGS))
                    }
                }
            }
        }
    }

    private fun updateVolumeBar() {
        val total = prefs.totalSteps
        val current = volumeController.currentStep
        volumeSeekbar.max = total
        volumeSeekbar.progress = current
        volumeCounter.text = "$current/$total"
    }

    private fun updateStatus() {
        val accessibilityOk = isAccessibilityEnabled()
        val overlayOk = Settings.canDrawOverlays(this)
        val next = nextMissingPermission()

        if (next == null) {
            findViewById<View>(R.id.permissions_title).visibility = View.GONE
            findViewById<View>(R.id.status_card).visibility = View.GONE
            setupBtn.visibility = View.GONE
        } else {
            findViewById<View>(R.id.permissions_title).visibility = View.VISIBLE
            findViewById<View>(R.id.status_card).visibility = View.VISIBLE
            setupBtn.visibility = View.VISIBLE

            val lines = listOf(
                if (accessibilityOk) "\u2713 Accessibility service" else "\u2717 Accessibility service",
                if (overlayOk) "\u2713 Overlay permission" else "\u2717 Overlay permission",
                if (prefs.batterySetupDone) "\u2713 Battery optimization" else "\u2717 Battery optimization"
            )
            statusText.text = lines.joinToString("\n")

            setupBtn.text = when (next) {
                Permission.ACCESSIBILITY -> "Enable Accessibility Service"
                Permission.OVERLAY -> "Grant Overlay Permission"
                Permission.BATTERY -> "Disable Battery Optimization"
            }
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabled = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
        return enabled.any {
            it.resolveInfo.serviceInfo.packageName == packageName
        }
    }
}
