package com.thirtytwo.steps

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class Preset(
    val headphoneName: String,
    val steps: Int
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

    var soundProfile: String?
        get() = prefs.getString(KEY_SOUND_PROFILE, null)
        set(value) = prefs.edit().putString(KEY_SOUND_PROFILE, value).apply()

    fun getPresets(): List<Preset> {
        val json = prefs.getString(KEY_PRESETS, null) ?: return emptyList()
        val array = JSONArray(json)
        val list = mutableListOf<Preset>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            list.add(Preset(obj.getString("name"), obj.getInt("steps")))
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
    }
}
