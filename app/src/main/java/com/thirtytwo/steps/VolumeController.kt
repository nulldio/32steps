package com.thirtytwo.steps

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.EnvironmentalReverb
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

class VolumeController(private val context: Context) {

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val prefs = PrefsManager(context)
    private val handler = Handler(Looper.getMainLooper())

    private val dynamicsProcessors = mutableMapOf<Int, DynamicsProcessing>()
    private val equalizers = mutableMapOf<Int, Equalizer>()
    private val virtualizers = mutableMapOf<Int, Virtualizer>()
    private val reverbs = mutableMapOf<Int, EnvironmentalReverb>()
    private val sessionPackages = mutableMapOf<Int, String>()
    private val caps = DeviceCapabilities.getInstance(context)
    private var useDynamicsProcessing = true // always try DP first, set false only on failure

    val systemMax: Int = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    val totalSteps: Int get() = prefs.totalSteps

    var currentStep: Int
        get() = prefs.currentStep
        private set(value) { prefs.currentStep = value }

    // Per-level dB gaps (measured individually, not averaged)
    private val perLevelMb: IntArray by lazy { measurePerLevelMb() }
    // Pre-computed lookup table: stepTable[step] = Pair(systemLevel, gainOffsetMb)
    private var stepTable: Array<Pair<Int, Int>> = emptyArray()
    private var lastSystemVol = -1
    @Volatile private var selfChanging = false

    // Equal loudness performance: only recalculate at 5% volume boundaries
    private var lastLoudnessFraction = -1f

    // Listeners

    private val stepListeners = mutableListOf<(step: Int, total: Int) -> Unit>()

    fun addStepListener(listener: (step: Int, total: Int) -> Unit) {
        stepListeners.add(listener)
    }

    fun removeStepListener(listener: (step: Int, total: Int) -> Unit) {
        stepListeners.remove(listener)
    }

    private fun notifyStepChanged() {
        for (listener in stepListeners) {
            listener(currentStep, totalSteps)
        }
    }

    // Volume observer

