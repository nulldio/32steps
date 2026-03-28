package com.thirtytwo.steps

import android.content.Context
import android.content.SharedPreferences
import android.media.audiofx.*
import android.os.Build
import android.util.Log

/**
 * Probes the device's audio subsystem on first launch to discover exact capabilities.
 * Results are cached in SharedPreferences and used throughout the app to configure
 * DynamicsProcessing with the optimal band count and show/hide features.
 */
class DeviceCapabilities private constructor(private val prefs: SharedPreferences) {

    val probed: Boolean get() = prefs.getBoolean(KEY_PROBED, false)
    val appVersion: Int get() = prefs.getInt(KEY_APP_VERSION, 0)

    val hasDynamicsProcessing: Boolean get() = prefs.getBoolean(KEY_HAS_DP, false)
    val maxPreEqBands: Int get() = prefs.getInt(KEY_MAX_PRE_EQ, 0)
    val maxPostEqBands: Int get() = prefs.getInt(KEY_MAX_POST_EQ, 0)
    val hasMbc: Boolean get() = prefs.getBoolean(KEY_HAS_MBC, false)
    val hasLimiter: Boolean get() = prefs.getBoolean(KEY_HAS_LIMITER, false)
    val hasStereoDP: Boolean get() = prefs.getBoolean(KEY_HAS_STEREO, false)
    val hasVirtualizer: Boolean get() = prefs.getBoolean(KEY_HAS_VIRTUALIZER, false)
    val hasReverb: Boolean get() = prefs.getBoolean(KEY_HAS_REVERB, false)
    val hasVisualizer: Boolean get() = prefs.getBoolean(KEY_HAS_VISUALIZER, false)
    val hasEqualizer: Boolean get() = prefs.getBoolean(KEY_HAS_EQUALIZER, false)

    val optimalPreEqBands: Int get() = maxPreEqBands.coerceIn(5, 31)

    val preEqFreqs: IntArray get() {
        val count = optimalPreEqBands
        return when {
            count >= 31 -> THIRD_OCTAVE_31
            count >= 20 -> HALF_OCTAVE_20
            count >= 10 -> OCTAVE_10
            else -> OCTAVE_10.sliceArray(0 until count.coerceAtLeast(5))
        }
    }

    /**
     * Probes device capabilities. Safe to call even during audio playback —
     * uses priority 0 (lowest) and releases immediately after each test.
     */
    fun probe(context: Context, forceReprobe: Boolean = false) {
        try {
            val currentVersion = try {
                context.packageManager.getPackageInfo(context.packageName, 0).let {
                    if (Build.VERSION.SDK_INT >= 28) it.longVersionCode.toInt()
                    else @Suppress("DEPRECATION") it.versionCode
                }
            } catch (_: Exception) { 0 }

            if (probed && appVersion == currentVersion && !forceReprobe) return

            Log.i(TAG, "Probing device audio capabilities...")
            val editor = prefs.edit()

            if (Build.VERSION.SDK_INT >= 28) {
                try { probeDynamicsProcessing(editor) } catch (_: Exception) {
                    editor.putBoolean(KEY_HAS_DP, false)
                }
            } else {
                editor.putBoolean(KEY_HAS_DP, false)
                editor.putInt(KEY_MAX_PRE_EQ, 0)
                editor.putInt(KEY_MAX_POST_EQ, 0)
                editor.putBoolean(KEY_HAS_MBC, false)
                editor.putBoolean(KEY_HAS_LIMITER, false)
                editor.putBoolean(KEY_HAS_STEREO, false)
            }

            editor.putBoolean(KEY_HAS_VIRTUALIZER, probeEffect { Virtualizer(0, 0) })
            editor.putBoolean(KEY_HAS_REVERB, probeReverb())
            editor.putBoolean(KEY_HAS_EQUALIZER, probeEffect { Equalizer(0, 0) })
            editor.putBoolean(KEY_HAS_VISUALIZER, probeVisualizer())

            editor.putInt(KEY_APP_VERSION, currentVersion)
            editor.putBoolean(KEY_PROBED, true)
            editor.apply()

            Log.i(TAG, "Probe complete: DP=$hasDynamicsProcessing preEq=$maxPreEqBands " +
                    "postEq=$maxPostEqBands mbc=$hasMbc limiter=$hasLimiter stereo=$hasStereoDP " +
                    "virtualizer=$hasVirtualizer reverb=$hasReverb visualizer=$hasVisualizer eq=$hasEqualizer")
        } catch (e: Exception) {
            Log.e(TAG, "Probe failed entirely", e)
        }
    }

    private fun probeDynamicsProcessing(editor: SharedPreferences.Editor) {
        var stereo = false
        var maxPre = 0
        var maxPost = 0
        var mbcWorks = false
        var limiterWorks = false
        var dpWorks = false

        for (channels in intArrayOf(2, 1)) {
            val maxBands = findMaxBands(channels) { ch, bands ->
                testDpConfigDeep(ch, preEq = bands, postEq = 1, mbc = false, limiter = false)
            }

            if (maxBands > 0) {
                dpWorks = true
                stereo = channels == 2
                maxPre = maxBands

                maxPost = findMaxBands(channels) { ch, bands ->
                    testDpConfigDeep(ch, preEq = maxPre.coerceAtMost(10), postEq = bands, mbc = false, limiter = false)
                }

                mbcWorks = testDpConfigDeep(channels, preEq = maxPre.coerceAtMost(10), postEq = 1, mbc = true, limiter = false)
                limiterWorks = testDpConfigDeep(channels, preEq = maxPre.coerceAtMost(10), postEq = 1, mbc = false, limiter = true)

                break
            }
        }

        editor.putBoolean(KEY_HAS_DP, dpWorks)
        editor.putInt(KEY_MAX_PRE_EQ, maxPre)
        editor.putInt(KEY_MAX_POST_EQ, maxPost)
        editor.putBoolean(KEY_HAS_MBC, mbcWorks)
        editor.putBoolean(KEY_HAS_LIMITER, limiterWorks)
        editor.putBoolean(KEY_HAS_STEREO, stereo)
    }

