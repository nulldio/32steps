package com.thirtytwo.steps

import kotlin.math.*

/**
 * Biquad IIR filter design and frequency response evaluation.
 * Based on Robert Bristow-Johnson's Audio EQ Cookbook — the industry
 * standard reference for parametric EQ filter design.
 */
object BiquadMath {

    const val TYPE_PEAKING = 0
    const val TYPE_LOW_SHELF = 1
    const val TYPE_HIGH_SHELF = 2
    const val TYPE_LOW_PASS = 3
    const val TYPE_HIGH_PASS = 4

    private const val SAMPLE_RATE = 48000.0

    data class Coeffs(
        val b0: Double, val b1: Double, val b2: Double,
        val a0: Double, val a1: Double, val a2: Double
    )

    /**
     * Design a biquad filter using RBJ Audio EQ Cookbook formulas.
     * @param type TYPE_PEAKING, TYPE_LOW_SHELF, or TYPE_HIGH_SHELF
     * @param freq Center/corner frequency in Hz
     * @param gainDb Boost/cut in dB
     * @param q Q factor (bandwidth). Higher = narrower. 0.1 to 10.
     */
    fun designFilter(type: Int, freq: Double, gainDb: Double, q: Double): Coeffs {
        val A = 10.0.pow(gainDb / 40.0) // sqrt of linear gain
        val w0 = 2.0 * PI * freq / SAMPLE_RATE
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val alpha = sinW0 / (2.0 * q)

        return when (type) {
            TYPE_PEAKING -> {
                val b0 = 1.0 + alpha * A
                val b1 = -2.0 * cosW0
                val b2 = 1.0 - alpha * A
                val a0 = 1.0 + alpha / A
                val a1 = -2.0 * cosW0
                val a2 = 1.0 - alpha / A
                Coeffs(b0, b1, b2, a0, a1, a2)
            }
            TYPE_LOW_SHELF -> {
                val twoSqrtAAlpha = 2.0 * sqrt(A) * alpha
                val b0 = A * ((A + 1.0) - (A - 1.0) * cosW0 + twoSqrtAAlpha)
                val b1 = 2.0 * A * ((A - 1.0) - (A + 1.0) * cosW0)
                val b2 = A * ((A + 1.0) - (A - 1.0) * cosW0 - twoSqrtAAlpha)
                val a0 = (A + 1.0) + (A - 1.0) * cosW0 + twoSqrtAAlpha
                val a1 = -2.0 * ((A - 1.0) + (A + 1.0) * cosW0)
                val a2 = (A + 1.0) + (A - 1.0) * cosW0 - twoSqrtAAlpha
                Coeffs(b0, b1, b2, a0, a1, a2)
            }
            TYPE_HIGH_SHELF -> {
                val twoSqrtAAlpha = 2.0 * sqrt(A) * alpha
                val b0 = A * ((A + 1.0) + (A - 1.0) * cosW0 + twoSqrtAAlpha)
                val b1 = -2.0 * A * ((A - 1.0) + (A + 1.0) * cosW0)
                val b2 = A * ((A + 1.0) + (A - 1.0) * cosW0 - twoSqrtAAlpha)
                val a0 = (A + 1.0) - (A - 1.0) * cosW0 + twoSqrtAAlpha
                val a1 = 2.0 * ((A - 1.0) - (A + 1.0) * cosW0)
                val a2 = (A + 1.0) - (A - 1.0) * cosW0 - twoSqrtAAlpha
                Coeffs(b0, b1, b2, a0, a1, a2)
            }
            TYPE_LOW_PASS -> {
                val b0 = (1.0 - cosW0) / 2.0
                val b1 = 1.0 - cosW0
                val b2 = (1.0 - cosW0) / 2.0
                val a0 = 1.0 + alpha
                val a1 = -2.0 * cosW0
                val a2 = 1.0 - alpha
                Coeffs(b0, b1, b2, a0, a1, a2)
            }
            TYPE_HIGH_PASS -> {
                val b0 = (1.0 + cosW0) / 2.0
                val b1 = -(1.0 + cosW0)
                val b2 = (1.0 + cosW0) / 2.0
                val a0 = 1.0 + alpha
                val a1 = -2.0 * cosW0
                val a2 = 1.0 - alpha
                Coeffs(b0, b1, b2, a0, a1, a2)
            }
            else -> Coeffs(1.0, 0.0, 0.0, 1.0, 0.0, 0.0) // pass-through
        }
    }

    /**
     * Compute magnitude response in dB at a given frequency.
     * Evaluates |H(e^jw)| where w = 2*pi*f/fs.
     */
    fun magnitudeDb(coeffs: Coeffs, freq: Double): Double {
        val w = 2.0 * PI * freq / SAMPLE_RATE
        val cosW = cos(w)
        val cos2W = cos(2.0 * w)

        // |H(e^jw)|² = (b0² + b1² + b2² + 2*(b0*b1+b1*b2)*cos(w) + 2*b0*b2*cos(2w))
        //              / (a0² + a1² + a2² + 2*(a0*a1+a1*a2)*cos(w) + 2*a0*a2*cos(2w))
        val num = coeffs.b0 * coeffs.b0 + coeffs.b1 * coeffs.b1 + coeffs.b2 * coeffs.b2 +
                2.0 * (coeffs.b0 * coeffs.b1 + coeffs.b1 * coeffs.b2) * cosW +
                2.0 * coeffs.b0 * coeffs.b2 * cos2W

        val den = coeffs.a0 * coeffs.a0 + coeffs.a1 * coeffs.a1 + coeffs.a2 * coeffs.a2 +
                2.0 * (coeffs.a0 * coeffs.a1 + coeffs.a1 * coeffs.a2) * cosW +
                2.0 * coeffs.a0 * coeffs.a2 * cos2W

        if (den <= 0.0) return 0.0
        return 10.0 * log10(num / den)
    }

