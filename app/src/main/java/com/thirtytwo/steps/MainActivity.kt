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
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.ListView
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
    private lateinit var presetGrid: GridLayout
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
        presetGrid = findViewById(R.id.preset_grid)

        setupStepsInput()
        setupVolumeSeekbar()
        setupBtn.setOnClickListener { openNextSetupStep() }
        updateVolumeBar()
        loadPresetGrid()

        findViewById<View>(R.id.btn_add_preset).setOnClickListener {
            showHeadphoneSearch()
        }

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

    private fun updateVolumeBar() {
        val total = prefs.totalSteps
        val current = volumeController.currentStep
        volumeSeekbar.max = total
        volumeSeekbar.progress = current
        volumeCounter.text = "$current/$total"
    }

    // Presets

    private fun loadPresetGrid() {
        presetGrid.removeAllViews()
        val presets = prefs.getPresets()

        for (preset in presets) {
            val card = layoutInflater.inflate(R.layout.item_preset, presetGrid, false)
            card.findViewById<TextView>(R.id.preset_name).text = preset.headphoneName
            card.findViewById<TextView>(R.id.preset_steps).text = "${preset.steps} steps"

            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)
            }
            card.layoutParams = params

            // Tap to apply
            card.setOnClickListener {
                prefs.totalSteps = preset.steps
                prefs.soundProfile = preset.headphoneName
                stepsInput.setText(preset.steps.toString())
                volumeController.syncFromSystem()
                updateVolumeBar()
                val intent = Intent(this, AudioService::class.java)
                intent.action = AudioService.ACTION_APPLY_PROFILE
                startService(intent)
            }

            // Long press to delete
            card.setOnLongClickListener {
                AlertDialog.Builder(this)
                    .setMessage("Remove ${preset.headphoneName}?")
                    .setPositiveButton("Remove") { _, _ ->
                        prefs.removePreset(preset.headphoneName)
                        if (prefs.soundProfile == preset.headphoneName) {
                            prefs.soundProfile = null
                            val intent = Intent(this, AudioService::class.java)
                            intent.action = AudioService.ACTION_CLEAR_PROFILE
                            startService(intent)
                        }
                        loadPresetGrid()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                true
            }

            presetGrid.addView(card)
        }
    }

    private fun showHeadphoneSearch() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_headphone_search, null)
        val searchInput = dialogView.findViewById<EditText>(R.id.search_input)
        val listView = dialogView.findViewById<ListView>(R.id.search_results)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Add headphones")
            .setView(dialogView)
            .setNegativeButton("Cancel", null)
            .create()

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            mutableListOf<String>()
        )
        listView.adapter = adapter
        var currentResults = listOf<HeadphoneProfile>()

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString() ?: ""
                if (query.length < 2 || !profilesLoaded) {
                    adapter.clear()
                    adapter.notifyDataSetChanged()
                    return
                }
                currentResults = profileManager.searchProfiles(query)
                adapter.clear()
                adapter.addAll(currentResults.map { it.name })
                adapter.notifyDataSetChanged()
            }
        })

        listView.setOnItemClickListener { _, _, position, _ ->
            if (position < currentResults.size) {
                val profile = currentResults[position]
                val preset = Preset(profile.name, prefs.totalSteps)
                prefs.addPreset(preset)
                prefs.soundProfile = profile.name
                loadPresetGrid()

                val intent = Intent(this, AudioService::class.java)
                intent.action = AudioService.ACTION_APPLY_PROFILE
                startService(intent)

                dialog.dismiss()
            }
        }

        dialog.show()
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
