package com.thirtytwo.steps

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.roundToInt

class VolumeController(private val context: Context) {

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val prefs = PrefsManager(context)
    private val handler = Handler(Looper.getMainLooper())

    private val dynamicsProcessors = mutableMapOf<Int, DynamicsProcessing>()
    private val equalizers = mutableMapOf<Int, Equalizer>()
    private var useDynamicsProcessing = true

    val systemMax: Int = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    val totalSteps: Int get() = prefs.totalSteps

    var currentStep: Int
        get() = prefs.currentStep
        private set(value) { prefs.currentStep = value }

    private val mbPerSystemStep: Int by lazy { measureMbPerStep() }
    private var lastSystemVol = -1
    private var selfChanging = false

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
                // Only update internal state - never re-set system volume
                if (sysVol == 0) {
                    currentStep = 0
                } else {
                    val fraction = (sysVol.toFloat() - 1) / (systemMax - 1).coerceAtLeast(1)
                    currentStep = (fraction * totalSteps).roundToInt().coerceIn(1, totalSteps)
                }
                setAllGain(gainOffsetForStep(currentStep))
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

    fun attachSession(sessionId: Int) {
        if (dynamicsProcessors.containsKey(sessionId) ||
            equalizers.containsKey(sessionId)
        ) return

        if (useDynamicsProcessing) {
            try {
                val config = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    1,     // channels
                    true,  // pre-EQ enabled (for sound profiles)
                    10,    // 10 EQ bands
                    false, 0, false, 0, false
                ).build()
                val dp = DynamicsProcessing(Int.MAX_VALUE, sessionId, config)
                dp.enabled = true
                dynamicsProcessors[sessionId] = dp
                applyDpGain(dp, gainOffsetForStep(currentStep))
                applySoundProfile(dp)
                return
            } catch (_: Exception) {
                useDynamicsProcessing = false
            }
        }

        try {
            val eq = Equalizer(Int.MAX_VALUE, sessionId)
            eq.enabled = true
            equalizers[sessionId] = eq
            applyEqGain(eq, gainOffsetForStep(currentStep))
        } catch (_: Exception) {}
    }

    fun detachSession(sessionId: Int) {
        dynamicsProcessors.remove(sessionId)?.apply { enabled = false; release() }
        equalizers.remove(sessionId)?.apply { enabled = false; release() }
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
            // Non-music: step through system levels directly
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

        // Mute
        if (newStep == 0) {
            setSystemVolume(stream, 0)
            setAllGain(0)
            notifyStepChanged()
            return
        }

        // Music: system volume + gain offset for sub-stepping
        val (targetVol, gainOffset) = computeMapping(newStep)
        val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

        if (targetVol != currentVol) {
            val needsSmoothing = currentVol > 0 && abs(targetVol - currentVol) <= 2
            if (needsSmoothing) {
                // Small step boundary: pre-attenuate to avoid pop
                val preAttenuation = -(targetVol - currentVol) * mbPerSystemStep + gainOffset
                setAllGain(preAttenuation)
                setSystemVolume(AudioManager.STREAM_MUSIC, targetVol)
                handler.postDelayed({ setAllGain(gainOffset) }, 15)
            } else {
                // Big jump or from mute: set directly
                setAllGain(gainOffset)
                setSystemVolume(AudioManager.STREAM_MUSIC, targetVol)
            }
        } else {
            setAllGain(gainOffset)
        }

        notifyStepChanged()
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
        // Re-apply mapping for the synced step
        val (targetVol, gainOffset) = computeMapping(currentStep)
        setSystemVolume(AudioManager.STREAM_MUSIC, targetVol)
        setAllGain(gainOffset)
    }

    // Volume mapping

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

    private fun measureMbPerStep(): Int {
        if (systemMax <= 1) return 300
        var totalDb = 0.0
        for (i in 1 until systemMax) {
            val dbThis = 20.0 * ln(i.toDouble() / systemMax) / ln(10.0)
            val dbNext = 20.0 * ln((i + 1).toDouble() / systemMax) / ln(10.0)
            totalDb += (dbNext - dbThis)
        }
        val avgMbPerStep = (totalDb / (systemMax - 1) * 100).toInt()
        if (avgMbPerStep <= 0) return 300
        return avgMbPerStep
    }

    // Sound profile via DynamicsProcessing pre-EQ

    private var activeProfile: HeadphoneProfile? = null
    private var profileManager: SoundProfileManager? = null

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
        for ((_, dp) in dynamicsProcessors) applySoundProfile(dp)
    }

    private fun applySoundProfile(dp: DynamicsProcessing) {
        try {
            ensureProfileLoaded()
            val profile = activeProfile

            // Enable/disable the pre-EQ stage on the channel
            val preEq = dp.getPreEqByChannelIndex(0)
            preEq.isEnabled = profile != null
            dp.setPreEqAllChannelsTo(preEq)

            if (profile == null) {
                for (i in 0 until 10) {
                    val band = dp.getPreEqBandByChannelIndex(0, i)
                    band.isEnabled = false
                    band.gain = 0f
                    dp.setPreEqBandAllChannelsTo(i, band)
                }
                return
            }

            // Apply profile bands to pre-EQ
            val eqBandFreqs = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
            for (i in 0 until 10.coerceAtMost(profile.bands.size)) {
                val (_, gain) = profile.bands[i]
                val band = dp.getPreEqBandByChannelIndex(0, i)
                band.isEnabled = true
                band.cutoffFrequency = eqBandFreqs.getOrElse(i) { 1000 }.toFloat()
                band.gain = gain
                dp.setPreEqBandAllChannelsTo(i, band)
            }
        } catch (_: Exception) {}
    }

    // Calibration support - lets CalibrationActivity use our DynamicsProcessing

    private var calibrating = false
    private var savedCalibrationGain = 0

    fun startCalibration(): DynamicsProcessing? {
        calibrating = true
        savedCalibrationGain = gainOffsetForStep(currentStep)
        return dynamicsProcessors.values.firstOrNull()
    }

    fun stopCalibration() {
        calibrating = false
        // Restore volume gain and sound profile
        val dp = dynamicsProcessors.values.firstOrNull() ?: return
        dp.setInputGainAllChannelsTo(savedCalibrationGain / 100f)
        applySoundProfile(dp)
    }

    fun isCalibrating(): Boolean = calibrating

    // Helpers

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
        try { dp.setInputGainAllChannelsTo(mb / 100f) } catch (_: Exception) {}
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
