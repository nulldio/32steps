package com.thirtytwo.steps

import android.media.AudioManager
import android.media.audiofx.DynamicsProcessing
import android.os.Bundle
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.abs

class CalibrationActivity : AppCompatActivity() {

    data class Band(val name: String, val freq: Int, val range: Float)

    // Bands with custom ranges - bass needs more, treble needs less
    private val bands = listOf(
        Band("Bass", 60, 8f),
        Band("Low Mid", 250, 6f),
        Band("Mid", 1000, 5f),
        Band("Upper Mid", 2500, 5f),
        Band("Presence", 4000, 6f),
        Band("Treble", 8000, 6f),
        Band("Air", 16000, 7f)
    )

    // Harman target baseline - slight bass boost, mild treble roll-off
    private val harmanBaseline = floatArrayOf(
        3.5f,   // 60Hz - bass boost
        1.0f,   // 250Hz - slight low-mid boost
        0.0f,   // 1kHz - flat reference
        -0.5f,  // 2.5kHz - slight dip
        -1.0f,  // 4kHz - presence dip
        -2.0f,  // 8kHz - treble roll-off
        -3.5f   // 16kHz - air roll-off
    )

    private var currentBandIndex = 0
    private var currentIteration = 0
    private val iterationsPerBand = 4
    private val results = FloatArray(7) { harmanBaseline[it] } // start from Harman

    // Binary search state
    private var searchLow = 0f
    private var searchHigh = 0f
    private var gainA = 0f
    private var gainB = 0f
    private var aIsLower = true // tracks randomization

    // Audio effect
    private var dp: DynamicsProcessing? = null
    private var currentChoice: String? = null

    // History for redo
    private data class BandState(val low: Float, val high: Float, val iteration: Int)
    private val history = mutableListOf<BandState>()

