package com.thirtytwo.steps

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: PrefsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = PrefsManager(this)

        val hideOverlay = findViewById<MaterialSwitch>(R.id.switch_hide_overlay)
        hideOverlay.isChecked = prefs.hideOverlay

        hideOverlay.setOnCheckedChangeListener { _, isChecked ->
            prefs.hideOverlay = isChecked
        }
    }
}
