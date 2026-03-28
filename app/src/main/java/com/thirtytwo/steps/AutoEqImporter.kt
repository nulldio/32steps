package com.thirtytwo.steps

/**
 * Parses AutoEQ ParametricEQ.txt and FixedBandEQ.txt files.
 * Converts parametric filters to 10-band graphic EQ gains using BiquadMath.
 * This is the same conversion AutoEQ itself uses internally.
 */
object AutoEqImporter {

    private val GRAPHIC_FREQS = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

    data class ParsedFilter(
        val type: Int, // BiquadMath.TYPE_*
        val freq: Double,
        val gain: Double,
        val q: Double
    )

    data class ImportResult(
        val name: String,
        val bands: List<Pair<Int, Float>>
    )

    /**
     * Parse an AutoEQ file and return 10-band graphic EQ gains.
     * Supports both ParametricEQ.txt and FixedBandEQ.txt formats.
     * Also handles simple CSV (freq,gain per line).
     */
    fun parse(text: String, fileName: String = ""): ImportResult? {
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return null

        // Check for Wavelet GraphicEQ format: "GraphicEQ: 20 0; 21 0.5; 22 -1.2; ..."
        val graphicEqLine = lines.find { it.startsWith("GraphicEQ:", ignoreCase = true) }
        if (graphicEqLine != null) {
            return parseGraphicEq(graphicEqLine, fileName)
        }

        val filters = mutableListOf<ParsedFilter>()
        var preamp = 0f

        for (line in lines) {
            // Preamp line: "Preamp: -6.2 dB"
            if (line.startsWith("Preamp:", ignoreCase = true)) {
                val match = Regex("""Preamp:\s*(-?[\d.]+)\s*dB""", RegexOption.IGNORE_CASE).find(line)
                preamp = match?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
                continue
            }

            // Filter line: "Filter N: ON PK Fc 100 Hz Gain 5.2 dB Q 0.71"
            val filterMatch = Regex(
                """Filter\s*\d+:\s*ON\s+(\w+)\s+Fc\s+([\d.]+)\s*Hz\s+Gain\s+(-?[\d.]+)\s*dB\s+Q\s+([\d.]+)""",
                RegexOption.IGNORE_CASE
            ).find(line)

            if (filterMatch != null) {
                val typeStr = filterMatch.groupValues[1].uppercase()
                val freq = filterMatch.groupValues[2].toDoubleOrNull() ?: continue
                val gain = filterMatch.groupValues[3].toDoubleOrNull() ?: continue
                val q = filterMatch.groupValues[4].toDoubleOrNull() ?: continue

                val type = when (typeStr) {
                    "PK", "PEQ" -> BiquadMath.TYPE_PEAKING
                    "LSC", "LS", "LSQ" -> BiquadMath.TYPE_LOW_SHELF
                    "HSC", "HS", "HSQ" -> BiquadMath.TYPE_HIGH_SHELF
                    "LP", "LPQ" -> BiquadMath.TYPE_LOW_PASS
                    "HP", "HPQ" -> BiquadMath.TYPE_HIGH_PASS
                    else -> BiquadMath.TYPE_PEAKING
                }

                filters.add(ParsedFilter(type, freq, gain, q))
                continue
            }

            // Simple CSV: "1000,5.2" or "1000 5.2"
            val csvMatch = Regex("""^(\d+)[,\s]+(-?[\d.]+)$""").find(line)
            if (csvMatch != null) {
                val freq = csvMatch.groupValues[1].toDoubleOrNull() ?: continue
                val gain = csvMatch.groupValues[2].toDoubleOrNull() ?: continue
                filters.add(ParsedFilter(BiquadMath.TYPE_PEAKING, freq, gain, 1.41))
                continue
            }
        }

        if (filters.isEmpty()) return null

        // Check if this is already a fixed-band EQ (all peaking, Q ~1.41, at standard freqs)
        val isFixedBand = filters.all { it.type == BiquadMath.TYPE_PEAKING } &&
                filters.size in 5..10 &&
                filters.all { f -> GRAPHIC_FREQS.any { kotlin.math.abs(it - f.freq) < f.freq * 0.1 } }

        val bands: List<Pair<Int, Float>> = if (isFixedBand) {
            // Direct mapping: match each filter to nearest graphic band
            GRAPHIC_FREQS.map { targetFreq ->
                val closest = filters.minByOrNull { kotlin.math.abs(it.freq - targetFreq) }
                Pair(targetFreq, (closest?.gain?.toFloat() ?: 0f) + preamp)
            }
        } else {
            // Parametric: compute combined biquad response at graphic frequencies
            val coeffs = filters.map { f ->
                BiquadMath.designFilter(f.type, f.freq, f.gain, f.q)
            }
            val gains = BiquadMath.evaluateChain(coeffs, GRAPHIC_FREQS)
            GRAPHIC_FREQS.mapIndexed { i, freq ->
                Pair(freq, gains[i] + preamp)
            }
        }

        // Derive name from filename
        val name = fileName
            .removeSuffix(".txt")
            .removeSuffix(".csv")
            .replace("ParametricEQ", "")
            .replace("FixedBandEQ", "")
            .replace("_", " ")
            .trim()
            .ifBlank { "Imported profile" }

        return ImportResult(name, removePreampOffset(bands))
    }

