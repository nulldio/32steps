package com.thirtytwo.steps

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.roundToInt

class VolumeController(private val context: Context) {

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val prefs = PrefsManager(context)
    private val handler = Handler(Looper.getMainLooper())

    // Audio effect instances keyed by session ID
    private val dynamicsProcessors = mutableMapOf<Int, DynamicsProcessing>()
    private val equalizers = mutableMapOf<Int, Equalizer>()
    private var useDynamicsProcessing = true

    val systemMax: Int = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    val totalSteps: Int get() = prefs.totalSteps

    var currentStep: Int
        get() = prefs.currentStep
        private set(value) {
            prefs.currentStep = value
        }

    // Measured dB gap between adjacent system steps (in millibels).
    // Calibrated at runtime by measuring actual volume index ratios.
    private val mbPerSystemStep: Int by lazy { measureMbPerStep() }

    private var lastSystemVol = -1
    private var selfChanging = false

    private val stepListeners = mutableListOf<(step: Int, total: Int) -> Unit>()

    fun addStepListener(listener: (step: Int, total: Int) -> Unit) {
        stepListeners.add(listener)
    }

    fun removeStepListener(listener: (step: Int, total: Int) -> Unit) {
        stepListeners.remove(listener)
    }

    private fun notifyStepChanged(step: Int, total: Int) {
        for (listener in stepListeners) {
            listener(step, total)
        }
    }

    private val volumeObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            if (selfChanging) return
            val sysVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (sysVol != lastSystemVol) {
                lastSystemVol = sysVol
                syncFromSystem()
                val offset = gainOffsetForStep(currentStep)
                setAllGain(offset)
                notifyStepChanged(currentStep, totalSteps)
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

    /**
     * Attach audio effect to a session.
     * Tries DynamicsProcessing first (flat gain control),
     * falls back to Equalizer (uniform band level).
     */
    fun attachSession(sessionId: Int) {
        if (dynamicsProcessors.containsKey(sessionId) ||
            equalizers.containsKey(sessionId)
        ) return

        if (useDynamicsProcessing) {
            try {
                val config = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    1,    // channel count
                    false, // pre-EQ
                    0,
                    false, // multi-band compressor
                    0,
                    false, // post-EQ
                    0,
                    false  // limiter
                ).build()

                val dp = DynamicsProcessing(Int.MAX_VALUE, sessionId, config)
                dp.enabled = true
                dynamicsProcessors[sessionId] = dp
                applyDpGain(dp, gainOffsetForStep(currentStep))
                return
            } catch (_: Exception) {
                // DynamicsProcessing not supported, fall back to EQ
                useDynamicsProcessing = false
            }
        }

        try {
            val eq = Equalizer(Int.MAX_VALUE, sessionId)
            eq.enabled = true
            equalizers[sessionId] = eq
            applyEqGain(eq, gainOffsetForStep(currentStep))
        } catch (_: Exception) {
            // Session invalid or effect not supported
        }
    }

    fun detachSession(sessionId: Int) {
        dynamicsProcessors.remove(sessionId)?.apply {
            enabled = false
            release()
        }
        equalizers.remove(sessionId)?.apply {
            enabled = false
            release()
        }
    }

    private fun activeStream(): Int {
        return when (audioManager.mode) {
            AudioManager.MODE_IN_CALL,
            AudioManager.MODE_IN_COMMUNICATION -> AudioManager.STREAM_VOICE_CALL
            AudioManager.MODE_RINGTONE -> AudioManager.STREAM_RING
            else -> AudioManager.STREAM_MUSIC
        }
    }

    fun stepUp(): Int {
        setStep(currentStep + 1)
        return currentStep
    }

    fun stepDown(): Int {
        setStep(currentStep - 1)
        return currentStep
    }