    private val volumeObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            if (selfChanging) return
            val stream = activeStream()
            if (stream != AudioManager.STREAM_MUSIC) return
            val sysVol = audioManager.getStreamVolume(stream)
            if (sysVol != lastSystemVol) {
                lastSystemVol = sysVol
                if (sysVol == 0) {
                    currentStep = 0
                } else {
                    val fraction = (sysVol.toFloat() - 1) / (systemMax - 1).coerceAtLeast(1)
                    currentStep = (fraction * totalSteps).roundToInt().coerceIn(1, totalSteps)
                }
                setAllGain(gainOffsetForStep(currentStep))
                updateEqualLoudnessIfNeeded()
                notifyStepChanged()
            }
        }
    }

    fun startObserving() {
        context.contentResolver.registerContentObserver(
            Settings.System.CONTENT_URI, true, volumeObserver
        )
    }

    fun stopObserving() {
        context.contentResolver.unregisterContentObserver(volumeObserver)
    }

    // Session management

    fun attachSession(sessionId: Int, packageName: String? = null) {
        if (dynamicsProcessors.containsKey(sessionId) ||
            equalizers.containsKey(sessionId)
        ) return

        if (packageName != null) {
            sessionPackages[sessionId] = packageName
            val apps = prefs.recentAudioApps.toMutableSet()
            apps.add(packageName)
            prefs.recentAudioApps = apps
        }

        if (useDynamicsProcessing) {
            try {
                val probed = caps.probed
                val channels = if (probed && caps.hasStereoDP) 2 else 1
                val hasLimiter = probed && caps.hasLimiter

                val config = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    channels,
                    true,                   // pre-EQ (always — profiles + EQ)
                    PRE_EQ_BAND_COUNT,
                    false,                  // MBC off for now
                    0,
                    false,                  // post-EQ off for now
                    0,
                    hasLimiter              // limiter always available (for balance + future use)
                ).build()
                val dp = DynamicsProcessing(Int.MAX_VALUE, sessionId, config)
                dp.enabled = true
                dynamicsProcessors[sessionId] = dp
                recomputeUserEq()
                applyDpGain(dp, gainOffsetForStep(currentStep))
                applyPreEq(dp, sessionId)
                applyBassTuner(dp)
                applyBassMbc(dp)
                applyLimiter(dp)
            } catch (_: Exception) {
                useDynamicsProcessing = false
            }
        }

        if (!useDynamicsProcessing) {
            try {
                val eq = Equalizer(Int.MAX_VALUE, sessionId)
                eq.enabled = true
                equalizers[sessionId] = eq
                applyEqGain(eq, gainOffsetForStep(currentStep))
            } catch (_: Exception) {}
        }

        // Virtualizer / Crossfeed
        if (caps.hasVirtualizer) {
            try {
                val v = Virtualizer(Int.MAX_VALUE, sessionId)
                virtualizers[sessionId] = v
                applyVirtualizerState()
            } catch (_: Exception) {}
        }

        // Reverb
        if (caps.hasReverb) {
            try {
                val r = EnvironmentalReverb(Int.MAX_VALUE, sessionId)
                applyReverbParams(r)
                r.enabled = prefs.reverbEnabled
                reverbs[sessionId] = r
            } catch (_: Exception) {}
        }
    }

    fun detachSession(sessionId: Int) {
        dynamicsProcessors.remove(sessionId)?.apply { enabled = false; release() }
        equalizers.remove(sessionId)?.apply { enabled = false; release() }
        virtualizers.remove(sessionId)?.apply { enabled = false; release() }
        reverbs.remove(sessionId)?.apply { enabled = false; release() }
        sessionPackages.remove(sessionId)
    }

    // Volume control

    private fun activeStream(): Int = when (audioManager.mode) {
        AudioManager.MODE_IN_CALL,
        AudioManager.MODE_IN_COMMUNICATION -> AudioManager.STREAM_VOICE_CALL
        AudioManager.MODE_RINGTONE -> AudioManager.STREAM_RING
        else -> AudioManager.STREAM_MUSIC
    }

    fun stepUp(): Int {
        val stream = activeStream()
        if (stream != AudioManager.STREAM_MUSIC) {
            val current = audioManager.getStreamVolume(stream)
            val max = audioManager.getStreamMaxVolume(stream)
            val newVol = (current + 1).coerceAtMost(max)
            setSystemVolume(stream, newVol)
            currentStep = (newVol.toFloat() / max * totalSteps).roundToInt().coerceIn(0, totalSteps)
            notifyStepChanged()
            return currentStep
        }
        setStep(currentStep + 1)
        return currentStep
    }

    fun stepDown(): Int {
        val stream = activeStream()
        if (stream != AudioManager.STREAM_MUSIC) {
            val current = audioManager.getStreamVolume(stream)
            val max = audioManager.getStreamMaxVolume(stream)
            val newVol = (current - 1).coerceAtLeast(0)
            setSystemVolume(stream, newVol)
            currentStep = (newVol.toFloat() / max * totalSteps).roundToInt().coerceIn(0, totalSteps)
            notifyStepChanged()
            return currentStep
        }
        setStep(currentStep - 1)
        return currentStep
    }

    fun setStep(step: Int) {
        val newStep = step.coerceIn(0, totalSteps)
        currentStep = newStep
        val stream = activeStream()

        if (newStep == 0) {
            setSystemVolume(stream, 0)
            setAllGain(0)
            notifyStepChanged()
            return
        }

        ensureStepTable()
        val (targetVol, gainOffset) = if (newStep < stepTable.size) stepTable[newStep]
            else computeMappingFallback(newStep)
        val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

        if (targetVol != currentVol) {
            // Smooth ramp: pre-compensate, change system vol, ramp to final gain
            val currentGainMb = if (currentStep > 0 && currentStep < stepTable.size)
                stepTable[currentStep].second else gainOffset
            // Get the actual dB gap for this specific boundary
            val boundaryMb = if (targetVol > 0 && targetVol <= perLevelMb.size)
                perLevelMb[(targetVol - 1).coerceIn(0, perLevelMb.size - 1)]
            else 300

            // Phase 1: pre-attenuate to compensate for upcoming system volume change
            val direction = if (targetVol > currentVol) 1 else -1
            val preGain = gainOffset + (direction * boundaryMb)
            setAllGain(preGain)

            // Phase 2: change system volume (gain compensates so net level stays same)
            handler.postDelayed({
                setSystemVolume(AudioManager.STREAM_MUSIC, targetVol)
            }, 5)

            // Phase 3-6: ramp to final gain over 60ms in 4 steps
            val rampSteps = 4
            val rampInterval = 15L
            for (i in 1..rampSteps) {
                val progress = i.toFloat() / rampSteps
                val rampGain = preGain + ((gainOffset - preGain) * progress).toInt()
                handler.postDelayed({ setAllGain(rampGain) }, 5 + (i * rampInterval))
            }
        } else {
            setAllGain(gainOffset)
        }

        updateEqualLoudnessIfNeeded()
        notifyStepChanged()
    }

    /** Only recalculates pre-EQ when volume crosses a 5% boundary */
    private fun updateEqualLoudnessIfNeeded() {
        if (!prefs.equalLoudnessEnabled) return
        val fraction = currentStep.toFloat() / totalSteps.coerceAtLeast(1)
        val quantized = (fraction * 20).toInt() / 20f
        if (quantized != lastLoudnessFraction) {
            lastLoudnessFraction = quantized
            for ((sid, dp) in dynamicsProcessors) applyPreEq(dp, sid)
        }
    }

    fun syncFromSystem() {
        val sysVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        lastSystemVol = sysVol
        if (sysVol == 0) {
            currentStep = 0
            setAllGain(0)
            return
        }
        val fraction = (sysVol.toFloat() - 1) / (systemMax - 1).coerceAtLeast(1)
        currentStep = (fraction * totalSteps).roundToInt().coerceIn(1, totalSteps)
        setAllGain(gainOffsetForStep(currentStep))
    }

    // ──────────────────────────────────────────────
    // Gold standard volume mapping
    // ──────────────────────────────────────────────

    /** Measure the actual dB gap for EACH individual system volume level */
    private fun measurePerLevelMb(): IntArray {
        if (systemMax <= 1) return intArrayOf(300)
        return IntArray(systemMax) { i ->
            if (i == 0) {
                // Level 0 to 1: large jump from silence
                600
            } else {
                val dbThis = 20.0 * ln(i.toDouble() / systemMax) / ln(10.0)
                val dbNext = 20.0 * ln((i + 1).coerceAtMost(systemMax).toDouble() / systemMax) / ln(10.0)
                val mb = ((dbNext - dbThis) * 100).toInt()
                if (mb <= 0) 300 else mb
            }
        }
    }

    /**
     * Build a pre-computed lookup table mapping each custom step to
     * (systemLevel, gainOffsetMb). Maximizes gain range within each
     * system level to minimize boundary crossings.
     */
    private fun ensureStepTable() {
        if (stepTable.size == totalSteps + 1) return
        buildStepTable()
    }

    private fun buildStepTable() {
        val steps = totalSteps
        if (steps <= 0 || systemMax <= 1) {
            stepTable = Array(steps + 1) { Pair(1, 0) }
            return
        }

        // Calculate total dB range from level 1 to systemMax
        val totalRangeMb = perLevelMb.sum()

        // Each custom step covers this much of the total range
        val mbPerCustomStep = totalRangeMb.toFloat() / steps

        val table = Array(steps + 1) { Pair(1, 0) }
        table[0] = Pair(0, 0) // step 0 = mute

        // Walk through custom steps, accumulating dB
        var accumulatedMb = 0f
        for (step in 1..steps) {
            accumulatedMb = step * mbPerCustomStep

            // Find which system level this falls in
            var sysLevel = 1
            var mbSoFar = 0f
            for (level in 1 until systemMax) {
                val levelMb = perLevelMb[level].toFloat()
                if (mbSoFar + levelMb >= accumulatedMb) {
                    sysLevel = level
                    break
                }
                mbSoFar += levelMb
                sysLevel = level + 1
            }
            sysLevel = sysLevel.coerceIn(1, systemMax)

            // Gain offset: how far into this system level's range we are
            // Negative = attenuate from the system level's natural volume
            val mbIntoLevel = accumulatedMb - mbSoFar
            val levelTotalMb = perLevelMb[sysLevel.coerceIn(0, perLevelMb.size - 1)].toFloat()
            val gainOffset = -(levelTotalMb - mbIntoLevel).toInt()

            table[step] = Pair(sysLevel, gainOffset)
        }

        stepTable = table
    }

    /** Fallback mapping if step table isn't ready */
    private fun computeMappingFallback(step: Int): Pair<Int, Int> {
        val fraction = step.toFloat() / totalSteps
        val floatSysVol = 1 + fraction * (systemMax - 1)
        val sysVol = ceil(floatSysVol).toInt().coerceIn(1, systemMax)
        val avgMb = if (perLevelMb.isNotEmpty()) perLevelMb.average().toInt() else 300
        val attenuation = sysVol - floatSysVol
        val gainOffset = -(attenuation * avgMb).toInt()
        return sysVol to gainOffset
    }

    private fun gainOffsetForStep(step: Int): Int {
        if (step <= 0) return 0
        ensureStepTable()
        return if (step < stepTable.size) stepTable[step].second
            else computeMappingFallback(step).second
    }

    // ──────────────────────────────────────────────
    // Pre-EQ: Sound profiles + Equal loudness
    // ──────────────────────────────────────────────

    private var activeProfile: HeadphoneProfile? = null
    private var profileManager: SoundProfileManager? = null
    private var userEqGains = FloatArray(PRE_EQ_BAND_COUNT)
    private var isPreviewingEq = false
    private var cachedPreampDb = prefs.preampDb

 // true when ParametricEqActivity is sending live gains

    private fun ensureProfileLoaded() {
        if (activeProfile == null) {
            val savedName = prefs.soundProfile ?: return
            if (profileManager == null) profileManager = SoundProfileManager(context)
            activeProfile = profileManager?.findProfile(savedName)
        }
    }

    fun hasSoundProfile(): Boolean {
        ensureProfileLoaded()
        return activeProfile != null
    }

    fun setSoundProfile(profile: HeadphoneProfile?) {
        activeProfile = profile
        for ((sid, dp) in dynamicsProcessors) applyPreEq(dp, sid)
    }

    /**
     * ISO 226:2003 equal loudness compensation at 10 anchor frequencies.
     * Differences between 40-phon and 80-phon contours, scaled 60%.
     * Interpolated to 31 bands at runtime via [computeLoudnessGains].
     */
    private val loudnessAnchors = listOf(
        31 to 10.8f, 62 to 7.2f, 125 to 4.8f, 250 to 3.6f, 500 to 1.2f,
        1000 to 0.0f, 2000 to -0.6f, 4000 to -1.2f, 8000 to 0.0f, 16000 to 8.4f
    )

    private val loudnessOffset by lazy {
        BiquadMath.interpolateToGrid(loudnessAnchors, PRE_EQ_FREQS)
    }

    /**
     * Unified pre-EQ: sums sound profile + equal loudness + user EQ (graphic or parametric).
     * Applied to all 31 third-octave bands. Auto-compensates input gain to prevent clipping.
     */
    private fun applyPreEq(dp: DynamicsProcessing, sessionId: Int = 0) {
        try {
            ensureProfileLoaded()
            val profile = activeProfile
            val equalLoudness = prefs.equalLoudnessEnabled

            // Per-app EQ: use app-specific gains if available
            val effectiveUserEq = getEffectiveUserEq(sessionId)
            val hasUserEq = effectiveUserEq.any { it != 0f }
            val hasContent = profile != null || equalLoudness || hasUserEq

            val preEq = dp.getPreEqByChannelIndex(0)
            preEq.isEnabled = hasContent
            dp.setPreEqAllChannelsTo(preEq)

            // Sound profile: interpolate to 31 bands
            val profileGains = if (profile != null) {
                BiquadMath.interpolateToGrid(profile.bands, PRE_EQ_FREQS)
            } else FloatArray(PRE_EQ_BAND_COUNT)

            // Equal loudness compensation
            val volumeFraction = if (totalSteps > 0) currentStep.toFloat() / totalSteps else 0.5f
            val loudnessGains = if (equalLoudness) {
                val thresholdDb = prefs.equalLoudnessThresholdDb
                val thresholdFrac = Math.pow(10.0, thresholdDb / 20.0).toFloat()
                val comp = if (volumeFraction >= thresholdFrac) 0f
                    else sqrt(((thresholdFrac - volumeFraction) / thresholdFrac).coerceIn(0f, 1f))
                FloatArray(PRE_EQ_BAND_COUNT) { if (it < loudnessOffset.size) loudnessOffset[it] * comp else 0f }
            } else FloatArray(PRE_EQ_BAND_COUNT)

            for (i in PRE_EQ_FREQS.indices) {
                val gain = (profileGains[i] + loudnessGains[i] + effectiveUserEq[i])
                    .coerceIn(-24f, 24f)
                val band = try { dp.getPreEqBandByChannelIndex(0, i) } catch (_: Exception) { break }
                band.isEnabled = hasContent
                band.cutoffFrequency = PRE_EQ_FREQS[i].toFloat()
                band.gain = gain
                dp.setPreEqBandAllChannelsTo(i, band)
            }
        } catch (_: Exception) {}
    }

    fun setEqualLoudness(enabled: Boolean, thresholdDb: Int? = null) {
        prefs.equalLoudnessEnabled = enabled
        if (thresholdDb != null) prefs.equalLoudnessThresholdDb = thresholdDb
        lastLoudnessFraction = -1f
        for ((sid, dp) in dynamicsProcessors) applyPreEq(dp, sid)
    }

    // User EQ: graphic or parametric mode

    fun setEqMode(mode: Int) {
        prefs.eqMode = mode
        recomputeUserEq()
        for ((sid, dp) in dynamicsProcessors) applyPreEq(dp, sid)
    }

    /** Called by ParametricEqActivity for real-time preview */
    fun setUserEqGains(gains: FloatArray) {
        isPreviewingEq = true
        userEqGains = if (gains.size == PRE_EQ_BAND_COUNT) gains.copyOf()
            else FloatArray(PRE_EQ_BAND_COUNT)
        for ((sid, dp) in dynamicsProcessors) applyPreEq(dp, sid)
    }

    /** End preview mode (called when activity saves or cancels) */
    fun endEqPreview() {
        isPreviewingEq = false
    }

    /** Recompute user EQ from saved prefs (called on startup / mode change) */
    private fun recomputeUserEq() {
        if (isPreviewingEq) return // don't overwrite live preview
        userEqGains = when (prefs.eqMode) {
            EQ_MODE_GRAPHIC -> {
                val g10 = prefs.graphicEqGains ?: FloatArray(10)
                interpolateGraphicTo31(g10)
            }
            EQ_MODE_PARAMETRIC -> {
                val bands = prefs.parametricBands.filter { it.enabled && it.gain != 0f }
                if (bands.isEmpty()) FloatArray(PRE_EQ_BAND_COUNT)
                else {
                    val coeffs = bands.map { b ->
                        BiquadMath.designFilter(b.type, b.frequency.toDouble(), b.gain.toDouble(), b.q.toDouble())
                    }
                    BiquadMath.evaluateChain(coeffs, PRE_EQ_FREQS)
                }
            }
            else -> FloatArray(PRE_EQ_BAND_COUNT)
        }
    }

    /** Get the effective user EQ for a session (per-app if mapped, otherwise global) */
    private fun getEffectiveUserEq(sessionId: Int): FloatArray {
        if (prefs.perAppEqEnabled) {
            val pkg = sessionPackages[sessionId]
            if (pkg != null) {
                val presetName = prefs.appEqMappings[pkg]
                if (presetName != null) {
                    val preset = prefs.getEqPresets().find { it.name == presetName }
                    if (preset != null) return computePresetGains(preset)
                }
            }
        }
        return userEqGains // global fallback
    }

    /** Compute 31-band gains from an EQ preset */
    private fun computePresetGains(preset: EqPreset): FloatArray {
        return when (preset.mode) {
            EQ_MODE_GRAPHIC -> {
                val g = preset.graphicGains ?: return FloatArray(PRE_EQ_BAND_COUNT)
                interpolateGraphicTo31(g.toFloatArray())
            }
            EQ_MODE_PARAMETRIC -> {
                val bands = preset.parametricBands?.filter { it.enabled && it.gain != 0f }
                    ?: return FloatArray(PRE_EQ_BAND_COUNT)
                val coeffs = bands.map { b ->
                    BiquadMath.designFilter(b.type, b.frequency.toDouble(), b.gain.toDouble(), b.q.toDouble())
                }
                BiquadMath.evaluateChain(coeffs, PRE_EQ_FREQS)
            }
            else -> FloatArray(PRE_EQ_BAND_COUNT)
        }
    }

    /** Interpolate 10 graphic EQ bands to 31 third-octave bands */
    private fun interpolateGraphicTo31(gains10: FloatArray): FloatArray {
        val graphicFreqs = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
        val pairs = graphicFreqs.zip(gains10.toList()).map { (f, g) -> Pair(f, g) }
        return BiquadMath.interpolateToGrid(pairs, PRE_EQ_FREQS)
    }

    // ──────────────────────────────────────────────
    // Bass tuner via post-EQ (12 dB/octave low shelf)
    // ──────────────────────────────────────────────

    private val bassFreqs = intArrayOf(31, 62, 125, 250, 500)

    private fun applyBassTuner(dp: DynamicsProcessing) {
        try {
            val boost = prefs.bassBoostDb
            val cutoff = prefs.bassCutoffHz
            val enabled = boost > 0f
            val maxCh = if (caps.hasStereoDP) 1 else 0

            for (ch in 0..maxCh) {
                val postEq = dp.getPostEqByChannelIndex(ch)
                postEq.isEnabled = enabled
                dp.setPostEqByChannelIndex(ch, postEq)
            }

            val postEqCount = caps.maxPostEqBands.coerceAtMost(bassFreqs.size)
            for (i in 0 until postEqCount) {
                val freq = bassFreqs[i]
                val gain = if (!enabled) 0f else {
                    if (freq <= cutoff) {
                        boost
                    } else {
                        val octavesAbove = ln(freq.toFloat() / cutoff) / LN_2
                        (boost - 12f * octavesAbove).coerceAtLeast(0f)
                    }
                }
                val band = try { dp.getPostEqBandByChannelIndex(0, i) } catch (_: Exception) { break }
                band.isEnabled = enabled
                band.cutoffFrequency = freq.toFloat()
                band.gain = gain
                dp.setPostEqBandAllChannelsTo(i, band)
            }
        } catch (_: Exception) {}
    }

    fun setBassTuner(db: Float, cutoffHz: Int? = null, type: Int? = null) {
        prefs.bassBoostDb = db
        if (cutoffHz != null) prefs.bassCutoffHz = cutoffHz
        if (type != null) prefs.bassType = type
        for ((_, dp) in dynamicsProcessors) {
            applyBassTuner(dp)
            applyBassMbc(dp)
        }
    }

    // ──────────────────────────────────────────────
    // Bass compressor (MBC stage for bass dynamics)
    //   Natural:    MBC disabled, post-EQ shelf only
    //   Transient:  fast attack / slow release → punchy
    //   Sustain:    slow attack / fast release → rumble
    // ──────────────────────────────────────────────

    private val mbcFreqs = intArrayOf(60, 150, 400) // sub-bass, bass, low-mid crossovers

    private fun applyBassMbc(dp: DynamicsProcessing) {
        if (!caps.hasMbc) return
        try {
            val bassType = prefs.bassType
            val boost = prefs.bassBoostDb
            val cutoff = prefs.bassCutoffHz
            val enabled = bassType != BASS_NATURAL && boost > 0f

            for (ch in 0..(if (caps.hasStereoDP) 1 else 0)) {
                val mbc = dp.getMbcByChannelIndex(ch)
                mbc.isEnabled = enabled
                dp.setMbcByChannelIndex(ch, mbc)
            }

            for (i in mbcFreqs.indices) {
                val band = dp.getMbcBandByChannelIndex(0, i)
                band.isEnabled = enabled
                band.cutoffFrequency = mbcFreqs[i].toFloat()

                if (enabled) {
                    val inRange = mbcFreqs[i] <= cutoff * 2
                    val ratio = if (inRange) 2.5f else 1.0f

                    when (bassType) {
                        BASS_TRANSIENT -> {
                            try { band.attackTime = 5f } catch (_: Exception) {}
                            try { band.releaseTime = 200f } catch (_: Exception) {}
                            try { band.ratio = ratio } catch (_: Exception) {}
                            try { band.threshold = -30f } catch (_: Exception) {}
                            try { band.kneeWidth = 6f } catch (_: Exception) {}
                            band.preGain = 0f
                            band.postGain = if (inRange) boost * 0.3f else 0f
                        }
                        BASS_SUSTAIN -> {
                            try { band.attackTime = 50f } catch (_: Exception) {}
                            try { band.releaseTime = 20f } catch (_: Exception) {}
                            try { band.ratio = ratio } catch (_: Exception) {}
                            try { band.threshold = -25f } catch (_: Exception) {}
                            try { band.kneeWidth = 6f } catch (_: Exception) {}
                            band.preGain = 0f
                            band.postGain = if (inRange) boost * 0.4f else 0f
                        }
                    }
                } else {
                    band.ratio = 1f
                    band.threshold = 0f
                    band.preGain = 0f
                    band.postGain = 0f
                }

                dp.setMbcBandAllChannelsTo(i, band)
            }
        } catch (_: Exception) {}
    }

    // ──────────────────────────────────────────────
    // Limiter (prevents clipping from EQ boosts)
    // ──────────────────────────────────────────────

    private fun applyLimiter(dp: DynamicsProcessing) {
        if (!caps.hasLimiter) return
        try {
            val enabled = prefs.limiterEnabled
            for (ch in 0..(if (caps.hasStereoDP) 1 else 0)) {
                val limiter = dp.getLimiterByChannelIndex(ch)
                limiter.isEnabled = enabled
                if (enabled) {
                    try { limiter.attackTime = 1f } catch (_: Exception) {}
                    try { limiter.releaseTime = 100f } catch (_: Exception) {}
                    try { limiter.ratio = 10f } catch (_: Exception) {}
                    try { limiter.threshold = -0.5f } catch (_: Exception) {}
                    try { limiter.postGain = 0f } catch (_: Exception) {}
                }
                dp.setLimiterByChannelIndex(ch, limiter)
            }
        } catch (_: Exception) {}
    }

    fun setLimiterEnabled(enabled: Boolean) {
        prefs.limiterEnabled = enabled
        for ((_, dp) in dynamicsProcessors) applyLimiter(dp)
    }

    // ──────────────────────────────────────────────
    // Channel balance (per-channel input gain)
    // ──────────────────────────────────────────────

    fun setChannelBalance(balance: Float) {
        prefs.channelBalance = balance
        setAllGain(gainOffsetForStep(currentStep))
    }

    // ──────────────────────────────────────────────
    // Crossfeed (uses Virtualizer at low strength for speaker simulation)
    // Mutually exclusive with manual Virtualizer
    // ──────────────────────────────────────────────

    fun setCrossfeed(enabled: Boolean, strength: Int? = null) {
        prefs.crossfeedEnabled = enabled
        if (strength != null) prefs.crossfeedStrength = strength
        if (enabled) {
            prefs.virtualizerEnabled = false // mutual exclusion
        }
        applyVirtualizerState()
    }

    // ──────────────────────────────────────────────
    // Virtualizer
    // ──────────────────────────────────────────────

    fun setVirtualizer(enabled: Boolean, strength: Int? = null) {
        prefs.virtualizerEnabled = enabled
        if (strength != null) prefs.virtualizerStrength = strength
        if (enabled) {
            prefs.crossfeedEnabled = false // mutual exclusion
        }
        applyVirtualizerState()
    }

    /** Applies the correct Virtualizer state based on crossfeed vs manual virtualizer */
    private fun applyVirtualizerState() {
        val crossfeed = prefs.crossfeedEnabled
        val virtualizer = prefs.virtualizerEnabled
        for ((_, v) in virtualizers) {
            try {
                if (crossfeed) {
                    v.setStrength(prefs.crossfeedStrength.toShort())
                    v.enabled = true
                } else if (virtualizer) {
                    v.setStrength(prefs.virtualizerStrength.toShort())
                    v.enabled = true
                } else {
                    v.enabled = false
                }
            } catch (_: Exception) {}
        }
    }

    // ──────────────────────────────────────────────
    // Reverberation (EnvironmentalReverb)
    // OpenAL EFX standard presets converted for Android
    // ──────────────────────────────────────────────

    data class ReverbParams(
        val roomLevel: Short,
        val roomHFLevel: Short,
        val decayTime: Int,
        val decayHFRatio: Short,
        val reflectionsLevel: Short,
        val reflectionsDelay: Int,
        val reverbLevel: Short,
        val reverbDelay: Int,
        val diffusion: Short,
        val density: Short
    )

    // Converted from OpenAL EFX-Util.h standard presets (linear→mB, s→ms, float→permille)
    private val reverbPresets = arrayOf(
        // Small Room (EFX_REVERB_PRESET_ROOM)
        ReverbParams(-1000, -454, 400, 830, -1646, 2, 53, 3, 1000, 429),
        // Medium Room (EFX_REVERB_PRESET_GENERIC)
        ReverbParams(-1000, -100, 1490, 830, -2602, 7, 200, 11, 1000, 1000),
        // Large Hall (EFX_REVERB_PRESET_CONCERTHALL)
        ReverbParams(-1000, -500, 3920, 700, -1230, 20, -2, 29, 1000, 1000),
        // Cathedral (EFX_REVERB_PRESET_CHAPEL)
        ReverbParams(-1000, -500, 4620, 640, -700, 32, -200, 49, 1000, 1000)
    )

    private fun applyReverbParams(r: EnvironmentalReverb) {
        val p = reverbPresets.getOrElse(prefs.reverbPreset) { reverbPresets[0] }
        try {
            r.roomLevel = p.roomLevel.coerceIn(-9000, 0)
            r.roomHFLevel = p.roomHFLevel.coerceIn(-9000, 0)
            r.decayTime = p.decayTime.coerceIn(100, 20000)
            r.decayHFRatio = p.decayHFRatio.coerceIn(100, 2000)
            r.reflectionsLevel = p.reflectionsLevel.coerceIn(-9000, 1000)
            r.reflectionsDelay = p.reflectionsDelay.coerceIn(0, 300)
            r.reverbLevel = p.reverbLevel.coerceIn(-9000, 2000)
            r.reverbDelay = p.reverbDelay.coerceIn(0, 100)
            r.diffusion = p.diffusion.coerceIn(0, 1000)
            r.density = p.density.coerceIn(0, 1000)
        } catch (_: Exception) {}
    }

    fun setReverb(enabled: Boolean, preset: Int? = null) {
        prefs.reverbEnabled = enabled
        if (preset != null) prefs.reverbPreset = preset
        for ((_, r) in reverbs) {
            try {
                applyReverbParams(r)
                r.enabled = enabled
            } catch (_: Exception) {}
        }
    }

    // ──────────────────────────────────────────────
    // Calibration (GraphicEqActivity borrows DP)
    // ──────────────────────────────────────────────

    private var calibrating = false
    private var savedCalibrationGain = 0

    fun startCalibration(): DynamicsProcessing? {
        calibrating = true
        savedCalibrationGain = gainOffsetForStep(currentStep)
        return dynamicsProcessors.values.firstOrNull()
    }

    fun stopCalibration() {
        calibrating = false
        activeProfile = null // force reload from prefs (may have been updated by GraphicEqActivity)
        val entry = dynamicsProcessors.entries.firstOrNull() ?: return
        val dp = entry.value
        applyDpGain(dp, savedCalibrationGain)
        applyPreEq(dp, entry.key)
        applyBassTuner(dp)
        applyBassMbc(dp)
        applyLimiter(dp)
    }

    fun isCalibrating(): Boolean = calibrating

    // ──────────────────────────────────────────────
    // Gain helpers
    // ──────────────────────────────────────────────

    private fun setSystemVolume(stream: Int, volume: Int) {
        selfChanging = true
        audioManager.setStreamVolume(stream, volume, 0)
        lastSystemVol = volume
        selfChanging = false
    }

    private fun setAllGain(mb: Int) {
        for ((_, dp) in dynamicsProcessors) applyDpGain(dp, mb)
        for ((_, eq) in equalizers) applyEqGain(eq, mb)
    }

    private fun applyDpGain(dp: DynamicsProcessing, mb: Int) {
        try {
            val baseGain = mb / 100f + cachedPreampDb
            dp.setInputGainAllChannelsTo(baseGain)
            applyChannelBalance(dp)
        } catch (_: Exception) {}
    }

    private fun applyChannelBalance(dp: DynamicsProcessing) {
        if (!caps.hasStereoDP || !caps.hasLimiter) return
        val balance = prefs.channelBalance
        if (balance == 0f) return // most common case — skip all JNI calls
        try {
            val leftAtten = if (balance > 0f) -balance * 96f else 0f
            val rightAtten = if (balance < 0f) balance * 96f else 0f

            // Enable limiter on both channels for balance (transparent settings if user hasn't enabled limiting)
            for (ch in 0..1) {
                val lim = dp.getLimiterByChannelIndex(ch)
                lim.isEnabled = true
                if (!prefs.limiterEnabled) {
                    // Transparent: won't actually limit anything
                    try { lim.threshold = 0f } catch (_: Throwable) {}
                    try { lim.ratio = 1f } catch (_: Throwable) {}
                    try { lim.attackTime = 1f } catch (_: Throwable) {}
                    try { lim.releaseTime = 100f } catch (_: Throwable) {}
                }
                lim.postGain = if (ch == 0) leftAtten else rightAtten
                dp.setLimiterByChannelIndex(ch, lim)
            }
        } catch (_: Throwable) {}
    }

    fun setPreamp(db: Float) {
        prefs.preampDb = db
        cachedPreampDb = db
        setAllGain(gainOffsetForStep(currentStep))
    }

    private fun applyEqGain(eq: Equalizer, mb: Int) {
        try {
            val range = eq.bandLevelRange
            val clamped = mb.toShort().coerceIn(range[0], range[1])
            for (i in 0 until eq.numberOfBands) eq.setBandLevel(i.toShort(), clamped)
        } catch (_: Exception) {}
    }

    fun release() {
        stopObserving()
        for ((_, dp) in dynamicsProcessors) {
            try { dp.enabled = false; dp.release() } catch (_: Exception) {}
        }
        for ((_, eq) in equalizers) {
            try { eq.enabled = false; eq.release() } catch (_: Exception) {}
        }
        for ((_, v) in virtualizers) {
            try { v.enabled = false; v.release() } catch (_: Exception) {}
        }
        for ((_, r) in reverbs) {
            try { r.enabled = false; r.release() } catch (_: Exception) {}
        }
        dynamicsProcessors.clear()
        equalizers.clear()
        virtualizers.clear()
        reverbs.clear()
    }

    companion object {
        @Volatile
        private var instance: VolumeController? = null

        fun getInstance(context: Context): VolumeController {
            return instance ?: synchronized(this) {
                instance ?: VolumeController(context.applicationContext).also { instance = it }
            }
        }

        /** Fixed 10-band pre-EQ matching AutoEQ FixedBandEQ format exactly */
        val PRE_EQ_FREQS = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
        const val PRE_EQ_BAND_COUNT = 10

        /** Called after device probe to update capabilities */
        fun configureForDevice(caps: DeviceCapabilities) {
            // Band count stays at 10 always — matches AutoEQ exactly
            instance?.let {
                if (caps.hasDynamicsProcessing) it.useDynamicsProcessing = true
            }
        }

        const val EQ_MODE_OFF = 0
        const val EQ_MODE_GRAPHIC = 1
        const val EQ_MODE_PARAMETRIC = 2

        val REVERB_PRESET_LABELS = arrayOf("Small room", "Medium room", "Large hall", "Cathedral")
        val BASS_TYPE_LABELS = arrayOf("Natural", "Transient compressor", "Sustain compressor")

        const val BASS_NATURAL = 0
        const val BASS_TRANSIENT = 1
        const val BASS_SUSTAIN = 2

        private val LN_2 = ln(2f)
    }
}