    /**
     * GraphicEQ format bakes in a negative preamp offset to prevent clipping.
     * Remove it by shifting the curve so the peak value sits at 0 dB.
     * This preserves all relative frequency differences (the actual correction)
     * while matching the level of our built-in FixedBandEQ profiles.
     */
    private fun removePreampOffset(bands: List<Pair<Int, Float>>): List<Pair<Int, Float>> {
        if (bands.isEmpty()) return bands
        val peak = bands.maxOf { it.second }
        if (peak >= 0f) return bands // no offset to remove
        return bands.map { Pair(it.first, it.second - peak) }
    }

    /**
     * Parse Wavelet's GraphicEQ format: "GraphicEQ: 20 0; 21 0.5; 22 -1.2; ..."
     * 127 frequency-gain pairs separated by semicolons.
     *
     * Uses least-squares optimization to find the 10 band gains that best
     * reproduce the 127-point target curve — the same math AutoEQ uses
     * to generate FixedBandEQ from raw measurement data.
     */
    private fun parseGraphicEq(line: String, fileName: String): ImportResult? {
        val data = line.substringAfter("GraphicEQ:").trim()
        val freqs = mutableListOf<Int>()
        val gains = mutableListOf<Float>()

        for (entry in data.split(";")) {
            val parts = entry.trim().split("\\s+".toRegex())
            if (parts.size >= 2) {
                val freq = parts[0].toIntOrNull() ?: continue
                val gain = parts[1].toFloatOrNull() ?: continue
                freqs.add(freq)
                gains.add(gain)
            }
        }

        if (freqs.isEmpty()) return null

        // Remove baked-in preamp offset: center the target curve around 0 dB
        // before optimizing. The GraphicEQ format bakes in a negative preamp
        // to prevent clipping. Our FixedBandEQ profiles are centered around 0.
        val gainsArray = gains.toFloatArray()
        val mean = gainsArray.average().toFloat()
        for (i in gainsArray.indices) gainsArray[i] -= mean

        // Least-squares optimization: find 10 gains that best match the centered curve
        val optimized = BiquadMath.optimizeGraphicEq(
            freqs.toIntArray(),
            gainsArray,
            GRAPHIC_FREQS
        )

        val result = GRAPHIC_FREQS.mapIndexed { i, freq ->
            Pair(freq, optimized[i])
        }

        val name = fileName
            .removeSuffix(".txt")
            .removeSuffix(".csv")
            .replace("GraphicEQ", "")
            .replace("_", " ")
            .trim()
            .ifBlank { "Imported profile" }

        return ImportResult(name, result)
    }
}
