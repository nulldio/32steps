package com.thirtytwo.steps

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class ParametricBand(
    val enabled: Boolean = true,
    val type: Int = BiquadMath.TYPE_PEAKING,
    val frequency: Float = 1000f,
    val gain: Float = 0f,
    val q: Float = 1.0f
)

data class Preset(
    val headphoneName: String,
    val steps: Int,
    val ringVolume: Int = -1,
    val notificationVolume: Int = -1,
    val alarmVolume: Int = -1,
    val callVolume: Int = -1
)

class PrefsManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("thirtytwo_steps", Context.MODE_PRIVATE)

    var totalSteps: Int
        get() = prefs.getInt(KEY_TOTAL_STEPS, DEFAULT_STEPS)
        set(value) = prefs.edit().putInt(KEY_TOTAL_STEPS, value.coerceIn(1, 1000)).apply()

    var currentStep: Int
        get() = prefs.getInt(KEY_CURRENT_STEP, 0)
        set(value) = prefs.edit().putInt(KEY_CURRENT_STEP, value).apply()

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var batterySetupDone: Boolean
        get() = prefs.getBoolean(KEY_BATTERY_DONE, false)
        set(value) = prefs.edit().putBoolean(KEY_BATTERY_DONE, value).apply()

    var hideFromScreenshots: Boolean
        get() = prefs.getBoolean(KEY_HIDE_SCREENSHOTS, false)
        set(value) = prefs.edit().putBoolean(KEY_HIDE_SCREENSHOTS, value).apply()

    var hideOverlay: Boolean
        get() = prefs.getBoolean(KEY_HIDE_OVERLAY, false)
        set(value) = prefs.edit().putBoolean(KEY_HIDE_OVERLAY, value).apply()

    var soundProfile: String?
        get() = prefs.getString(KEY_SOUND_PROFILE, null)
        set(value) = prefs.edit().putString(KEY_SOUND_PROFILE, value).apply()

    /** Manual preamp in dB (-12 to +12, default 0) */
    var preampDb: Float
        get() = prefs.getFloat(KEY_PREAMP, 0f)
        set(value) = prefs.edit().putFloat(KEY_PREAMP, value.coerceIn(-12f, 12f)).apply()

    // Audio effects

    var bassBoostDb: Float
        get() = prefs.getFloat(KEY_BASS_BOOST, 0f)
        set(value) = prefs.edit().putFloat(KEY_BASS_BOOST, value.coerceIn(0f, 12f)).apply()

    /** Bass tuner cutoff frequency in Hz (40-250) */
    var bassCutoffHz: Int
        get() = prefs.getInt(KEY_BASS_CUTOFF, 80)
        set(value) = prefs.edit().putInt(KEY_BASS_CUTOFF, value.coerceIn(40, 250)).apply()

    var limiterEnabled: Boolean
        get() = prefs.getBoolean(KEY_LIMITER, false)
        set(value) = prefs.edit().putBoolean(KEY_LIMITER, value).apply()

    /** -1.0 = full left, 0.0 = center, 1.0 = full right */
    var channelBalance: Float
        get() = prefs.getFloat(KEY_CHANNEL_BALANCE, 0f)
        set(value) = prefs.edit().putFloat(KEY_CHANNEL_BALANCE, value.coerceIn(-1f, 1f)).apply()

    var reverbEnabled: Boolean
        get() = prefs.getBoolean(KEY_REVERB_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_REVERB_ENABLED, value).apply()

    /** 0=Small Room, 1=Medium Room, 2=Large Hall, 3=Cathedral */
    var reverbPreset: Int
        get() = prefs.getInt(KEY_REVERB_PRESET, 0)
        set(value) = prefs.edit().putInt(KEY_REVERB_PRESET, value.coerceIn(0, 3)).apply()

    var virtualizerEnabled: Boolean
        get() = prefs.getBoolean(KEY_VIRTUALIZER_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_VIRTUALIZER_ENABLED, value).apply()

    /** Virtualizer strength 0-1000 */
    var virtualizerStrength: Int
        get() = prefs.getInt(KEY_VIRTUALIZER_STRENGTH, 500)
        set(value) = prefs.edit().putInt(KEY_VIRTUALIZER_STRENGTH, value.coerceIn(0, 1000)).apply()

    var equalLoudnessEnabled: Boolean
        get() = prefs.getBoolean(KEY_EQUAL_LOUDNESS, false)
        set(value) = prefs.edit().putBoolean(KEY_EQUAL_LOUDNESS, value).apply()

    /** Equal loudness volume threshold in dB (0 to -40). Compensation starts below this level. */
    var equalLoudnessThresholdDb: Int
        get() = prefs.getInt(KEY_EL_THRESHOLD, -20)
        set(value) = prefs.edit().putInt(KEY_EL_THRESHOLD, value.coerceIn(-40, 0)).apply()

    /** 0=Natural, 1=Transient compressor, 2=Sustain compressor */
    var bassType: Int
        get() = prefs.getInt(KEY_BASS_TYPE, 0)
        set(value) = prefs.edit().putInt(KEY_BASS_TYPE, value.coerceIn(0, 2)).apply()

    var crossfeedEnabled: Boolean
        get() = prefs.getBoolean(KEY_CROSSFEED_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_CROSSFEED_ENABLED, value).apply()

    /** Crossfeed strength 0-1000 (default 250 = subtle speaker simulation) */
    var crossfeedStrength: Int
        get() = prefs.getInt(KEY_CROSSFEED_STRENGTH, 250)
        set(value) = prefs.edit().putInt(KEY_CROSSFEED_STRENGTH, value.coerceIn(0, 1000)).apply()

    var autoHeadphoneDetection: Boolean
        get() = prefs.getBoolean(KEY_AUTO_HEADPHONE, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_HEADPHONE, value).apply()

    /** device key -> profile name */
    var deviceProfileMappings: Map<String, String>
        get() {
            val json = prefs.getString(KEY_DEVICE_MAPPINGS, null) ?: return emptyMap()
            return try {
                val obj = JSONObject(json)
                obj.keys().asSequence().associateWith { obj.getString(it) }
            } catch (_: Exception) { emptyMap() }
        }
        set(value) {
            val obj = JSONObject()
            for ((k, v) in value) obj.put(k, v)
            prefs.edit().putString(KEY_DEVICE_MAPPINGS, obj.toString()).apply()
        }

    var profileBeforeAutoSwitch: String?
        get() = prefs.getString(KEY_PROFILE_BEFORE_AUTO, null)
        set(value) = prefs.edit().putString(KEY_PROFILE_BEFORE_AUTO, value).apply()

    var perAppEqEnabled: Boolean
        get() = prefs.getBoolean(KEY_PER_APP_EQ, false)
        set(value) = prefs.edit().putBoolean(KEY_PER_APP_EQ, value).apply()

    /** package name -> EQ preset name */
    var appEqMappings: Map<String, String>
        get() {
            val json = prefs.getString(KEY_APP_EQ_MAPPINGS, null) ?: return emptyMap()
            return try {
                val obj = JSONObject(json)
                obj.keys().asSequence().associateWith { obj.getString(it) }
            } catch (_: Exception) { emptyMap() }
        }
        set(value) {
            val obj = JSONObject()
            for ((k, v) in value) obj.put(k, v)
            prefs.edit().putString(KEY_APP_EQ_MAPPINGS, obj.toString()).apply()
        }

    var recentAudioApps: Set<String>
        get() = prefs.getStringSet(KEY_RECENT_AUDIO_APPS, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_RECENT_AUDIO_APPS, value).apply()

    /** 0=off, 1=graphic, 2=parametric */
    var eqMode: Int
        get() = prefs.getInt(KEY_EQ_MODE, 0)
        set(value) = prefs.edit().putInt(KEY_EQ_MODE, value.coerceIn(0, 2)).apply()

    /** 10 graphic EQ user gains as JSON float array */
    var graphicEqGains: FloatArray?
        get() {
            val json = prefs.getString(KEY_GRAPHIC_EQ_GAINS, null) ?: return null
            return try {
                val arr = JSONArray(json)
                FloatArray(arr.length()) { arr.getDouble(it).toFloat() }
            } catch (_: Exception) { null }
        }
        set(value) {
            if (value == null) {
                prefs.edit().remove(KEY_GRAPHIC_EQ_GAINS).apply()
            } else {
                val arr = JSONArray()
                for (v in value) arr.put(v.toDouble())
                prefs.edit().putString(KEY_GRAPHIC_EQ_GAINS, arr.toString()).apply()
            }
        }

    var parametricBands: List<ParametricBand>
        get() {
            val json = prefs.getString(KEY_PARAMETRIC_BANDS, null) ?: return emptyList()
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    ParametricBand(
                        enabled = obj.optBoolean("on", true),
                        type = obj.optInt("type", BiquadMath.TYPE_PEAKING),
                        frequency = obj.optDouble("freq", 1000.0).toFloat(),
                        gain = obj.optDouble("gain", 0.0).toFloat(),
                        q = obj.optDouble("q", 1.0).toFloat()
                    )
                }
            } catch (_: Exception) { emptyList() }
        }
        set(value) {
            val arr = JSONArray()
            for (b in value) {
                val obj = JSONObject()
                obj.put("on", b.enabled)
                obj.put("type", b.type)
                obj.put("freq", b.frequency.toDouble())
                obj.put("gain", b.gain.toDouble())
                obj.put("q", b.q.toDouble())
                arr.put(obj)
            }
            prefs.edit().putString(KEY_PARAMETRIC_BANDS, arr.toString()).apply()
        }

    // EQ Preset management

    fun getEqPresets(): List<EqPreset> {
        val json = prefs.getString(KEY_EQ_PRESETS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { EqPreset.fromJson(arr.getJSONObject(it)) }
        } catch (_: Exception) { emptyList() }
    }

    fun saveEqPreset(preset: EqPreset) {
        val list = getEqPresets().toMutableList()
        val idx = list.indexOfFirst { it.name == preset.name }
        if (idx >= 0) list[idx] = preset else list.add(preset)
        val arr = JSONArray()
        for (p in list) arr.put(p.toJson())
        prefs.edit().putString(KEY_EQ_PRESETS, arr.toString()).apply()
    }

    fun deleteEqPreset(name: String) {
        val list = getEqPresets().toMutableList()
        list.removeAll { it.name == name }
        val arr = JSONArray()
        for (p in list) arr.put(p.toJson())
        prefs.edit().putString(KEY_EQ_PRESETS, arr.toString()).apply()
    }

    fun getPresets(): List<Preset> {
        val json = prefs.getString(KEY_PRESETS, null) ?: return emptyList()
        val array = JSONArray(json)
        val list = mutableListOf<Preset>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            list.add(Preset(
                obj.getString("name"),
                obj.getInt("steps"),
                obj.optInt("ring", -1),
                obj.optInt("notif", -1),
                obj.optInt("alarm", -1),
                obj.optInt("call", -1)
            ))
        }
        return list
    }

    fun addPreset(preset: Preset) {
        val presets = getPresets().toMutableList()
        // Update in place if exists, otherwise add at end
        val index = presets.indexOfFirst { it.headphoneName == preset.headphoneName }
        if (index >= 0) {
            presets[index] = preset
        } else {
            presets.add(preset)
        }
        savePresets(presets)
    }

    fun removePreset(headphoneName: String) {
        val presets = getPresets().toMutableList()
        presets.removeAll { it.headphoneName == headphoneName }
        savePresets(presets)
    }

    private fun savePresets(presets: List<Preset>) {
        val array = JSONArray()
        for (p in presets) {
            val obj = JSONObject()
            obj.put("name", p.headphoneName)
            obj.put("steps", p.steps)
            if (p.ringVolume >= 0) obj.put("ring", p.ringVolume)
            if (p.notificationVolume >= 0) obj.put("notif", p.notificationVolume)
            if (p.alarmVolume >= 0) obj.put("alarm", p.alarmVolume)
            if (p.callVolume >= 0) obj.put("call", p.callVolume)
            array.put(obj)
        }
        prefs.edit().putString(KEY_PRESETS, array.toString()).apply()
    }

    companion object {
        const val DEFAULT_STEPS = 32
        private const val KEY_TOTAL_STEPS = "total_steps"
        private const val KEY_CURRENT_STEP = "current_step"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_BATTERY_DONE = "battery_setup_done"
        private const val KEY_SOUND_PROFILE = "sound_profile"
        private const val KEY_PRESETS = "presets"
        private const val KEY_HIDE_SCREENSHOTS = "hide_from_screenshots"
        private const val KEY_HIDE_OVERLAY = "hide_overlay"
        private const val KEY_BASS_BOOST = "bass_boost_db"
        private const val KEY_BASS_CUTOFF = "bass_cutoff_hz"
        private const val KEY_LIMITER = "limiter_enabled"
        private const val KEY_CHANNEL_BALANCE = "channel_balance"
        private const val KEY_REVERB_ENABLED = "reverb_enabled"
        private const val KEY_REVERB_PRESET = "reverb_preset"
        private const val KEY_VIRTUALIZER_ENABLED = "virtualizer_enabled"
        private const val KEY_VIRTUALIZER_STRENGTH = "virtualizer_strength"
        private const val KEY_EQUAL_LOUDNESS = "equal_loudness"
        private const val KEY_EL_THRESHOLD = "equal_loudness_threshold"
        private const val KEY_BASS_TYPE = "bass_type"
        private const val KEY_PREAMP = "preamp_db"
        private const val KEY_EQ_PRESETS = "eq_presets"
        private const val KEY_CROSSFEED_ENABLED = "crossfeed_enabled"
        private const val KEY_CROSSFEED_STRENGTH = "crossfeed_strength"
        private const val KEY_AUTO_HEADPHONE = "auto_headphone_detection"
        private const val KEY_DEVICE_MAPPINGS = "device_profile_mappings"
        private const val KEY_PROFILE_BEFORE_AUTO = "profile_before_auto_switch"
        private const val KEY_PER_APP_EQ = "per_app_eq_enabled"
        private const val KEY_APP_EQ_MAPPINGS = "app_eq_mappings"
        private const val KEY_RECENT_AUDIO_APPS = "recent_audio_apps"
        private const val KEY_EQ_MODE = "eq_mode"
        private const val KEY_GRAPHIC_EQ_GAINS = "graphic_eq_gains"
        private const val KEY_PARAMETRIC_BANDS = "parametric_bands"
    }
}
