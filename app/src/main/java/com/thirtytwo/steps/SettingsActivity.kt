package com.thirtytwo.steps

import android.content.Intent
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: PrefsManager
    private lateinit var volumeController: VolumeController
    private lateinit var caps: DeviceCapabilities

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = PrefsManager(this)
        volumeController = VolumeController.getInstance(this)
        caps = DeviceCapabilities.getInstance(this)

        val isTv = packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)

        // TV: constrain content width and increase padding
        if (isTv) {
            val content = findViewById<android.view.ViewGroup>(android.R.id.content)
            val scrollView = content.getChildAt(0)
            if (scrollView is android.widget.ScrollView) {
                val inner = scrollView.getChildAt(0)
                if (inner is android.widget.LinearLayout) {
                    inner.setPadding(
                        (resources.displayMetrics.widthPixels * 0.15).toInt(),
                        inner.paddingTop,
                        (resources.displayMetrics.widthPixels * 0.15).toInt(),
                        inner.paddingBottom
                    )
                }
            }
        }

        // Hide overlay toggle on TV (no overlay)
        if (isTv) {
            findViewById<View>(R.id.switch_hide_overlay)?.let { findParentCard(it)?.visibility = View.GONE }
        } else {
            setupHideOverlay()
        }
        if (caps.hasStereoDP) setupChannelBalance()

        // Hide all other features for now — will add back later
        findViewById<View>(R.id.switch_equal_loudness)?.let { findParentCard(it)?.visibility = View.GONE }
        findViewById<View>(R.id.switch_crossfeed)?.let { findParentCard(it)?.visibility = View.GONE }
        findViewById<View>(R.id.switch_bass_tuner)?.let { findParentCard(it)?.visibility = View.GONE }
        findViewById<View>(R.id.switch_reverb)?.let { findParentCard(it)?.visibility = View.GONE }
        findViewById<View>(R.id.switch_virtualizer)?.let { findParentCard(it)?.visibility = View.GONE }
        findViewById<View>(R.id.preamp_slider)?.let { findParentCard(it)?.visibility = View.GONE }
        findViewById<View>(R.id.card_mono)?.visibility = View.GONE
        findViewById<View>(R.id.switch_limiter)?.let { findParentCard(it)?.visibility = View.GONE }
        if (!caps.hasStereoDP) {
            findViewById<View>(R.id.balance_slider)?.let { findParentCard(it)?.visibility = View.GONE }
        }
        findViewById<View>(R.id.switch_auto_headphone)?.let { findParentCard(it)?.visibility = View.GONE }
        findViewById<View>(R.id.switch_per_app_eq)?.let { findParentCard(it)?.visibility = View.GONE }

        // Hide section headers for hidden features
        hideTextViewByContent("Equalization")
        hideTextViewByContent("Effects")
        hideTextViewByContent("Automation")
        // Keep "Gain control" visible for channel balance

        // Diagnostics
        val ok = "\u2713"
        val no = "\u2717"
        fun yn(b: Boolean) = if (b) ok else no

        // USB DAC / audio output info
        val am = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        val outputDevices = am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
        val usbDac = outputDevices.firstOrNull {
            it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET ||
            it.type == android.media.AudioDeviceInfo.TYPE_USB_DEVICE
        }
        val outputInfo = if (usbDac != null) {
            val name = usbDac.productName?.toString()?.takeIf { it.isNotBlank() } ?: "USB DAC"
            val rates = usbDac.sampleRates.takeIf { it.isNotEmpty() }?.joinToString("/") { "${it/1000}kHz" } ?: "—"
            "Output:              $name\nSample rates:        $rates\n"
        } else ""

        findViewById<TextView>(R.id.diagnostics_text)?.text =
            "${outputInfo}" +
            "DynamicsProcessing:  ${yn(caps.hasDynamicsProcessing)}\n" +
            "Pre-EQ bands:        ${caps.maxPreEqBands}\n" +
            "Post-EQ bands:       ${caps.maxPostEqBands}\n" +
            "Stereo channels:     ${yn(caps.hasStereoDP)}\n" +
            "Multi-band compress: ${yn(caps.hasMbc)}\n" +
            "Limiter:             ${yn(caps.hasLimiter)}\n" +
            "Virtualizer:         ${yn(caps.hasVirtualizer)}\n" +
            "Reverb:              ${yn(caps.hasReverb)}\n" +
            "Spectrum analyzer:   ${yn(caps.hasVisualizer)}\n" +
            "Equalizer (legacy):  ${yn(caps.hasEqualizer)}"
    }

    private fun hideTextViewByContent(text: String) {
        val root = findViewById<android.view.ViewGroup>(android.R.id.content)
        findTextView(root, text)?.visibility = View.GONE
    }

    private fun findTextView(group: android.view.ViewGroup, text: String): View? {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child is TextView && child.text?.toString() == text) return child
            if (child is android.view.ViewGroup) {
                val found = findTextView(child, text)
                if (found != null) return found
            }
        }
        return null
    }

    /** Walk up the view tree to find the enclosing MaterialCardView */
    private fun findParentCard(view: View): View? {
        var v: View? = view
        while (v != null) {
            if (v is com.google.android.material.card.MaterialCardView) return v
            v = v.parent as? View
        }
        return null
    }

    private fun setupHideOverlay() {
        val switch = findViewById<MaterialSwitch>(R.id.switch_hide_overlay)
        switch.isChecked = prefs.hideOverlay
        switch.setOnCheckedChangeListener { view, isChecked ->
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            prefs.hideOverlay = isChecked
        }
    }

    private fun setupEqualLoudness() {
        val switch = findViewById<MaterialSwitch>(R.id.switch_equal_loudness)
        val thresholdRow = findViewById<View>(R.id.el_threshold_row)
        val thresholdSlider = findViewById<SeekBar>(R.id.el_threshold_slider)
        val thresholdValue = findViewById<TextView>(R.id.el_threshold_value)

        switch.isChecked = prefs.equalLoudnessEnabled
        thresholdRow.visibility = if (prefs.equalLoudnessEnabled) View.VISIBLE else View.GONE

        // Slider: 0-40 maps to 0 dB to -40 dB
        val currentDb = prefs.equalLoudnessThresholdDb // -40 to 0
        thresholdSlider.progress = -currentDb // 0 to 40
        updateThresholdLabel(thresholdValue, currentDb)

        switch.setOnCheckedChangeListener { view, isChecked ->
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            thresholdRow.visibility = if (isChecked) View.VISIBLE else View.GONE
            volumeController.setEqualLoudness(isChecked)
        }

        thresholdSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    seekBar?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    val db = -progress // 0 to -40
                    updateThresholdLabel(thresholdValue, db)
                    volumeController.setEqualLoudness(true, db)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                seekBar?.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun updateThresholdLabel(tv: TextView, db: Int) {
        tv.text = "${db}dB"
    }

    private fun setupBassTuner() {
        val switch = findViewById<MaterialSwitch>(R.id.switch_bass_tuner)
        val controls = findViewById<View>(R.id.bass_tuner_controls)
        val gainSlider = findViewById<SeekBar>(R.id.bass_gain_slider)
        val gainValue = findViewById<TextView>(R.id.bass_gain_value)
        val freqSlider = findViewById<SeekBar>(R.id.bass_freq_slider)
        val freqValue = findViewById<TextView>(R.id.bass_freq_value)
        val typeGroup = findViewById<MaterialButtonToggleGroup>(R.id.bass_type_group)

        val isOn = prefs.bassBoostDb > 0f
        switch.isChecked = isOn
        controls.visibility = if (isOn) View.VISIBLE else View.GONE

        switch.setOnCheckedChangeListener { view, isChecked ->
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            controls.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (!isChecked) {
                // Reset bass tuner
                gainSlider.progress = 0
                updateBassGainLabel(gainValue, 0f)
                freqSlider.progress = 40 // 80 Hz default
                updateBassFreqLabel(freqValue, 80)
                volumeController.setBassTuner(0f, 80, type = 0)
                typeGroup.check(R.id.bass_natural)
            }
        }

        // Gain: 0-120 → 0.0-12.0 dB
        gainSlider.progress = (prefs.bassBoostDb * 10f).toInt()
        updateBassGainLabel(gainValue, prefs.bassBoostDb)

        // Frequency: 0-210 → 40-250 Hz
        freqSlider.progress = prefs.bassCutoffHz - 40
        updateBassFreqLabel(freqValue, prefs.bassCutoffHz)

        // Bass type
        val typeIds = intArrayOf(R.id.bass_natural, R.id.bass_transient, R.id.bass_sustain)
        typeGroup.check(typeIds[prefs.bassType.coerceIn(0, 2)])

        typeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                typeGroup.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                val type = typeIds.indexOf(checkedId).coerceAtLeast(0)
                volumeController.setBassTuner(prefs.bassBoostDb, type = type)
            }
        }

        gainSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    seekBar?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    val db = progress / 10f
                    updateBassGainLabel(gainValue, db)
                    volumeController.setBassTuner(db)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                seekBar?.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        freqSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    seekBar?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    val hz = progress + 40
                    updateBassFreqLabel(freqValue, hz)
                    volumeController.setBassTuner(prefs.bassBoostDb, hz)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                seekBar?.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun updateBassGainLabel(tv: TextView, db: Float) {
        tv.text = if (db == 0f) "Off" else String.format("+%.1f dB", db)
    }

    private fun updateBassFreqLabel(tv: TextView, hz: Int) {
        tv.text = "$hz Hz"
    }

    private fun setupReverb() {
        val switch = findViewById<MaterialSwitch>(R.id.switch_reverb)
        val presetGroup = findViewById<MaterialButtonToggleGroup>(R.id.reverb_presets)

        switch.isChecked = prefs.reverbEnabled
        presetGroup.visibility = if (prefs.reverbEnabled) View.VISIBLE else View.GONE

        // Set initial preset selection
        val presetIds = intArrayOf(R.id.reverb_small, R.id.reverb_medium, R.id.reverb_hall, R.id.reverb_cathedral)
        presetGroup.check(presetIds[prefs.reverbPreset.coerceIn(0, 3)])

        switch.setOnCheckedChangeListener { view, isChecked ->
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            presetGroup.visibility = if (isChecked) View.VISIBLE else View.GONE
            volumeController.setReverb(isChecked)
        }

        presetGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                presetGroup.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                val preset = presetIds.indexOf(checkedId).coerceAtLeast(0)
                volumeController.setReverb(true, preset)
            }
        }
    }

    private fun setupVirtualizer() {
        val switch = findViewById<MaterialSwitch>(R.id.switch_virtualizer)
        val slider = findViewById<SeekBar>(R.id.virtualizer_slider)

        switch.isChecked = prefs.virtualizerEnabled
        slider.visibility = if (prefs.virtualizerEnabled) View.VISIBLE else View.GONE
        slider.progress = prefs.virtualizerStrength

        switch.setOnCheckedChangeListener { view, isChecked ->
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            slider.visibility = if (isChecked) View.VISIBLE else View.GONE
            volumeController.setVirtualizer(isChecked)
            // Mutual exclusion: disable crossfeed when virtualizer is on
            if (isChecked) {
                findViewById<MaterialSwitch>(R.id.switch_crossfeed).isChecked = false
                findViewById<SeekBar>(R.id.crossfeed_slider).visibility = View.GONE
            }
        }

        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    seekBar?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    volumeController.setVirtualizer(true, progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                seekBar?.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun setupLimiter() {
        val switch = findViewById<MaterialSwitch>(R.id.switch_limiter)
        switch.isChecked = prefs.limiterEnabled
        switch.setOnCheckedChangeListener { view, isChecked ->
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            volumeController.setLimiterEnabled(isChecked)
        }
    }

    private fun setupChannelBalance() {
        val slider = findViewById<SeekBar>(R.id.balance_slider)
        val valueText = findViewById<TextView>(R.id.balance_value)

        val currentBalance = prefs.channelBalance
        slider.progress = ((currentBalance + 1f) * 100f).toInt()
        updateBalanceLabel(valueText, currentBalance)

        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    seekBar?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    val balance = (progress - 100f) / 100f
                    updateBalanceLabel(valueText, balance)
                    volumeController.setChannelBalance(balance)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                seekBar?.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun updateBalanceLabel(tv: TextView, balance: Float) {
        tv.text = when {
            balance < -0.01f -> String.format("L %.0f%%", -balance * 100)
            balance > 0.01f -> String.format("R %.0f%%", balance * 100)
            else -> "Center"
        }
    }

    private fun setupPreamp() {
        val slider = findViewById<SeekBar>(R.id.preamp_slider)
        val valueText = findViewById<TextView>(R.id.preamp_value)

        // 0-240 → -12.0 to +12.0 dB
        val currentDb = prefs.preampDb
        slider.progress = ((currentDb + 12f) * 10f).toInt()
        updatePreampLabel(valueText, currentDb)

        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    seekBar?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    val db = (progress - 120) / 10f
                    updatePreampLabel(valueText, db)
                    volumeController.setPreamp(db)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                seekBar?.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun updatePreampLabel(tv: TextView, db: Float) {
        tv.text = when {
            db > 0.05f -> String.format("+%.1f dB", db)
            db < -0.05f -> String.format("%.1f dB", db)
            else -> "0 dB"
        }
    }

    private fun setupMono() {
        val status = findViewById<TextView>(R.id.mono_status)
        val isMono = try {
            android.provider.Settings.System.getInt(contentResolver, "master_mono", 0) == 1
        } catch (_: Exception) { false }
        status.text = if (isMono) "Currently on" else "Tap to open system audio settings"

        findViewById<View>(R.id.card_mono).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun setupCrossfeed() {
        val switch = findViewById<MaterialSwitch>(R.id.switch_crossfeed)
        val slider = findViewById<SeekBar>(R.id.crossfeed_slider)
        val virtualizerSwitch = findViewById<MaterialSwitch>(R.id.switch_virtualizer)

        switch.isChecked = prefs.crossfeedEnabled
        slider.visibility = if (prefs.crossfeedEnabled) View.VISIBLE else View.GONE
        slider.progress = prefs.crossfeedStrength

        switch.setOnCheckedChangeListener { view, isChecked ->
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            slider.visibility = if (isChecked) View.VISIBLE else View.GONE
            volumeController.setCrossfeed(isChecked)
            // Mutual exclusion: disable virtualizer when crossfeed is on
            if (isChecked) {
                virtualizerSwitch.isChecked = false
                findViewById<SeekBar>(R.id.virtualizer_slider).visibility = View.GONE
            }
        }

        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    seekBar?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    volumeController.setCrossfeed(true, progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                seekBar?.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun setupAutoHeadphone() {
        val switch = findViewById<MaterialSwitch>(R.id.switch_auto_headphone)
        val manageBtn = findViewById<MaterialButton>(R.id.btn_manage_devices)

        switch.isChecked = prefs.autoHeadphoneDetection
        manageBtn.visibility = if (prefs.autoHeadphoneDetection) View.VISIBLE else View.GONE

        switch.setOnCheckedChangeListener { view, isChecked ->
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            prefs.autoHeadphoneDetection = isChecked
            manageBtn.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        manageBtn.setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            startActivity(Intent(this, DeviceMappingsActivity::class.java))
        }
    }

    private fun setupPerAppEq() {
        val switch = findViewById<MaterialSwitch>(R.id.switch_per_app_eq)
        val configureBtn = findViewById<MaterialButton>(R.id.btn_configure_apps)

        switch.isChecked = prefs.perAppEqEnabled
        configureBtn.visibility = if (prefs.perAppEqEnabled) View.VISIBLE else View.GONE

        switch.setOnCheckedChangeListener { view, isChecked ->
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            prefs.perAppEqEnabled = isChecked
            configureBtn.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        configureBtn.setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            startActivity(Intent(this, PerAppEqActivity::class.java))
        }
    }
}
