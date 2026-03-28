package com.thirtytwo.steps

import android.media.audiofx.Visualizer
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Wraps Android's Visualizer API to provide real-time FFT magnitude data.
 * Captures from global audio output (session 0).
 */
class SpectrumAnalyzer(private val sessionId: Int = 0) {

    fun interface Listener {
        fun onFftData(magnitudes: FloatArray, sampleRateHz: Int)
    }

    private var visualizer: Visualizer? = null
    private var listener: Listener? = null

    fun start(callback: Listener) {
        if (visualizer != null) return // already running
        listener = callback
        try {
            val v = Visualizer(sessionId)
            val range = Visualizer.getCaptureSizeRange()
            val size = if (range != null && range.size >= 2) range[1].coerceAtMost(1024) else 512
            v.captureSize = size

            val rate = Visualizer.getMaxCaptureRate()
            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(vis: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}
                override fun onFftDataCapture(vis: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                    if (fft == null || fft.size < 4) return
                    val magnitudes = fftToMagnitudes(fft)
                    // Visualizer reports samplingRate in milliHz; convert to Hz
                    val srHz = samplingRate / 1000
                    listener?.onFftData(magnitudes, srHz)
                }
            }, rate, false, true)
            v.enabled = true
            visualizer = v
        } catch (_: Exception) {
            // Visualizer not available on this device/session
            visualizer = null
        }
    }

    fun stop() {
        try { visualizer?.enabled = false } catch (_: Exception) {}
    }

    fun release() {
        try {
            visualizer?.enabled = false
            visualizer?.release()
        } catch (_: Exception) {}
        visualizer = null
        listener = null
    }

    /**
     * Convert Visualizer FFT byte array to magnitude spectrum in dB.
     * Format: [DC_real, DC_imag, bin1_real, bin1_imag, ...]
     */
    private fun fftToMagnitudes(fft: ByteArray): FloatArray {
        val n = fft.size / 2
        if (n <= 1) return FloatArray(0)
        val magnitudes = FloatArray(n - 1)
        for (i in 1 until n) {
            val idx = 2 * i
            if (idx + 1 >= fft.size) break
            val real = fft[idx].toFloat()
            val imag = fft[idx + 1].toFloat()
            val mag = sqrt(real * real + imag * imag)
            magnitudes[i - 1] = if (mag > 0f) (20f * log10(mag / 128f)) else -96f
        }
        return magnitudes
    }
}