    fun setStep(step: Int) {
        val newStep = step.coerceIn(0, totalSteps)
        currentStep = newStep
        val stream = activeStream()

        // 1 step = simple mute/max toggle
        if (totalSteps == 1) {
            selfChanging = true
            if (newStep == 0) {
                audioManager.setStreamVolume(stream, 0, 0)
                lastSystemVol = 0
            } else {
                val max = audioManager.getStreamMaxVolume(stream)
                audioManager.setStreamVolume(stream, max, 0)
                lastSystemVol = max
            }
            selfChanging = false
            setAllGain(0)
            notifyStepChanged(newStep, totalSteps)
            return
        }

        if (newStep == 0) {
            selfChanging = true
            audioManager.setStreamVolume(stream, 0, 0)
            lastSystemVol = 0
            selfChanging = false
            setAllGain(0)
            notifyStepChanged(newStep, totalSteps)
            return
        }

        if (stream != AudioManager.STREAM_MUSIC) {
            val sysMax = audioManager.getStreamMaxVolume(stream)
            val sysVol = (newStep.toFloat() / totalSteps * sysMax).roundToInt().coerceIn(0, sysMax)
            selfChanging = true
            audioManager.setStreamVolume(stream, sysVol, 0)
            selfChanging = false
            notifyStepChanged(newStep, totalSteps)
            return
        }

        val (sysVol, gainOffset) = computeMapping(newStep)
        val currentSysVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

        if (sysVol != currentSysVol) {
            val bigJump = kotlin.math.abs(sysVol - currentSysVol) > 2
            if (currentSysVol == 0 || totalSteps <= 1 || bigJump) {
                // From mute or single step — set directly, no smoothing needed
                setAllGain(gainOffset)
                selfChanging = true
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, sysVol, 0)
                lastSystemVol = sysVol
                selfChanging = false
            } else {
                // Crossing a system step boundary — pre-attenuate to avoid pop
                val preAttenuation = -(sysVol - currentSysVol) * mbPerSystemStep + gainOffset
                setAllGain(preAttenuation)

                selfChanging = true
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, sysVol, 0)
                lastSystemVol = sysVol
                selfChanging = false

                handler.postDelayed({ setAllGain(gainOffset) }, 15)
            }
        } else {
            setAllGain(gainOffset)
            lastSystemVol = sysVol
        }

        notifyStepChanged(newStep, totalSteps)
    }

    /**
     * Maps a virtual step (1..totalSteps) to system volume + gain offset.
     * System volume is the ceiling; gain offset attenuates the remainder.
     */
    private fun computeMapping(step: Int): Pair<Int, Int> {
        val fraction = step.toFloat() / totalSteps
        val floatSysVol = 1 + fraction * (systemMax - 1)
        val sysVol = ceil(floatSysVol).toInt().coerceIn(1, systemMax)
        val attenuation = sysVol - floatSysVol
        val gainOffset = -(attenuation * mbPerSystemStep).toInt()
        return sysVol to gainOffset
    }

    private fun gainOffsetForStep(step: Int): Int {
        if (step <= 0) return 0
        return computeMapping(step).second
    }

    /**
     * Apply gain to all active audio effect instances.
     * Uses DynamicsProcessing inputGain (flat) or Equalizer bands (approximate).
     */
    private fun setAllGain(mb: Int) {
        for ((_, dp) in dynamicsProcessors) {
            applyDpGain(dp, mb)
        }
        for ((_, eq) in equalizers) {
            applyEqGain(eq, mb)
        }
    }

    /** DynamicsProcessing: true flat gain in dB — no frequency coloring. */
    private fun applyDpGain(dp: DynamicsProcessing, mb: Int) {
        try {
            val gainDb = mb / 100f // millibels to decibels
            dp.setInputGainAllChannelsTo(gainDb)
        } catch (_: Exception) {}
    }

    /** Equalizer fallback: set all bands to uniform level. */
    private fun applyEqGain(eq: Equalizer, mb: Int) {
        try {
            val range = eq.bandLevelRange
            val clamped = mb.toShort().coerceIn(range[0], range[1])
            for (i in 0 until eq.numberOfBands) {
                eq.setBandLevel(i.toShort(), clamped)
            }
        } catch (_: Exception) {}
    }

    /**
     * Measure actual dB per system step on this device.
     *
     * Android maps volume indices to dB using a curve defined per-device.
     * We estimate the average step size by using the standard Android formula:
     *   dB = 20 * log10(index / maxIndex)
     * and computing the average dB gap across all steps.
     *
     * Returns millibels per step.
     */
    private fun measureMbPerStep(): Int {
        if (systemMax <= 1) return 300

        // Android's typical volume curve: roughly linear in dB
        // Total range is usually ~45-50 dB for STREAM_MUSIC
        // We estimate using the log curve: volume_dB ≈ 20*log10(i/max)
        var totalDb = 0.0
        for (i in 1 until systemMax) {
            val dbThis = 20.0 * ln(i.toDouble() / systemMax) / ln(10.0)
            val dbNext = 20.0 * ln((i + 1).toDouble() / systemMax) / ln(10.0)
            totalDb += (dbNext - dbThis)
        }
        val avgDbPerStep = totalDb / (systemMax - 1)
        val avgMbPerStep = (avgDbPerStep * 100).toInt()

        if (avgMbPerStep <= 0) return 300
        return avgMbPerStep
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
        // Apply the correct volume mapping for the current step
        val (targetSysVol, gainOffset) = computeMapping(currentStep)
        selfChanging = true
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetSysVol, 0)
        lastSystemVol = targetSysVol
        selfChanging = false
        setAllGain(gainOffset)
    }

    fun release() {
        stopObserving()
        for ((_, dp) in dynamicsProcessors) {
            try { dp.enabled = false; dp.release() } catch (_: Exception) {}
        }
        for ((_, eq) in equalizers) {
            try { eq.enabled = false; eq.release() } catch (_: Exception) {}
        }
        dynamicsProcessors.clear()
        equalizers.clear()
    }

    companion object {
        @Volatile
        private var instance: VolumeController? = null

        fun getInstance(context: Context): VolumeController {
            return instance ?: synchronized(this) {
                instance ?: VolumeController(context.applicationContext).also { instance = it }
            }
        }
    }
}
