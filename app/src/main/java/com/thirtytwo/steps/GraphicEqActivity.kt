package com.thirtytwo.steps

import android.media.audiofx.DynamicsProcessing
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class GraphicEqActivity : AppCompatActivity() {

    data class Band(val label: String, val freq: Int)

    private val bands = listOf(
        Band("31", 31),
        Band("62", 62),
        Band("125", 125),
        Band("250", 250),
        Band("500", 500),
        Band("1k", 1000),
        Band("2k", 2000),
        Band("4k", 4000),
        Band("8k", 8000),
        Band("16k", 16000)
    )

    private val gains = FloatArray(10)
    private val defaultGains = FloatArray(10)

    private var dp: DynamicsProcessing? = null
    private lateinit var volumeController: VolumeController
    private lateinit var eqView: GraphicEqView
    private var editingProfileName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_graphic_eq)

        volumeController = VolumeController.getInstance(this)
        dp = volumeController.startCalibration()

        if (dp == null) {
            findViewById<TextView>(R.id.eq_title).text = "Error: service not running"
            return
        }

        editingProfileName = intent.getStringExtra(EXTRA_PROFILE_NAME)
        loadBaseline()
        initializeEq()

        eqView = findViewById(R.id.eq_view)
        eqView.setGains(gains)
        eqView.listener = GraphicEqView.OnGainChangeListener { index, gain ->
            gains[index] = gain
            applyBand(index)
        }

        findViewById<View>(R.id.btn_reset).setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
            for (i in gains.indices) gains[i] = defaultGains[i]
            eqView.setGains(gains)
            applyAllBands()
        }

        findViewById<View>(R.id.btn_save).setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
            save()
        }

        findViewById<View>(R.id.btn_cancel).setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
            volumeController.stopCalibration()
            finish()
        }
    }

    private fun loadBaseline() {
        val profileManager = SoundProfileManager(this)
        val profileName = editingProfileName

        if (profileName != null) {
            // Load current values (custom override if exists, else AutoEQ)
            val profile = profileManager.findProfile(profileName)
            // Load original AutoEQ for reset defaults (skip custom overrides)
            val original = profileManager.findOriginalProfile(profileName) ?: profile

            if (profile != null) {
                for (i in bands.indices) {
                    val targetFreq = bands[i].freq
                    val closest = profile.bands.minByOrNull { kotlin.math.abs(it.first - targetFreq) }
                    if (closest != null) gains[i] = closest.second
                }
            }
            if (original != null) {
                for (i in bands.indices) {
                    val targetFreq = bands[i].freq
                    val closest = original.bands.minByOrNull { kotlin.math.abs(it.first - targetFreq) }
                    if (closest != null) defaultGains[i] = closest.second
                }
            }
            if (profile != null || original != null) return
        }

        val harman = floatArrayOf(4.0f, 3.5f, 2.0f, 1.0f, 0.5f, 0.0f, -0.5f, -1.0f, -2.0f, -3.5f)
        for (i in gains.indices) {
            gains[i] = harman[i]
            defaultGains[i] = harman[i]
        }
    }

    private fun initializeEq() {
        try {
            val d = dp ?: return
            val preEq = d.getPreEqByChannelIndex(0) ?: return
            preEq.isEnabled = true
            d.setPreEqAllChannelsTo(preEq)
            applyAllBands()
        } catch (_: Exception) {}
    }

    /** Interpolate 10 graphic bands to 31 DP bands and apply all */
    private fun applyAllBands() {
        val d = dp ?: return
        val pairs = bands.mapIndexed { i, b -> Pair(b.freq, gains[i]) }
        val interpolated = BiquadMath.interpolateToGrid(pairs, VolumeController.PRE_EQ_FREQS)
        try {
            for (i in VolumeController.PRE_EQ_FREQS.indices) {
                val band = d.getPreEqBandByChannelIndex(0, i) ?: continue
                band.isEnabled = true
                band.cutoffFrequency = VolumeController.PRE_EQ_FREQS[i].toFloat()
                band.gain = interpolated[i]
                d.setPreEqBandAllChannelsTo(i, band)
            }
            d.setInputGainAllChannelsTo(0f)
        } catch (_: Exception) {}
    }

    /** Called on single band change — recomputes and applies all 31 bands */
    private fun applyBand(@Suppress("UNUSED_PARAMETER") index: Int) {
        applyAllBands()
    }

    private fun save() {
        val profileBands = bands.mapIndexed { i, band ->
            Pair(band.freq, gains[i])
        }

        val input = android.widget.EditText(this)
        input.hint = "My headphones"
        input.setPadding(48, 32, 48, 32)
        input.inputType = android.text.InputType.TYPE_CLASS_TEXT
        input.maxLines = 1
        input.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        if (editingProfileName != null) input.setText(editingProfileName)

        android.app.AlertDialog.Builder(this)
            .setTitle("Save profile")
            .setMessage(if (editingProfileName != null) "Save as the same name to overwrite, or enter a new name" else "Give your custom profile a name")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().takeIf { it.isNotBlank() } ?: "Custom"
                val prefs = PrefsManager(this)

                // Add as a preset if it's new (different name or no existing preset)
                if (prefs.getPresets().none { it.headphoneName == name }) {
                    prefs.addPreset(Preset(name, prefs.totalSteps))
                }

                prefs.soundProfile = name
                SoundProfileManager(this).saveCustomProfile(name, profileBands)
                volumeController.stopCalibration()

                val intent = android.content.Intent(this, AudioService::class.java)
                intent.action = AudioService.ACTION_APPLY_PROFILE
                startService(intent)
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroy() {
        if (volumeController.isCalibrating()) {
            volumeController.stopCalibration()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PROFILE_NAME = "profile_name"
    }
}
