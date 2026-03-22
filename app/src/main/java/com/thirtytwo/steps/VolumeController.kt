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

    // Use synchronized collections to prevent ConcurrentModificationException (#8)
    private val dynamicsProcessors = LinkedHashMap<Int, DynamicsProcessing>()
    private val equalizers = LinkedHashMap<Int, Equalizer>()
    private var useDynamicsProcessing = true

    val systemMax: Int = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    val totalSteps: Int get() = prefs.totalSteps

    var currentStep: Int
        get() = prefs.currentStep
        private set(value) { prefs.currentStep = value }

    private val mbPerSystemStep: Int by lazy { measureMbPerStep() }
    private var lastSystemVol = -1

    // Timestamp-based observer guard instead of boolean flag (#1, #3)
    private var lastSelfChangeTime = 0L
    private val selfChangeWindowMs = 150L

    // Prevent concurrent operations (#2, #4, #7, #8)
    private var operating = false

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

    // Volume observer - uses timestamp window instead of boolean (#1, #3)

    private val volumeObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            if (System.currentTimeMillis() - lastSelfChangeTime < selfChangeWindowMs) return
            if (operating) return
            val sysVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (sysVol != lastSystemVol) {
                lastSystemVol = sysVol
                // Only update internal state, don't re-set system volume (#3)
                val fraction = (sysVol.toFloat() - 1) / (systemMax - 1).coerceAtLeast(1)
                currentStep = if (sysVol == 0) 0
                    else (fraction * totalSteps).roundToInt().coerceIn(1, totalSteps)
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

    @Synchronized
    fun attachSession(sessionId: Int) {
        if (dynamicsProcessors.containsKey(sessionId) ||
            equalizers.containsKey(sessionId)
        ) return

        if (useDynamicsProcessing) {
            try {
                val config = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    1, true, 10, false, 0, false, 0, false
                ).build()
                val dp = DynamicsProcessing(Int.MAX_VALUE, sessionId, config)
                dp.enabled = true
                dynamicsProcessors[sessionId] = dp
                applySoundProfile(dp)
                applyDpGain(dp, gainOffsetForStep(currentStep))
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

    @Synchronized
    fun detachSession(sessionId: Int) {
        dynamicsProcessors.remove(sessionId)?.apply {
            try { enabled = false; release() } catch (_: Exception) {}
        }
        equalizers.remove(sessionId)?.apply {
            try { enabled = false; release() } catch (_: Exception) {}
        }
    }

    // Volume control

    private fun activeStream(): Int = when (audioManager.mode) {
        AudioManager.MODE_IN_CALL,
        AudioManager.MODE_IN_COMMUNICATION -> AudioManager.STREAM_VOICE_CALL
        AudioManager.MODE_RINGTONE -> AudioManager.STREAM_RING
        else -> AudioManager.STREAM_MUSIC
    }

    fun stepUp(): Int {
        setStep(currentStep + 1)
        return currentStep
    }

    fun stepDown(): Int {
        setStep(currentStep - 1)
        return currentStep
    }

    @Synchronized
    fun setStep(step: Int) {
        if (operating) return
        operating = true

        try {
            val newStep = step.coerceIn(0, totalSteps)
            currentStep = newStep
            val stream = activeStream()

            if (newStep == 0) {
                setSystemVolume(stream, 0)
                setAllGain(0)
                notifyStepChanged()
                return
            }

            if (stream != AudioManager.STREAM_MUSIC) {
                val sysMax = audioManager.getStreamMaxVolume(stream)
                val sysVol = (newStep.toFloat() / totalSteps * sysMax).roundToInt().coerceIn(0, sysMax)
                setSystemVolume(stream, sysVol)
                notifyStepChanged()
                return
            }

            val (targetVol, gainOffset) = computeMapping(newStep)
            val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

            // Set gain first, then system volume. No delayed callbacks (#7)
            setAllGain(gainOffset)
            if (targetVol != currentVol) {
                setSystemVolume(AudioManager.STREAM_MUSIC, targetVol)
            }

            notifyStepChanged()
        } finally {
            operating = false
        }
    }

    @Synchronized
    fun syncFromSystem() {
        if (operating) return
        operating = true

        try {
            val sysVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            lastSystemVol = sysVol
            if (sysVol == 0) {
                currentStep = 0
                setAllGain(0)
                return
            }
            val fraction = (sysVol.toFloat() - 1) / (systemMax - 1).coerceAtLeast(1)
            currentStep = (fraction * totalSteps).roundToInt().coerceIn(1, totalSteps)
            val (targetVol, gainOffset) = computeMapping(currentStep)
            if (targetVol != sysVol) {
                setSystemVolume(AudioManager.STREAM_MUSIC, targetVol)
            }
            setAllGain(gainOffset)
        } finally {
            operating = false
        }
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

    @Synchronized
    fun setSoundProfile(profile: HeadphoneProfile?) {
        activeProfile = profile
        val sessions = dynamicsProcessors.toMap()
        for ((_, dp) in sessions) {
            applySoundProfile(dp) // only touches pre-EQ bands
        }
        // Re-apply volume gain with correct preamp compensation
        setAllGain(gainOffsetForStep(currentStep))
    }

    private fun applySoundProfile(dp: DynamicsProcessing) {
        try {
            ensureProfileLoaded()
            val profile = activeProfile

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

            // Apply profile bands to pre-EQ only - inputGain handled by setAllGain
            val eqBandFreqs = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
            for (i in 0 until 10.coerceAtMost(profile.bands.size)) {
                val (_, gain) = profile.bands[i]
                val band = dp.getPreEqBandByChannelIndex(0, i)
                band.isEnabled = true
                band.cutoffFrequency = eqBandFreqs.getOrElse(i) { 1000 }.toFloat()
                band.gain = gain
                dp.setPreEqBandAllChannelsTo(i, band)
            }

        } catch (e: Exception) {
            // Log but don't silently swallow (#10)
            android.util.Log.w("VolumeController", "Failed to apply sound profile", e)
        }
    }

    // Helpers

    private fun setSystemVolume(stream: Int, volume: Int) {
        lastSelfChangeTime = System.currentTimeMillis()
        lastSystemVol = volume
        audioManager.setStreamVolume(stream, volume, 0)
    }

    @Synchronized
    private fun setAllGain(mb: Int) {
        val preampMb = activeProfile?.let { (it.preamp * 100).toInt() } ?: 0
        val totalGain = mb + preampMb

        val dpSnapshot = dynamicsProcessors.toMap() // snapshot (#8)
        for ((_, dp) in dpSnapshot) applyDpGain(dp, totalGain)

        val eqSnapshot = equalizers.toMap()
        for ((_, eq) in eqSnapshot) applyEqGain(eq, mb) // EQ path has no preamp
    }

    private fun applyDpGain(dp: DynamicsProcessing, mb: Int) {
        try {
            dp.setInputGainAllChannelsTo(mb / 100f)
        } catch (e: Exception) {
            android.util.Log.w("VolumeController", "DynamicsProcessing failed, reattaching", e)
            reattachSessions()
        }
    }

    private var reattaching = false

    private fun reattachSessions() {
        if (reattaching) return
        reattaching = true
        handler.post {
            val sessionIds = synchronized(this) {
                (dynamicsProcessors.keys + equalizers.keys).toSet().toList()
            }
            synchronized(this) {
                for ((_, dp) in dynamicsProcessors) {
                    try { dp.enabled = false; dp.release() } catch (_: Exception) {}
                }
                for ((_, eq) in equalizers) {
                    try { eq.enabled = false; eq.release() } catch (_: Exception) {}
                }
                dynamicsProcessors.clear()
                equalizers.clear()
            }
            for (id in sessionIds) attachSession(id)
            reattaching = false
        }
    }

    private fun applyEqGain(eq: Equalizer, mb: Int) {
        try {
            val range = eq.bandLevelRange
            val clamped = mb.toShort().coerceIn(range[0], range[1])
            for (i in 0 until eq.numberOfBands) eq.setBandLevel(i.toShort(), clamped)
        } catch (_: Exception) {}
    }

    @Synchronized
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
