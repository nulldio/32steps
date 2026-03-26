package com.thirtytwo.steps

import android.media.AudioManager
import android.media.audiofx.DynamicsProcessing
import android.os.Bundle
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class CalibrationActivity : AppCompatActivity() {

    // Frequency bands to calibrate
    private data class Band(val name: String, val freq: Int)

    private val bands = listOf(
        Band("Bass", 60),
        Band("Low Mid", 250),
        Band("Mid", 1000),
        Band("Upper Mid", 2500),
        Band("Presence", 4000),
        Band("Treble", 8000),
        Band("Air", 16000)
    )

    private var currentBandIndex = 0
    private var currentIteration = 0
    private val iterationsPerBand = 4
    private val results = FloatArray(7) // final gain per band

    // Binary search state per band
    private var searchLow = -6f
    private var searchHigh = 6f
    private var gainA = 0f
    private var gainB = 0f

    // Audio effect
    private var dp: DynamicsProcessing? = null
    private var currentChoice: String? = null // "A" or "B" currently playing

    private lateinit var titleText: TextView
    private lateinit var bandText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var btnA: Button
    private lateinit var btnB: Button
    private lateinit var btnSkip: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calibration)

        titleText = findViewById(R.id.calibration_title)
        bandText = findViewById(R.id.calibration_band)
        progress = findViewById(R.id.calibration_progress)
        btnA = findViewById(R.id.btn_a)
        btnB = findViewById(R.id.btn_b)
        btnSkip = findViewById(R.id.btn_skip)

        setupDynamicsProcessing()
        startBand()

        btnA.setOnClickListener {
            if (currentChoice == "A") {
                // Already listening to A, this is their pick
                pickA()
            } else {
                // Switch to A
                applyTestEq(gainA)
                currentChoice = "A"
                highlightButton("A")
            }
        }

        btnB.setOnClickListener {
            if (currentChoice == "B") {
                pickB()
            } else {
                applyTestEq(gainB)
                currentChoice = "B"
                highlightButton("B")
            }
        }

        btnSkip.setOnClickListener {
            results[currentBandIndex] = 0f // no correction for skipped band
            nextBand()
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

            // Enable pre-EQ
            val preEq = dp?.getPreEqByChannelIndex(0)
            preEq?.isEnabled = true
            dp?.setPreEqAllChannelsTo(preEq!!)

            // Initialize all bands to 0
            for (i in bands.indices) {
                val band = dp?.getPreEqBandByChannelIndex(0, i)
                band?.isEnabled = true
                band?.cutoffFrequency = bands[i].freq.toFloat()
                band?.gain = 0f
                dp?.setPreEqBandAllChannelsTo(i, band!!)
            }
        } catch (_: Exception) {}
    }

    private fun startBand() {
        searchLow = -6f
        searchHigh = 6f
        currentIteration = 0
        currentChoice = null
        prepareComparison()
        updateUI()
    }

    private fun prepareComparison() {
        val mid = (searchLow + searchHigh) / 2
        val step = (searchHigh - searchLow) / 4
        gainA = mid - step
        gainB = mid + step
        // Start with neither selected
        applyTestEq(0f)
        currentChoice = null
        highlightButton(null)
    }

    private fun pickA() {
        // User prefers lower gain - narrow search to lower half
        searchHigh = (searchLow + searchHigh) / 2
        advance()
    }

    private fun pickB() {
        // User prefers higher gain - narrow search to upper half
        searchLow = (searchLow + searchHigh) / 2
        advance()
    }

    private fun advance() {
        currentIteration++
        if (currentIteration >= iterationsPerBand) {
            // Done with this band - save result
            results[currentBandIndex] = (searchLow + searchHigh) / 2
            applyResult(currentBandIndex, results[currentBandIndex])
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

    private fun applyTestEq(gain: Float) {
        try {
            val band = dp?.getPreEqBandByChannelIndex(0, currentBandIndex)
            band?.gain = gain
            dp?.setPreEqBandAllChannelsTo(currentBandIndex, band!!)
        } catch (_: Exception) {}
    }

    private fun applyResult(bandIndex: Int, gain: Float) {
        try {
            val band = dp?.getPreEqBandByChannelIndex(0, bandIndex)
            band?.gain = gain
            dp?.setPreEqBandAllChannelsTo(bandIndex, band!!)
        } catch (_: Exception) {}
    }

    private fun highlightButton(which: String?) {
        btnA.alpha = if (which == "A") 1f else 0.5f
        btnB.alpha = if (which == "B") 1f else 0.5f
    }

    private fun updateUI() {
        val band = bands[currentBandIndex]
        bandText.text = "${band.name} (${currentBandIndex + 1}/${bands.size})"

        val totalSteps = bands.size * iterationsPerBand
        val currentStep = currentBandIndex * iterationsPerBand + currentIteration
        progress.progress = (currentStep * 100) / totalSteps
    }

    private fun finishCalibration() {
        // Release test EQ
        dp?.enabled = false
        dp?.release()
        dp = null

        // Build the band list for HeadphoneProfile format
        val profileBands = bands.mapIndexed { i, band ->
            Pair(band.freq, results[i])
        }

        // Ask for a name
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

                // Save the custom EQ data
                val profileManager = SoundProfileManager(this)
                profileManager.saveCustomProfile(name, profileBands)

                // Apply via AudioService
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
        dp?.enabled = false
        dp?.release()
        dp = null
        super.onDestroy()
    }
}
