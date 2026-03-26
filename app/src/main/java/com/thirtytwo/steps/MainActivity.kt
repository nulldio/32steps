package com.thirtytwo.steps

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.InputMethodManager
import android.media.AudioManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: PrefsManager
    private lateinit var volumeController: VolumeController
    private lateinit var profileManager: SoundProfileManager
    private lateinit var stepsInput: EditText
    private lateinit var volumeSeekbar: SeekBar
    private lateinit var volumeCounter: TextView
    private lateinit var statusText: TextView
    private lateinit var setupBtn: Button
    private lateinit var presetList: LinearLayout
    private var pendingSetup = false
    private var profilesLoaded = false

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
        profileManager = SoundProfileManager(this)
        stepsInput = findViewById(R.id.steps_input)
        volumeSeekbar = findViewById(R.id.volume_seekbar)
        volumeCounter = findViewById(R.id.volume_counter)
        statusText = findViewById(R.id.status_text)
        setupBtn = findViewById(R.id.btn_setup)
        presetList = findViewById(R.id.preset_list)

        setupStepsInput()
        setupVolumeSeekbar()
        setupStreamSliders()
        setupBtn.setOnClickListener { openNextSetupStep() }
        findViewById<android.view.View>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<android.view.View>(R.id.btn_create_custom).setOnClickListener {
            startActivity(Intent(this, CalibrationActivity::class.java))
        }
        updateVolumeBar()
        loadPresetGrid()

        setupSoundProfile()

        // Preload profiles in background
        Thread {
            profileManager.loadProfiles()
            profilesLoaded = true
        }.start()
    }

    override fun onResume() {
        super.onResume()
        AudioService.appInForeground = true
        volumeController.addStepListener(stepListener)
        volumeController.syncFromSystem()
        updateVolumeBar()
        updateStatus()
        // Refresh stream sliders and preset list
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        refreshAppStreamSliders(am)
        loadPresetGrid()

        if (pendingSetup) {
            pendingSetup = false
            nextMissingPermission()?.let {
                setupBtn.postDelayed({ openPermission(it) }, 400)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        AudioService.appInForeground = false
        volumeController.removeStepListener(stepListener)
    }

    // Steps input

    private fun setupStepsInput() {
        stepsInput.setText(prefs.totalSteps.toString())
        stepsInput.filters = arrayOf(InputFilter.LengthFilter(4))

        findViewById<View>(R.id.steps_card).setOnClickListener {
            if (!stepsInput.hasFocus()) {
                stepsInput.requestFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(stepsInput, InputMethodManager.SHOW_IMPLICIT)
            }
        }

        stepsInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                stepsInput.setText("")
            } else if (stepsInput.text.isNullOrEmpty()) {
                stepsInput.setText(prefs.totalSteps.toString())
            }
        }

        stepsInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString()?.takeIf { it.isNotEmpty() } ?: return
                val raw = text.toIntOrNull() ?: return
                if (raw > 1000) {
                    stepsInput.setText("1000")
                    stepsInput.setSelection(4)
                    return
                }
                if (raw >= 1) {
                    prefs.totalSteps = raw
                    volumeController.syncFromSystem()
                    updateVolumeBar()
                    // Update active preset
                    val activeProfile = prefs.soundProfile
                    if (activeProfile != null) {
                        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        prefs.addPreset(Preset(activeProfile, raw,
                            am.getStreamVolume(AudioManager.STREAM_RING),
                            am.getStreamVolume(AudioManager.STREAM_NOTIFICATION),
                            am.getStreamVolume(AudioManager.STREAM_ALARM),
                            am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
                        ))
                        loadPresetGrid()
                    }
                }
            }
        })
    }

    // Volume seekbar

    private fun setupVolumeSeekbar() {
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
    }

    private fun setupStreamSliders() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val expandBtn = findViewById<ImageView>(R.id.btn_expand_app)
        val extraSliders = findViewById<View>(R.id.extra_sliders_app)
        var expanded = false

        expandBtn.setOnClickListener {
            // Request DND access if not granted
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (!nm.isNotificationPolicyAccessGranted) {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                return@setOnClickListener
            }
            expanded = !expanded
            extraSliders.visibility = if (expanded) View.VISIBLE else View.GONE
            expandBtn.setImageResource(if (expanded) R.drawable.ic_collapse else R.drawable.ic_expand)
            if (expanded) refreshAppStreamSliders(audioManager)
        }

        setupAppStreamSlider(R.id.ring_slider_app, R.id.ring_counter_app, AudioManager.STREAM_RING, audioManager)
        setupAppStreamSlider(R.id.notification_slider_app, R.id.notif_counter_app, AudioManager.STREAM_NOTIFICATION, audioManager)
        setupAppStreamSlider(R.id.alarm_slider_app, R.id.alarm_counter_app, AudioManager.STREAM_ALARM, audioManager)
        setupAppStreamSlider(R.id.call_slider_app, R.id.call_counter_app, AudioManager.STREAM_VOICE_CALL, audioManager)
    }

    private fun setupAppStreamSlider(sliderId: Int, counterId: Int, stream: Int, audioManager: android.media.AudioManager) {
        val slider = findViewById<SeekBar>(sliderId)
        val counter = findViewById<TextView>(counterId)
        val max = audioManager.getStreamMaxVolume(stream)
        val current = audioManager.getStreamVolume(stream)
        slider.max = max
        slider.progress = current
        counter.text = "$current/$max"
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    try { audioManager.setStreamVolume(stream, progress, 0) } catch (_: Exception) {}
                    counter.text = "$progress/$max"
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // Auto-save to active preset
                val activeProfile = prefs.soundProfile ?: return
                prefs.addPreset(Preset(activeProfile, prefs.totalSteps,
                    audioManager.getStreamVolume(AudioManager.STREAM_RING),
                    audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION),
                    audioManager.getStreamVolume(AudioManager.STREAM_ALARM),
                    audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
                ))
            }
        })
    }

    private fun refreshAppStreamSliders(audioManager: android.media.AudioManager) {
        refreshOneAppSlider(R.id.ring_slider_app, R.id.ring_counter_app, AudioManager.STREAM_RING, audioManager)
        refreshOneAppSlider(R.id.notification_slider_app, R.id.notif_counter_app, AudioManager.STREAM_NOTIFICATION, audioManager)
        refreshOneAppSlider(R.id.alarm_slider_app, R.id.alarm_counter_app, AudioManager.STREAM_ALARM, audioManager)
        refreshOneAppSlider(R.id.call_slider_app, R.id.call_counter_app, AudioManager.STREAM_VOICE_CALL, audioManager)
    }

    private fun refreshOneAppSlider(sliderId: Int, counterId: Int, stream: Int, audioManager: android.media.AudioManager) {
        val max = audioManager.getStreamMaxVolume(stream)
        val current = audioManager.getStreamVolume(stream)
        findViewById<SeekBar>(sliderId).apply {
            this.max = max
            progress = current
        }
        findViewById<TextView>(counterId).text = "$current/$max"
    }

    private fun updateVolumeBar() {
        val total = prefs.totalSteps
        val current = volumeController.currentStep
        volumeSeekbar.max = total
        volumeSeekbar.progress = current
        volumeCounter.text = "$current/$total"
    }

    // Presets

    private fun loadPresetGrid() {
        presetList.removeAllViews()
        val presets = prefs.getPresets()

        for (preset in presets) {
            val card = layoutInflater.inflate(R.layout.item_preset, presetList, false)
            card.findViewById<TextView>(R.id.preset_name).text = preset.headphoneName
            card.findViewById<TextView>(R.id.preset_steps).text = "${preset.steps} steps"

            // Highlight active preset
            val isActive = prefs.soundProfile == preset.headphoneName
            if (isActive) {
                (card as com.google.android.material.card.MaterialCardView).apply {
                    strokeWidth = (2 * resources.displayMetrics.density).toInt()
                    strokeColor = getColor(com.google.android.material.R.color.material_on_surface_emphasis_medium)
                }
            }

            // Tap to apply or deselect
            card.setOnClickListener {
                if (prefs.soundProfile == preset.headphoneName) {
                    // Deselect
                    prefs.soundProfile = null
                    refreshPresetHighlights()
                    val intent = Intent(this@MainActivity, AudioService::class.java)
                    intent.action = AudioService.ACTION_CLEAR_PROFILE
                    startService(intent)
                } else {
                    // Apply
                    prefs.totalSteps = preset.steps
                    prefs.soundProfile = preset.headphoneName
                    stepsInput.setText(preset.steps.toString())
                    volumeController.syncFromSystem()
                    updateVolumeBar()
                    refreshPresetHighlights()
                    val intent = Intent(this@MainActivity, AudioService::class.java)
                    intent.action = AudioService.ACTION_APPLY_PROFILE
                    startService(intent)
                }
            }

            // Long press to delete
            card.setOnLongClickListener {
                AlertDialog.Builder(this)
                    .setMessage("Remove ${preset.headphoneName}?")
                    .setPositiveButton("Remove") { _, _ ->
                        prefs.removePreset(preset.headphoneName)
                        if (prefs.soundProfile == preset.headphoneName) {
                            prefs.soundProfile = null
                            val intent = Intent(this@MainActivity, AudioService::class.java)
                            intent.action = AudioService.ACTION_CLEAR_PROFILE
                            startService(intent)
                        }
                        loadPresetGrid()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                true
            }

            // Edit button
            card.findViewById<android.view.View>(R.id.preset_edit).setOnClickListener {
                val intent = Intent(this@MainActivity, CalibrationActivity::class.java)
                intent.putExtra(CalibrationActivity.EXTRA_PROFILE_NAME, preset.headphoneName)
                startActivity(intent)
            }

            presetList.addView(card)
        }
    }

    private fun refreshPresetHighlights() {
        loadPresetGrid()
    }

    private fun setupSoundProfile() {
        val addBtn = findViewById<View>(R.id.btn_add_preset)
        val searchInput = findViewById<EditText>(R.id.profile_search)
        val searchResultsView = findViewById<LinearLayout>(R.id.search_results)
        var currentResults: List<HeadphoneProfile>

        addBtn.setOnClickListener {
            addBtn.visibility = View.GONE
            searchInput.visibility = View.VISIBLE
            searchInput.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
        }

        searchInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                searchInput.setText("")
                searchInput.visibility = View.GONE
                addBtn.visibility = View.VISIBLE
                searchResultsView.visibility = View.GONE
            }
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString() ?: ""
                searchResultsView.removeAllViews()

                if (query.length < 2 || !profilesLoaded) {
                    searchResultsView.visibility = View.GONE
                    return
                }

                currentResults = profileManager.searchProfiles(query)
                if (currentResults.isEmpty()) {
                    searchResultsView.visibility = View.GONE
                    return
                }

                searchResultsView.visibility = View.VISIBLE
                for (profile in currentResults) {
                    val item = layoutInflater.inflate(R.layout.item_preset, searchResultsView, false)
                    item.findViewById<TextView>(R.id.preset_name).text = profile.name
                    item.findViewById<TextView>(R.id.preset_steps).text = profile.category
                    item.setOnClickListener {
                        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        val preset = Preset(profile.name, prefs.totalSteps,
                            am.getStreamVolume(AudioManager.STREAM_RING),
                            am.getStreamVolume(AudioManager.STREAM_NOTIFICATION),
                            am.getStreamVolume(AudioManager.STREAM_ALARM),
                            am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
                        )
                        prefs.addPreset(preset)
                        prefs.soundProfile = profile.name
                        loadPresetGrid()

                        searchInput.setText("")
                        searchInput.clearFocus()
                        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.hideSoftInputFromWindow(searchInput.windowToken, 0)

                        val intent = Intent(this@MainActivity, AudioService::class.java)
                        intent.action = AudioService.ACTION_APPLY_PROFILE
                        startService(intent)
                    }
                    searchResultsView.addView(item)
                }
            }
        })
    }

    // Permission setup

    private enum class Permission { ACCESSIBILITY, OVERLAY, BATTERY }

    private fun nextMissingPermission(): Permission? {
        if (!isAccessibilityEnabled()) return Permission.ACCESSIBILITY
        if (!Settings.canDrawOverlays(this)) return Permission.OVERLAY
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName) && !prefs.batterySetupDone)
            return Permission.BATTERY
        return null
    }

    private fun openNextSetupStep() {
        nextMissingPermission()?.let { openPermission(it) }
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
                    // Direct popup asking to exempt this app
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                } catch (_: Exception) {
                    // Fallback: Huawei-specific settings
                    try {
                        startActivity(Intent().apply {
                            component = ComponentName(
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
    }

    private fun updateStatus() {
        val next = nextMissingPermission()

        if (next == null) {
            findViewById<View>(R.id.permissions_title).visibility = View.GONE
            findViewById<View>(R.id.status_card).visibility = View.GONE
            setupBtn.visibility = View.GONE
            return
        }

        findViewById<View>(R.id.permissions_title).visibility = View.VISIBLE
        findViewById<View>(R.id.status_card).visibility = View.VISIBLE
        setupBtn.visibility = View.VISIBLE

        val accessibilityOk = isAccessibilityEnabled()
        val overlayOk = Settings.canDrawOverlays(this)
        statusText.text = listOf(
            if (accessibilityOk) "\u2713 Accessibility service" else "\u2717 Accessibility service",
            if (overlayOk) "\u2713 Overlay permission" else "\u2717 Overlay permission",
            if (prefs.batterySetupDone) "\u2713 Battery optimization" else "\u2717 Battery optimization"
        ).joinToString("\n")

        setupBtn.text = when (next) {
            Permission.ACCESSIBILITY -> "Enable Accessibility Service"
            Permission.OVERLAY -> "Grant Overlay Permission"
            Permission.BATTERY -> "Disable Battery Optimization"
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
            .any { it.resolveInfo.serviceInfo.packageName == packageName }
    }
}
