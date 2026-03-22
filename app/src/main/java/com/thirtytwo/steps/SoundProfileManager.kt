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

    private val popular = setOf(
        "apple airpods", "apple airpods pro", "apple airpods pro 2", "apple airpods max",
        "apple airpods 4", "samsung galaxy buds", "samsung galaxy buds pro",
        "samsung galaxy buds2", "samsung galaxy buds2 pro", "samsung galaxy buds fe",
        "samsung galaxy buds live", "sony wh-1000xm4", "sony wh-1000xm5",
        "sony wf-1000xm4", "sony wf-1000xm5", "bose quietcomfort",
        "bose quietcomfort 45", "bose quietcomfort ultra", "bose 700",
        "sennheiser momentum 4", "sennheiser hd 560s", "sennheiser hd 600",
        "google pixel buds pro", "google pixel buds a-series",
        "jbl tune 510bt", "jbl tune 720bt", "nothing ear", "nothing ear (2)",
        "koss porta pro", "koss kph30i", "audio-technica ath-m50x"
    )

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
        if (query.isBlank()) {
            // Show popular first, then rest
            val popularList = all.filter { p ->
                popular.any { p.name.lowercase().contains(it) }
            }.sortedBy { it.name }
            return popularList.take(30)
        }
        val q = query.lowercase()
        return all.filter { it.name.lowercase().contains(q) }.take(50)
    }

    fun findProfile(name: String): HeadphoneProfile? {
        return loadProfiles().find { it.name == name }
    }

    fun applyProfile(profile: HeadphoneProfile, sessionId: Int) {
        removeProfile(sessionId)
        try {
            val eq = Equalizer(0, sessionId)
            eq.enabled = true

            val numBands = eq.numberOfBands.toInt()
            val range = eq.bandLevelRange

            // Map AutoEQ band gains to Android's actual band center frequencies
            for (i in 0 until numBands) {
                val centerFreq = eq.getCenterFreq(i.toShort()) / 1000 // milliHz to Hz
                // Find closest AutoEQ band by frequency
                val closest = profile.bands.minByOrNull {
                    kotlin.math.abs(it.first - centerFreq)
                }
                val gain = closest?.second ?: 0f
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