    private fun findMaxBands(channels: Int, test: (Int, Int) -> Boolean): Int {
        for (count in intArrayOf(31, 25, 20, 15, 10, 5, 1)) {
            if (test(channels, count)) return count
        }
        return 0
    }

    /**
     * Deep test: create DP, enable it, write a band gain, read it back, verify.
     * This catches devices that accept the config but don't actually process bands.
     */
    private fun testDpConfigDeep(channels: Int, preEq: Int, postEq: Int, mbc: Boolean, limiter: Boolean): Boolean {
        return try {
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                channels,
                preEq > 0, preEq.coerceAtLeast(1),
                mbc, if (mbc) 3 else 0,
                postEq > 0, postEq.coerceAtLeast(1),
                limiter
            ).build()
            // Priority 0 = lowest, won't interfere with active effects
            val dp = DynamicsProcessing(0, 0, config)
            dp.enabled = true

            // Verify pre-EQ actually works: write gain, read back
            if (preEq > 0) {
                val band = dp.getPreEqBandByChannelIndex(0, 0)
                band.isEnabled = true
                band.cutoffFrequency = 1000f
                band.gain = 3.0f
                dp.setPreEqBandAllChannelsTo(0, band)
                val readBack = dp.getPreEqBandByChannelIndex(0, 0)
                // Verify the gain was actually accepted (within 1 dB tolerance)
                if (kotlin.math.abs(readBack.gain - 3.0f) > 1.0f) {
                    dp.enabled = false
                    dp.release()
                    return false
                }
            }

            // Verify MBC if requested
            if (mbc) {
                val mbcBand = dp.getMbcBandByChannelIndex(0, 0)
                mbcBand.isEnabled = true
                mbcBand.attackTime = 10f
                mbcBand.releaseTime = 100f
                mbcBand.ratio = 2f
                mbcBand.threshold = -20f
                dp.setMbcBandAllChannelsTo(0, mbcBand)
            }

            // Verify limiter if requested
            if (limiter) {
                val lim = dp.getLimiterByChannelIndex(0)
                lim.isEnabled = true
                lim.attackTime = 1f
                lim.releaseTime = 100f
                lim.ratio = 10f
                lim.threshold = -1f
                dp.setLimiterByChannelIndex(0, lim)
            }

            dp.enabled = false
            dp.release()
            true
        } catch (e: Exception) {
            Log.d(TAG, "DP config test failed: ch=$channels preEq=$preEq mbc=$mbc lim=$limiter: ${e.message}")
            false
        }
    }

    private fun probeEffect(create: () -> AudioEffect): Boolean {
        return try {
            val effect = create()
            effect.enabled = false
            effect.release()
            true
        } catch (_: Exception) { false }
    }

    /** Deep reverb probe: create + set parameters to verify they're accepted */
    private fun probeReverb(): Boolean {
        return try {
            val r = EnvironmentalReverb(0, 0)
            r.enabled = true
            // Test setting a parameter — some devices create the effect but reject parameters
            r.roomLevel = -1000
            r.decayTime = 1000
            r.enabled = false
            r.release()
            true
        } catch (_: Exception) { false }
    }

    private fun probeVisualizer(): Boolean {
        return try {
            val v = Visualizer(0)
            val range = Visualizer.getCaptureSizeRange()
            if (range == null || range.size < 2 || range[1] < 64) {
                v.release()
                return false
            }
            v.captureSize = range[1]
            v.enabled = false
            v.release()
            true
        } catch (_: Exception) { false }
    }

    companion object {
        private const val TAG = "DeviceCapabilities"
        private const val PREFS_NAME = "device_capabilities"
        private const val KEY_PROBED = "probed"
        private const val KEY_APP_VERSION = "app_version"
        private const val KEY_HAS_DP = "has_dp"
        private const val KEY_MAX_PRE_EQ = "max_pre_eq"
        private const val KEY_MAX_POST_EQ = "max_post_eq"
        private const val KEY_HAS_MBC = "has_mbc"
        private const val KEY_HAS_LIMITER = "has_limiter"
        private const val KEY_HAS_STEREO = "has_stereo"
        private const val KEY_HAS_VIRTUALIZER = "has_virtualizer"
        private const val KEY_HAS_REVERB = "has_reverb"
        private const val KEY_HAS_VISUALIZER = "has_visualizer"
        private const val KEY_HAS_EQUALIZER = "has_equalizer"

        val THIRD_OCTAVE_31 = intArrayOf(
            20, 25, 31, 40, 50, 63, 80, 100, 125, 160,
            200, 250, 315, 400, 500, 630, 800, 1000, 1250, 1600,
            2000, 2500, 3150, 4000, 5000, 6300, 8000, 10000, 12500, 16000,
            20000
        )

        val HALF_OCTAVE_20 = intArrayOf(
            25, 40, 63, 100, 160, 250, 400, 630, 1000, 1600,
            2500, 4000, 6300, 10000, 16000, 20, 50, 125, 500, 8000
        ).also { it.sort() }

        val OCTAVE_10 = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

        @Volatile
        private var instance: DeviceCapabilities? = null

        fun getInstance(context: Context): DeviceCapabilities {
            return instance ?: synchronized(this) {
                instance ?: DeviceCapabilities(
                    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ).also { instance = it }
            }
        }
    }
}
