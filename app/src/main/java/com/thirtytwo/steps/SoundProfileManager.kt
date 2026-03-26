package com.thirtytwo.steps

import android.content.Context
import android.media.audiofx.Equalizer
import org.json.JSONArray
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

data class HeadphoneProfile(
    val name: String,
    val category: String,
    val preamp: Float,
    val bands: List<Pair<Int, Float>>
)

class SoundProfileManager(private val context: Context) {

    private val equalizers = mutableMapOf<Int, Equalizer>()
    private var profiles: List<HeadphoneProfile>? = null

    fun loadProfiles(): List<HeadphoneProfile> {
        profiles?.let { return it }

        val stream = context.assets.open("headphones.dat")
        val gzip = GZIPInputStream(stream)
        val json = InputStreamReader(gzip).readText()
        gzip.close()

        val array = JSONArray(json)
        val list = mutableListOf<HeadphoneProfile>()

        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val bands = mutableListOf<Pair<Int, Float>>()
            val bandsArray = obj.getJSONArray("b")
            for (j in 0 until bandsArray.length()) {
                val band = bandsArray.getJSONArray(j)
                bands.add(band.getInt(0) to band.getDouble(1).toFloat())
            }
            list.add(HeadphoneProfile(
                name = obj.getString("n"),
                category = obj.getString("c"),
                preamp = obj.getDouble("p").toFloat(),
                bands = bands
            ))
        }

        profiles = list
        return list
    }

    fun searchProfiles(query: String): List<HeadphoneProfile> {
        val all = loadProfiles()
        if (query.isBlank()) return emptyList()
        val q = query.lowercase()
        return all.filter { it.name.lowercase().contains(q) }.take(50)
    }

    fun findProfile(name: String): HeadphoneProfile? {
        // Check custom profiles first
        val custom = loadCustomProfile(name)
        if (custom != null) return custom
        return loadProfiles().find { it.name == name }
    }

    fun saveCustomProfile(name: String, bands: List<Pair<Int, Float>>) {
        val prefs = context.getSharedPreferences("custom_profiles", android.content.Context.MODE_PRIVATE)
        val json = org.json.JSONObject()
        val bandsArray = org.json.JSONArray()
        for ((freq, gain) in bands) {
            val b = org.json.JSONArray()
            b.put(freq)
            b.put(gain.toDouble())
            bandsArray.put(b)
        }
        json.put("bands", bandsArray)
        prefs.edit().putString(name, json.toString()).apply()
    }

    private fun loadCustomProfile(name: String): HeadphoneProfile? {
        val prefs = context.getSharedPreferences("custom_profiles", android.content.Context.MODE_PRIVATE)
        val json = prefs.getString(name, null) ?: return null
        return try {
            val obj = org.json.JSONObject(json)
            val bandsArray = obj.getJSONArray("bands")
            val bands = mutableListOf<Pair<Int, Float>>()
            for (i in 0 until bandsArray.length()) {
                val b = bandsArray.getJSONArray(i)
                bands.add(b.getInt(0) to b.getDouble(1).toFloat())
            }
            HeadphoneProfile(name, "custom", 0f, bands)
        } catch (_: Exception) { null }
    }

    fun applyProfile(profile: HeadphoneProfile, sessionId: Int) {
        removeProfile(sessionId)
        try {
            // Use a unique session - not 0, to avoid conflicting with volume controller
            val eq = Equalizer(0, sessionId)
            eq.enabled = true

            val numBands = eq.numberOfBands.toInt()
            val range = eq.bandLevelRange

            for (i in 0 until numBands) {
                val centerFreq = eq.getCenterFreq(i.toShort()) / 1000 // milliHz to Hz
                // Interpolate gain from the two closest AutoEQ bands
                val sorted = profile.bands.sortedBy { kotlin.math.abs(it.first - centerFreq) }
                val gain = if (sorted.isNotEmpty()) sorted[0].second else 0f
                val gainMb = (gain * 100).toInt()
                val clamped = gainMb.toShort().coerceIn(range[0], range[1])
                eq.setBandLevel(i.toShort(), clamped)
            }

            equalizers[sessionId] = eq
        } catch (_: Exception) {}
    }

    fun removeProfile(sessionId: Int) {
        equalizers.remove(sessionId)?.apply {
            enabled = false
            release()
        }
    }

    fun removeAll() {
        for ((_, eq) in equalizers) {
            try { eq.enabled = false; eq.release() } catch (_: Exception) {}
        }
        equalizers.clear()
    }
}