    private lateinit var titleText: TextView
    private lateinit var bandText: TextView
    private lateinit var instructionText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var btnA: Button
    private lateinit var btnB: Button
    private lateinit var btnSkip: Button
    private lateinit var btnNoDiff: Button
    private lateinit var btnBack: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calibration)

        titleText = findViewById(R.id.calibration_title)
        bandText = findViewById(R.id.calibration_band)
        instructionText = findViewById(R.id.calibration_instruction)
        progress = findViewById(R.id.calibration_progress)
        btnA = findViewById(R.id.btn_a)
        btnB = findViewById(R.id.btn_b)
        btnSkip = findViewById(R.id.btn_skip)
        btnNoDiff = findViewById(R.id.btn_no_diff)
        btnBack = findViewById(R.id.btn_back)

        setupDynamicsProcessing()
        applyAllBands() // apply Harman baseline
        showInstructions()
    }

    private fun showInstructions() {
        titleText.text = "Listening Test"
        bandText.text = ""
        instructionText.text = "Play some music with varied content (vocals, bass, instruments). " +
                "For each frequency band, tap A and B to hear two options, then pick which sounds better. " +
                "Tap the selected button again to confirm your choice."
        btnA.text = "Start"
        btnA.alpha = 1f
        btnB.visibility = android.view.View.GONE
        btnSkip.visibility = android.view.View.GONE
        btnNoDiff.visibility = android.view.View.GONE
        btnBack.visibility = android.view.View.GONE
        progress.progress = 0

        btnA.setOnClickListener {
            btnA.text = "A"
            btnB.visibility = android.view.View.VISIBLE
            btnSkip.visibility = android.view.View.VISIBLE
            btnNoDiff.visibility = android.view.View.VISIBLE
            setupButtons()
            startBand()
        }
    }

    private fun setupButtons() {
        btnA.setOnClickListener {
            if (currentChoice == "A") {
                // Confirm pick A
                if (aIsLower) pickLower() else pickHigher()
            } else {
                applyTestGain(gainA)
                currentChoice = "A"
                highlightButton("A")
            }
        }

        btnB.setOnClickListener {
            if (currentChoice == "B") {
                // Confirm pick B
                if (aIsLower) pickHigher() else pickLower()
            } else {
                applyTestGain(gainB)
                currentChoice = "B"
                highlightButton("B")
            }
        }

        btnSkip.setOnClickListener {
            results[currentBandIndex] = harmanBaseline[currentBandIndex]
            applyBand(currentBandIndex, results[currentBandIndex])
            nextBand()
        }

        btnNoDiff.setOnClickListener {
            // No difference heard - keep current midpoint and move on
            results[currentBandIndex] = (searchLow + searchHigh) / 2
            applyBand(currentBandIndex, results[currentBandIndex])
            nextBand()
        }

        btnBack.setOnClickListener {
            if (history.isNotEmpty()) {
                val prev = history.removeAt(history.size - 1)
                if (currentBandIndex > 0 && prev.iteration == 0) {
                    currentBandIndex--
                }
                searchLow = prev.low
                searchHigh = prev.high
                currentIteration = prev.iteration
                prepareComparison()
                updateUI()
            }
        }
    }

    private fun setupDynamicsProcessing() {
        try {
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                1, true, 7, false, 0, false, 0, false
            ).build()
            dp = DynamicsProcessing(0, 0, config)
            dp?.enabled = true

            val preEq = dp?.getPreEqByChannelIndex(0)
            preEq?.isEnabled = true
            dp?.setPreEqAllChannelsTo(preEq!!)
        } catch (_: Exception) {}
    }

    private fun applyAllBands() {
        for (i in bands.indices) {
            applyBand(i, results[i])
        }
    }

    private fun startBand() {
        val band = bands[currentBandIndex]
        val baseline = harmanBaseline[currentBandIndex]
        searchLow = baseline - band.range / 2
        searchHigh = baseline + band.range / 2
        currentIteration = 0
        currentChoice = null
        btnBack.visibility = if (history.isNotEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        prepareComparison()
        updateUI()
    }

    private fun prepareComparison() {
        val mid = (searchLow + searchHigh) / 2
        val step = (searchHigh - searchLow) / 4

        // Randomize A/B assignment
        aIsLower = Math.random() > 0.5
        if (aIsLower) {
            gainA = mid - step
            gainB = mid + step
        } else {
            gainA = mid + step
            gainB = mid - step
        }

        // Reset to current result (no test applied)
        applyBand(currentBandIndex, results[currentBandIndex])
        currentChoice = null
        highlightButton(null)
    }

    private fun pickLower() {
        saveHistory()
        searchHigh = (searchLow + searchHigh) / 2
        advance()
    }

    private fun pickHigher() {
        saveHistory()
        searchLow = (searchLow + searchHigh) / 2
        advance()
    }

    private fun saveHistory() {
        history.add(BandState(searchLow, searchHigh, currentIteration))
    }

    private fun advance() {
        currentIteration++
        if (currentIteration >= iterationsPerBand) {
            results[currentBandIndex] = (searchLow + searchHigh) / 2
            applyBand(currentBandIndex, results[currentBandIndex])
            nextBand()
        } else {
            prepareComparison()
            updateUI()
        }
    }

    private fun nextBand() {
        currentBandIndex++
        if (currentBandIndex >= bands.size) {
            finishCalibration()
        } else {
            startBand()
        }
    }

    /**
     * Apply test gain with loudness compensation.
     * When boosting, reduce overall volume so the user judges
     * tonal quality, not just loudness.
     */
    private fun applyTestGain(gain: Float) {
        try {
            // Apply the test gain on current band
            val band = dp?.getPreEqBandByChannelIndex(0, currentBandIndex)
            band?.gain = gain
            dp?.setPreEqBandAllChannelsTo(currentBandIndex, band!!)

            // Loudness compensation: offset inputGain by the change from baseline
            val baseline = results[currentBandIndex]
            val delta = gain - baseline
            // Compensate by reducing input gain proportional to the boost
            // Use a fraction since one band boost doesn't equal overall loudness change
            val compensation = -delta * 0.3f
            dp?.setInputGainAllChannelsTo(compensation)
        } catch (_: Exception) {}
    }

    private fun applyBand(bandIndex: Int, gain: Float) {
        try {
            val band = dp?.getPreEqBandByChannelIndex(0, bandIndex)
            band?.isEnabled = true
            band?.cutoffFrequency = bands[bandIndex].freq.toFloat()
            band?.gain = gain
            dp?.setPreEqBandAllChannelsTo(bandIndex, band!!)
            // Reset input gain compensation
            dp?.setInputGainAllChannelsTo(0f)
        } catch (_: Exception) {}
    }

    private fun highlightButton(which: String?) {
        btnA.alpha = when (which) {
            "A" -> 1f
            "B" -> 0.4f
            else -> 0.7f
        }
        btnB.alpha = when (which) {
            "B" -> 1f
            "A" -> 0.4f
            else -> 0.7f
        }
    }

    private fun updateUI() {
        val band = bands[currentBandIndex]
        titleText.text = "Calibrating"
        bandText.text = "${band.name} (${currentBandIndex + 1}/${bands.size})"
        instructionText.text = "Tap A and B to compare, then tap your choice again to confirm"
        btnBack.visibility = if (history.isNotEmpty()) android.view.View.VISIBLE else android.view.View.GONE

        val totalSteps = bands.size * iterationsPerBand
        val currentStep = currentBandIndex * iterationsPerBand + currentIteration
        progress.progress = (currentStep * 100) / totalSteps
    }

    private fun finishCalibration() {
        dp?.setInputGainAllChannelsTo(0f)
        dp?.enabled = false
        dp?.release()
        dp = null

        val profileBands = bands.mapIndexed { i, band ->
            Pair(band.freq, results[i])
        }

        val input = android.widget.EditText(this)
        input.hint = "My headphones"
        input.setPadding(48, 32, 48, 32)

        android.app.AlertDialog.Builder(this)
            .setTitle("Save profile")
            .setMessage("Give your custom profile a name")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().takeIf { it.isNotBlank() } ?: "Custom"
                val prefs = PrefsManager(this)
                val preset = Preset(name, prefs.totalSteps)
                prefs.addPreset(preset)
                prefs.soundProfile = name

                val profileManager = SoundProfileManager(this)
                profileManager.saveCustomProfile(name, profileBands)

                val intent = android.content.Intent(this, AudioService::class.java)
                intent.action = AudioService.ACTION_APPLY_PROFILE
                startService(intent)

                finish()
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    override fun onDestroy() {
        dp?.setInputGainAllChannelsTo(0f)
        dp?.enabled = false
        dp?.release()
        dp = null
        super.onDestroy()
    }
}
