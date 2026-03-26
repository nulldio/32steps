package com.thirtytwo.steps

import android.media.audiofx.DynamicsProcessing
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class CalibrationActivity : AppCompatActivity() {

    data class Band(val name: String, val freq: Int, val range: Float)

    private val bands = listOf(
        Band("Sub Bass", 31, 6f),
        Band("Bass", 62, 8f),
        Band("Upper Bass", 125, 6f),
        Band("Low Mid", 250, 6f),
        Band("Mid", 500, 5f),
        Band("Upper Mid", 1000, 5f),
        Band("Presence", 2000, 5f),
        Band("Upper Presence", 4000, 6f),
        Band("Treble", 8000, 6f),
        Band("Air", 16000, 7f)
    )

    // Harman target baseline - 10 bands
    private val harmanBaseline = floatArrayOf(
        4.0f, 3.5f, 2.0f, 1.0f, 0.5f, 0.0f, -0.5f, -1.0f, -2.0f, -3.5f
    )

    private var currentBandIndex = 0
    private var currentIteration = 0
    private var currentPass = 1 // 1 = first pass (4 iterations), 2 = refinement (2 iterations)
    private val iterationsFirstPass = 4
    private val iterationsSecondPass = 2
    private val iterationsPerBand get() = if (currentPass == 1) iterationsFirstPass else iterationsSecondPass
    private val results = FloatArray(10) { harmanBaseline[it] }

    private var searchLow = 0f
    private var searchHigh = 0f
    private var gainA = 0f
    private var gainB = 0f
    private var aIsLower = true

    private var dp: DynamicsProcessing? = null
    private lateinit var volumeController: VolumeController
    private var currentChoice: String? = null
    private var inFinalComparison = false
    private var editingProfileName: String? = null

    private data class BandState(val bandIndex: Int, val low: Float, val high: Float, val iteration: Int)
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

        volumeController = VolumeController.getInstance(this)
        dp = volumeController.startCalibration()

        // Check if editing an existing profile
        editingProfileName = intent.getStringExtra(EXTRA_PROFILE_NAME)

        // Load baseline: editing profile > active profile > Harman
        loadBaseline()

        if (dp == null) {
            titleText.text = "Error"
            instructionText.text = "Could not start calibration. Make sure the service is running."
            btnA.visibility = View.GONE
            btnB.visibility = View.GONE
            btnSkip.visibility = View.GONE
            btnNoDiff.visibility = View.GONE
            return
        }

        initializeBands()
        showInstructions()
    }

    private var baseline = harmanBaseline.clone()


    private fun loadBaseline() {
        val profileManager = SoundProfileManager(this)

        // Priority: editing profile > active profile > Harman
        val profileName = editingProfileName
            ?: PrefsManager(this).soundProfile
            ?: return

        val profile = profileManager.findProfile(profileName) ?: return

        // Map profile bands to our calibration bands by closest frequency
        for (i in bands.indices) {
            val targetFreq = bands[i].freq
            val closest = profile.bands.minByOrNull { kotlin.math.abs(it.first - targetFreq) }
            if (closest != null) {
                baseline[i] = closest.second
                results[i] = closest.second
            }
        }
    }

    private fun initializeBands() {
        try {
            val preEq = dp?.getPreEqByChannelIndex(0)
            preEq?.isEnabled = true
            dp?.setPreEqAllChannelsTo(preEq!!)

            for (i in bands.indices) {
                val band = dp?.getPreEqBandByChannelIndex(0, i)
                band?.isEnabled = true
                band?.cutoffFrequency = bands[i].freq.toFloat()
                band?.gain = baseline[i]
                dp?.setPreEqBandAllChannelsTo(i, band!!)
            }
            dp?.setInputGainAllChannelsTo(0f)
        } catch (_: Exception) {}
    }

    private fun showInstructions() {
        titleText.text = "Listening Test"
        bandText.text = ""
        instructionText.text = "Play some music with varied content (vocals, bass, instruments). " +
                "For each frequency band, tap A and B to hear two options, then tap your choice again to confirm."
        btnA.text = "Start"
        btnA.alpha = 1f
        btnB.visibility = View.GONE
        btnSkip.visibility = View.GONE
        btnNoDiff.visibility = View.GONE
        btnBack.visibility = View.GONE
        progress.progress = 0

        btnA.setOnClickListener {
            btnA.text = "A"
            btnB.visibility = View.VISIBLE
            btnSkip.visibility = View.VISIBLE
            btnNoDiff.visibility = View.VISIBLE
            setupButtons()
            startBand()
        }
    }

    private fun setupButtons() {
        btnA.setOnClickListener {
            if (inFinalComparison) {
                applyAllResults()
                currentChoice = "A"
                highlightButton("A")
                return@setOnClickListener
            }
            if (currentChoice == "A") {
                if (aIsLower) pickLower() else pickHigher()
            } else {
                applyTestGain(gainA)
                currentChoice = "A"
                highlightButton("A")
            }
        }

        btnB.setOnClickListener {
            if (inFinalComparison) {
                applyFlat()
                currentChoice = "B"
                highlightButton("B")
                return@setOnClickListener
            }
            if (currentChoice == "B") {
                if (aIsLower) pickHigher() else pickLower()
            } else {
                applyTestGain(gainB)
                currentChoice = "B"
                highlightButton("B")
            }
        }

        btnSkip.setOnClickListener {
            if (inFinalComparison) {
                finishCalibration()
                return@setOnClickListener
            }
            results[currentBandIndex] = baseline[currentBandIndex]
            applyBand(currentBandIndex, results[currentBandIndex])
            nextBand()
        }

        btnNoDiff.setOnClickListener {
            if (inFinalComparison) {
                finishCalibration()
                return@setOnClickListener
            }
            results[currentBandIndex] = (searchLow + searchHigh) / 2
            applyBand(currentBandIndex, results[currentBandIndex])
            nextBand()
        }

        btnBack.setOnClickListener {
            if (inFinalComparison) {
                // Go back to last band
                inFinalComparison = false
                currentBandIndex = bands.size - 1
                startBand()
                return@setOnClickListener
            }
            if (history.isNotEmpty()) {
                val prev = history.removeAt(history.size - 1)
                currentBandIndex = prev.bandIndex
                searchLow = prev.low
                searchHigh = prev.high
                currentIteration = prev.iteration
                prepareComparison()
                updateUI()
            }
        }
    }

    private fun startBand() {
        val band = bands[currentBandIndex]
        if (currentPass == 1) {
            val base = baseline[currentBandIndex]
            searchLow = base - band.range / 2
            searchHigh = base + band.range / 2
        } else {
            // Refinement pass - narrow range centered on first pass result
            val base = results[currentBandIndex]
            val refinementRange = band.range / 3
            searchLow = base - refinementRange / 2
            searchHigh = base + refinementRange / 2
        }
        currentIteration = 0
        currentChoice = null
        btnBack.visibility = if (history.isNotEmpty()) View.VISIBLE else View.GONE
        prepareComparison()
        updateUI()
    }

    private fun prepareComparison() {
        val mid = (searchLow + searchHigh) / 2
        val step = (searchHigh - searchLow) / 4

        aIsLower = Math.random() > 0.5
        if (aIsLower) {
            gainA = mid - step
            gainB = mid + step
        } else {
            gainA = mid + step
            gainB = mid - step
        }

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
        history.add(BandState(currentBandIndex, searchLow, searchHigh, currentIteration))
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
            if (currentPass == 1) {
                // Start refinement pass
                currentPass = 2
                currentBandIndex = 0
                startBand()
            } else {
                showFinalComparison()
            }
        } else {
            startBand()
        }
    }

    private fun showFinalComparison() {
        inFinalComparison = true
        currentChoice = null

        titleText.text = "Final Check"
        bandText.text = ""
        instructionText.text = "A = your calibrated profile, B = no correction. " +
                "Pick which sounds better, or tap Save to keep your profile."
        btnA.text = "A"
        btnB.text = "B"
        btnSkip.text = "Save"
        btnNoDiff.text = "Redo all"
        btnBack.visibility = View.VISIBLE
        btnBack.text = "Redo"
        highlightButton(null)
        progress.progress = 100

        applyAllResults()

        btnNoDiff.setOnClickListener {
            // Redo entire test
            inFinalComparison = false
            currentBandIndex = 0
            currentPass = 1
            history.clear()
            for (i in results.indices) results[i] = baseline[i]
            initializeBands()
            btnSkip.text = "Skip"
            btnNoDiff.text = "No difference"
            btnBack.text = "Redo"
            setupButtons()
            startBand()
        }

        btnSkip.setOnClickListener {
            finishCalibration()
        }
    }

    private fun applyAllResults() {
        for (i in bands.indices) {
            applyBand(i, results[i])
        }
        dp?.setInputGainAllChannelsTo(0f)
    }

    private fun applyFlat() {
        for (i in bands.indices) {
            applyBand(i, 0f)
        }
        dp?.setInputGainAllChannelsTo(0f)
    }

    private fun applyTestGain(gain: Float) {
        try {
            val band = dp?.getPreEqBandByChannelIndex(0, currentBandIndex)
            band?.gain = gain
            dp?.setPreEqBandAllChannelsTo(currentBandIndex, band!!)

            // Loudness compensation
            val baseline = results[currentBandIndex]
            val delta = gain - baseline
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
            dp?.setInputGainAllChannelsTo(0f)
        } catch (_: Exception) {}
    }

    private fun highlightButton(which: String?) {
        btnA.alpha = when (which) {
            "A" -> 1f; "B" -> 0.4f; else -> 0.7f
        }
        btnB.alpha = when (which) {
            "B" -> 1f; "A" -> 0.4f; else -> 0.7f
        }
    }

    private fun updateUI() {
        val band = bands[currentBandIndex]
        val passLabel = if (currentPass == 1) "Calibrating" else "Refining"
        titleText.text = passLabel
        bandText.text = "${band.name} (${currentBandIndex + 1}/${bands.size})"
        instructionText.text = if (currentPass == 1)
            "Tap A and B to compare, then tap your choice again to confirm"
        else
            "Fine-tuning for band interaction. Compare and confirm."
        btnBack.visibility = if (history.isNotEmpty()) View.VISIBLE else View.GONE

        val totalFirstPass = bands.size * iterationsFirstPass
        val totalSecondPass = bands.size * iterationsSecondPass
        val totalSteps = totalFirstPass + totalSecondPass
        val currentStep = if (currentPass == 1) {
            currentBandIndex * iterationsFirstPass + currentIteration
        } else {
            totalFirstPass + currentBandIndex * iterationsSecondPass + currentIteration
        }
        progress.progress = (currentStep * 100) / totalSteps
    }

    private fun finishCalibration() {
        applyAllResults()
        volumeController.stopCalibration()

        val profileBands = bands.mapIndexed { i, band ->
            Pair(band.freq, results[i])
        }

        if (editingProfileName != null) {
            // Editing existing - save directly
            SoundProfileManager(this).saveCustomProfile(editingProfileName!!, profileBands)
            val prefs = PrefsManager(this)
            prefs.soundProfile = editingProfileName

            val intent = android.content.Intent(this, AudioService::class.java)
            intent.action = AudioService.ACTION_APPLY_PROFILE
            startService(intent)

            finish()
        } else {
            // New profile - ask for name
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

                    SoundProfileManager(this).saveCustomProfile(name, profileBands)

                    val intent = android.content.Intent(this, AudioService::class.java)
                    intent.action = AudioService.ACTION_APPLY_PROFILE
                    startService(intent)

                    finish()
                }
                .setNegativeButton("Cancel") { _, _ -> finish() }
                .setCancelable(false)
                .show()
        }
    }

    companion object {
        const val EXTRA_PROFILE_NAME = "profile_name"
    }

    override fun onDestroy() {
        if (volumeController.isCalibrating()) {
            volumeController.stopCalibration()
        }
        super.onDestroy()
    }
}
