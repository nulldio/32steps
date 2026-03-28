package com.thirtytwo.steps

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class DeviceMappingsActivity : AppCompatActivity() {

    private lateinit var prefs: PrefsManager
    private lateinit var profileManager: SoundProfileManager
    private lateinit var mappingsContainer: LinearLayout
    private lateinit var labelSavedMappings: TextView
    private lateinit var labelEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_mappings)

        prefs = PrefsManager(this)
        profileManager = SoundProfileManager(this)
        mappingsContainer = findViewById(R.id.mappings_container)
        labelSavedMappings = findViewById(R.id.label_saved_mappings)
        labelEmpty = findViewById(R.id.label_empty)

        findViewById<MaterialButton>(R.id.btn_pair_device).setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            detectAndPairDevice()
        }

        refreshMappings()
    }

    override fun onResume() {
        super.onResume()
        refreshMappings()
    }

    // ── Detect connected devices ────────────────────────────────────────────

    private fun detectAndPairDevice() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val headphones = devices.filter {
            it.isSink && HeadphoneDetector.isHeadphoneType(it.type)
        }

        if (headphones.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("No headphones detected")
                .setMessage("Connect headphones via Bluetooth, USB, or the headphone jack, then try again.")
                .setPositiveButton("OK") { d, _ ->
                    d.dismiss()
                }
                .show()
            return
        }

        if (headphones.size == 1) {
            val device = headphones[0]
            showProfilePicker(HeadphoneDetector.deviceKey(device), HeadphoneDetector.deviceName(device))
        } else {
            // Multiple devices connected — let user pick which one
            val names = headphones.map { HeadphoneDetector.deviceName(it) }.toTypedArray()
            MaterialAlertDialogBuilder(this)
                .setTitle("Select device")
                .setItems(names) { _, which ->
                    val device = headphones[which]
                    showProfilePicker(HeadphoneDetector.deviceKey(device), HeadphoneDetector.deviceName(device))
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    // ── Profile picker dialog ───────────────────────────────────────────────

    private fun showProfilePicker(deviceKey: String, deviceName: String) {
        val userPresets = prefs.getPresets().map { it.headphoneName }

        // We'll build the dialog content programmatically for flexibility
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }

        val subtitle = TextView(this).apply {
            text = "Assign a sound profile to \"$deviceName\""
            setTextColor(getColorAttr(com.google.android.material.R.attr.colorOnSurfaceVariant))
            textSize = 13f
        }
        container.addView(subtitle)

        // Search field for AutoEQ
        val searchField = EditText(this).apply {
            hint = "Search AutoEQ profiles..."
            textSize = 14f
            setPadding(dp(4), dp(12), dp(4), dp(12))
            setTextColor(getColorAttr(com.google.android.material.R.attr.colorOnSurface))
            setHintTextColor(getColorAttr(com.google.android.material.R.attr.colorOnSurfaceVariant))
        }
        container.addView(searchField, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })

        // Results container
        val resultsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.addView(resultsContainer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Pick profile")
            .setView(container)
            .setNegativeButton("Cancel", null)
            .create()

        // Helper to create a clickable profile row
        fun addProfileRow(parent: LinearLayout, profileName: String, isUserPreset: Boolean) {
            val row = TextView(this).apply {
                text = if (isUserPreset) "$profileName (preset)" else profileName
                textSize = 14f
                setTextColor(getColorAttr(com.google.android.material.R.attr.colorOnSurface))
                setPadding(dp(4), dp(14), dp(4), dp(14))
                setOnClickListener { v ->
                    v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    saveMapping(deviceKey, profileName)
                    dialog.dismiss()
                }
            }
            parent.addView(row)
        }

        // Show user presets section
        if (userPresets.isNotEmpty()) {
            val header = TextView(this).apply {
                text = "Your presets"
                textSize = 12f
                setTextColor(getColorAttr(com.google.android.material.R.attr.colorPrimary))
                setPadding(dp(4), dp(8), dp(4), dp(4))
            }
            resultsContainer.addView(header)
            for (name in userPresets) {
                addProfileRow(resultsContainer, name, true)
            }
        }

        // AutoEQ search
        val searchHeader = TextView(this).apply {
            text = "AutoEQ results"
            textSize = 12f
            setTextColor(getColorAttr(com.google.android.material.R.attr.colorPrimary))
            setPadding(dp(4), dp(12), dp(4), dp(4))
            visibility = View.GONE
        }
        resultsContainer.addView(searchHeader)

        val autoEqContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        resultsContainer.addView(autoEqContainer)

        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim() ?: ""
                autoEqContainer.removeAllViews()
                if (query.length >= 2) {
                    val results = profileManager.searchProfiles(query)
                    searchHeader.visibility = if (results.isNotEmpty()) View.VISIBLE else View.GONE
                    for (profile in results) {
                        addProfileRow(autoEqContainer, profile.name, false)
                    }
                } else {
                    searchHeader.visibility = View.GONE
                }
            }
        })

        dialog.show()
    }

    // ── Save / delete mappings ──────────────────────────────────────────────

    private fun saveMapping(deviceKey: String, profileName: String) {
        val mappings = prefs.deviceProfileMappings.toMutableMap()
        mappings[deviceKey] = profileName
        prefs.deviceProfileMappings = mappings
        refreshMappings()
    }

    private fun deleteMapping(deviceKey: String) {
        val mappings = prefs.deviceProfileMappings.toMutableMap()
        mappings.remove(deviceKey)
        prefs.deviceProfileMappings = mappings
        refreshMappings()
    }

    // ── Refresh mapping list ────────────────────────────────────────────────

    private fun refreshMappings() {
        mappingsContainer.removeAllViews()
        val mappings = prefs.deviceProfileMappings

        if (mappings.isEmpty()) {
            labelSavedMappings.visibility = View.GONE
            labelEmpty.visibility = View.VISIBLE
            return
        }

        labelSavedMappings.visibility = View.VISIBLE
        labelEmpty.visibility = View.GONE

        for ((deviceKey, profileName) in mappings) {
            val displayName = deviceKey.substringBefore("|").ifBlank { "Unknown device" }
            val connectionType = deviceKey.substringAfter("|", "").replaceFirstChar { it.uppercase() }

            val card = MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(12) }
                radius = dp(24).toFloat()
                cardElevation = 0f
                strokeWidth = 0
                setCardBackgroundColor(getColorAttr(com.google.android.material.R.attr.colorSurfaceVariant))
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(22), dp(18), dp(14), dp(18))
            }

            val textContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val nameView = TextView(this).apply {
                text = displayName
                textSize = 15f
                setTextColor(getColorAttr(com.google.android.material.R.attr.colorOnSurface))
            }
            textContainer.addView(nameView)

            val profileView = TextView(this).apply {
                text = "$profileName  \u00B7  $connectionType"
                textSize = 12f
                setTextColor(getColorAttr(com.google.android.material.R.attr.colorOnSurfaceVariant))
                setPadding(0, dp(4), 0, 0)
            }
            textContainer.addView(profileView)

            row.addView(textContainer)

            val deleteBtn = ImageButton(this).apply {
                setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                setBackgroundResource(android.R.color.transparent)
                contentDescription = "Remove mapping"
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setOnClickListener { v ->
                    v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    MaterialAlertDialogBuilder(this@DeviceMappingsActivity)
                        .setTitle("Remove mapping?")
                        .setMessage("\"$displayName\" will no longer auto-switch to \"$profileName\".")
                        .setPositiveButton("Remove") { _, _ ->
                            deleteMapping(deviceKey)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            row.addView(deleteBtn)

            card.addView(row)
            mappingsContainer.addView(card)
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun getColorAttr(attr: Int): Int {
        val ta = obtainStyledAttributes(intArrayOf(attr))
        val color = ta.getColor(0, 0)
        ta.recycle()
        return color
    }
}
