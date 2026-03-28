package com.thirtytwo.steps

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import kotlin.math.ln
import kotlin.math.max

/**
 * Real-time spectrum analyzer visualization.
 * Displays audio frequency spectrum as smoothed bars with peak hold indicators.
 */
class SpectrumAnalyzerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val barCount = 32
    private val minDb = -72f
    private val maxDb = 0f

    private val currentLevels = FloatArray(barCount) { minDb }
    private val peakLevels = FloatArray(barCount) { minDb }
    private val peakHoldTime = LongArray(barCount)
    private val smoothing = 0.65f
    private val peakDecayRate = 0.15f // dB per frame
    private val peakHoldMs = 800L

    // Paints
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; alpha = 180
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val cardRadius: Float
    private val barGap: Float

    init {
        val accent = resolveColor(com.google.android.material.R.attr.colorPrimary, 0xFF80CBC4.toInt())
        val surfaceVariant = resolveColor(com.google.android.material.R.attr.colorSurfaceVariant, 0xFF2C2C2C.toInt())

        barPaint.color = accent
        peakPaint.color = accent
        bgPaint.color = surfaceVariant
        cardRadius = dp(16f)
        barGap = dp(1.5f)
    }

    /** Feed FFT magnitude data. Called from SpectrumAnalyzer callback. */
    fun updateFft(magnitudes: FloatArray, sampleRate: Int) {
        // Map FFT bins to our display bars (logarithmic frequency grouping)
        val binCount = magnitudes.size
        val nyquist = sampleRate / 2f
        val now = System.currentTimeMillis()

        for (bar in 0 until barCount) {
            // Map bar index to frequency range (log scale, 20-20000 Hz)
            val fLow = 20f * Math.pow((20000.0 / 20.0), bar.toDouble() / barCount).toFloat()
            val fHigh = 20f * Math.pow((20000.0 / 20.0), (bar + 1.0) / barCount).toFloat()

            // Find FFT bins in this frequency range
            val binLow = ((fLow / nyquist) * binCount).toInt().coerceIn(0, binCount - 1)
            val binHigh = ((fHigh / nyquist) * binCount).toInt().coerceIn(binLow, binCount - 1)

            // Average magnitudes in this range
            var sum = minDb
            var count = 0
            for (b in binLow..binHigh) {
                sum = if (count == 0) magnitudes[b] else max(sum, magnitudes[b])
                count++
            }
            val rawDb = if (count > 0) sum else minDb

            // Smooth
            currentLevels[bar] = currentLevels[bar] * smoothing + rawDb * (1f - smoothing)

            // Peak hold
            if (currentLevels[bar] > peakLevels[bar]) {
                peakLevels[bar] = currentLevels[bar]
                peakHoldTime[bar] = now
            } else if (now - peakHoldTime[bar] > peakHoldMs) {
                peakLevels[bar] -= peakDecayRate
            }
        }

        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()

        // Background card
        canvas.drawRoundRect(0f, 0f, w, h, cardRadius, cardRadius, bgPaint)

        val pad = dp(8f)
        val chartLeft = pad
        val chartRight = w - pad
        val chartTop = pad
        val chartBottom = h - pad
        val chartH = chartBottom - chartTop
        val chartW = chartRight - chartLeft

        if (chartW <= 0 || chartH <= 0) return

        val totalBarWidth = chartW / barCount
        val barWidth = totalBarWidth - barGap

        for (i in 0 until barCount) {
            val x = chartLeft + i * totalBarWidth
            val level = ((currentLevels[i] - minDb) / (maxDb - minDb)).coerceIn(0f, 1f)
            val barH = level * chartH
            val top = chartBottom - barH

            // Bar
            canvas.drawRoundRect(x, top, x + barWidth, chartBottom, barGap, barGap, barPaint)

            // Peak indicator
            val peakLevel = ((peakLevels[i] - minDb) / (maxDb - minDb)).coerceIn(0f, 1f)
            val peakY = chartBottom - peakLevel * chartH
            canvas.drawRect(x, peakY, x + barWidth, peakY + dp(2f), peakPaint)
        }
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun resolveColor(attr: Int, fallback: Int): Int {
        val tv = TypedValue(); return if (context.theme.resolveAttribute(attr, tv, true)) tv.data else fallback
    }
}
