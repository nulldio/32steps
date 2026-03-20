package com.thirtytwo.steps

import android.content.Context
import android.content.SharedPreferences

class PrefsManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("thirtytwo_steps", Context.MODE_PRIVATE)

    var totalSteps: Int
        get() = prefs.getInt(KEY_TOTAL_STEPS, DEFAULT_STEPS)
        set(value) = prefs.edit().putInt(KEY_TOTAL_STEPS, value.coerceIn(2, 1000)).apply()

    var currentStep: Int
        get() = prefs.getInt(KEY_CURRENT_STEP, 0)
        set(value) = prefs.edit().putInt(KEY_CURRENT_STEP, value).apply()

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var batterySetupDone: Boolean
        get() = prefs.getBoolean(KEY_BATTERY_DONE, false)
        set(value) = prefs.edit().putBoolean(KEY_BATTERY_DONE, value).apply()

    companion object {
        const val DEFAULT_STEPS = 32
        private const val KEY_TOTAL_STEPS = "total_steps"
        private const val KEY_CURRENT_STEP = "current_step"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_BATTERY_DONE = "battery_setup_done"
    }
}