    /**
     * Evaluate a chain of cascaded filters at a set of frequencies.
     * Returns the combined magnitude response in dB at each frequency.
     */
    fun evaluateChain(filters: List<Coeffs>, freqs: IntArray): FloatArray {
        val result = FloatArray(freqs.size)
        for (i in freqs.indices) {
            var totalDb = 0.0
            for (c in filters) {
                totalDb += magnitudeDb(c, freqs[i].toDouble())
            }
            result[i] = totalDb.toFloat()
        }
        return result
    }

    /**
     * Evaluate chain at arbitrary float frequencies (for smooth curve plotting).
     */
    fun evaluateChainSmooth(filters: List<Coeffs>, freqs: FloatArray): FloatArray {
        val result = FloatArray(freqs.size)
        for (i in freqs.indices) {
            var totalDb = 0.0
            for (c in filters) {
                totalDb += magnitudeDb(c, freqs[i].toDouble())
            }
            result[i] = totalDb.toFloat()
        }
        return result
    }

    /**
     * Generate logarithmically spaced frequencies for smooth curve plotting.
     */
    fun logFrequencies(count: Int, minHz: Float = 20f, maxHz: Float = 20000f): FloatArray {
        val result = FloatArray(count)
        val logMin = ln(minHz.toDouble())
        val logMax = ln(maxHz.toDouble())
        for (i in 0 until count) {
            result[i] = exp(logMin + (logMax - logMin) * i / (count - 1)).toFloat()
        }
        return result
    }

    /**
     * Optimize 10-band graphic EQ gains to best match a detailed target curve.
     * Uses least-squares regression — the same approach AutoEQ uses to generate
     * FixedBandEQ from a high-resolution target response.
     *
     * Each graphic EQ band is modeled as a peaking filter with Q=1.41 (one octave).
     * We find the gains that minimize the squared error between the combined
     * 10-band response and the 127-point target at all frequencies.
     *
     * Math: g = (A^T * A)^(-1) * A^T * t
     * where A[i][j] = response of band j at frequency i, t = target gains
     */
    fun optimizeGraphicEq(
        targetFreqs: IntArray,
        targetGains: FloatArray,
        bandFreqs: IntArray,
        q: Double = 1.41
    ): FloatArray {
        val m = targetFreqs.size
        val n = bandFreqs.size

        // Response matrix: A[i][j] = dB response of unit-gain band j at target frequency i
        val A = Array(m) { i ->
            DoubleArray(n) { j ->
                val coeffs = designFilter(TYPE_PEAKING, bandFreqs[j].toDouble(), 1.0, q)
                magnitudeDb(coeffs, targetFreqs[i].toDouble())
            }
        }

        // A^T * A (n x n)
        val ATA = Array(n) { i ->
            DoubleArray(n) { j ->
                var sum = 0.0
                for (k in 0 until m) sum += A[k][i] * A[k][j]
                sum
            }
        }

        // A^T * t (n x 1)
        val ATt = DoubleArray(n) { i ->
            var sum = 0.0
            for (k in 0 until m) sum += A[k][i] * targetGains[k].toDouble()
            sum
        }

        return solveLinearSystem(ATA, ATt).map { it.toFloat() }.toFloatArray()
    }

    /** Gaussian elimination with partial pivoting for solving Ax = b */
    private fun solveLinearSystem(A: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val n = b.size
        val aug = Array(n) { i ->
            DoubleArray(n + 1) { j -> if (j < n) A[i][j] else b[i] }
        }

        for (col in 0 until n) {
            // Partial pivoting
            var maxRow = col
            for (row in col + 1 until n) {
                if (Math.abs(aug[row][col]) > Math.abs(aug[maxRow][col])) maxRow = row
            }
            val temp = aug[col]; aug[col] = aug[maxRow]; aug[maxRow] = temp

            val pivot = aug[col][col]
            if (Math.abs(pivot) < 1e-10) continue

            for (row in col + 1 until n) {
                val factor = aug[row][col] / pivot
                for (j in col..n) aug[row][j] -= factor * aug[col][j]
            }
        }

        // Back substitution
        val x = DoubleArray(n)
        for (i in n - 1 downTo 0) {
            var sum = aug[i][n]
            for (j in i + 1 until n) sum -= aug[i][j] * x[j]
            x[i] = if (Math.abs(aug[i][i]) > 1e-10) sum / aug[i][i] else 0.0
        }
        return x
    }

    /**
     * Interpolate source frequency/gain pairs onto a target frequency grid.
     * Uses linear interpolation in log-frequency domain.
     */
    fun interpolateToGrid(source: List<Pair<Int, Float>>, targetFreqs: IntArray): FloatArray {
        if (source.isEmpty()) return FloatArray(targetFreqs.size)
        val sorted = source.sortedBy { it.first }
        val result = FloatArray(targetFreqs.size)

        for (i in targetFreqs.indices) {
            val logF = ln(targetFreqs[i].toDouble())

            // Find bracketing source points
            val upperIdx = sorted.indexOfFirst { ln(it.first.toDouble()) >= logF }

            result[i] = when {
                upperIdx <= 0 -> sorted.first().second
                upperIdx >= sorted.size -> sorted.last().second
                else -> {
                    val lo = sorted[upperIdx - 1]
                    val hi = sorted[upperIdx]
                    val logLo = ln(lo.first.toDouble())
                    val logHi = ln(hi.first.toDouble())
                    val t = if (logHi != logLo) (logF - logLo) / (logHi - logLo) else 0.5
                    (lo.second + (hi.second - lo.second) * t).toFloat()
                }
            }
        }
        return result
    }
}
