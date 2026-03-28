package com.thirtytwo.steps

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class PerAppEqActivity : AppCompatActivity() {

    private lateinit var prefs: PrefsManager
    private lateinit var listContainer: LinearLayout
    private lateinit var emptyView: TextView

    private data class AppInfo(
        val packageName: String,
        val label: String,
        val icon: Drawable?
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_per_app_eq)

        prefs = PrefsManager(this)
        listContainer = findViewById(R.id.per_app_eq_list)
        emptyView = findViewById(R.id.per_app_eq_empty)

        populateAppList()
    }

    override fun onResume() {
        super.onResume()
        populateAppList()
    }

    private fun populateAppList() {
        listContainer.removeAllViews()

        val recentApps = prefs.recentAudioApps
        val appInfos = recentApps.mapNotNull { pkg -> resolveAppInfo(pkg) }
            .sortedBy { it.label.lowercase() }

        if (appInfos.isEmpty()) {
            emptyView.visibility = View.VISIBLE
            listContainer.visibility = View.GONE
            return
        }

        emptyView.visibility = View.GONE
        listContainer.visibility = View.VISIBLE

        val mappings = prefs.appEqMappings
        val inflater = LayoutInflater.from(this)

        for (app in appInfos) {
            val row = inflater.inflate(R.layout.item_per_app_eq, listContainer, false)

            val iconView = row.findViewById<ImageView>(R.id.app_icon)
            val nameView = row.findViewById<TextView>(R.id.app_name)
            val presetView = row.findViewById<TextView>(R.id.app_preset)

            iconView.setImageDrawable(app.icon)
            nameView.text = app.label

            val currentPreset = mappings[app.packageName]
            presetView.text = currentPreset ?: "Default"

            row.setOnClickListener { view ->
                view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                showPresetPicker(app.packageName, presetView)
            }

            listContainer.addView(row)
        }
    }

    private fun resolveAppInfo(packageName: String): AppInfo? {
        return try {
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val label = pm.getApplicationLabel(appInfo).toString()
            val icon = pm.getApplicationIcon(appInfo)
            AppInfo(packageName, label, icon)
        } catch (_: PackageManager.NameNotFoundException) {
            // App uninstalled — skip it
            null
        }
    }

    private fun showPresetPicker(packageName: String, presetView: TextView) {
        val presets = prefs.getEqPresets()
        val names = mutableListOf("Default")
        names.addAll(presets.map { it.name })

        val currentMapping = prefs.appEqMappings[packageName]
        val checkedIndex = if (currentMapping == null) 0
            else names.indexOf(currentMapping).coerceAtLeast(0)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("EQ preset")
            .setSingleChoiceItems(names.toTypedArray(), checkedIndex) { dlg, which ->
                dlg.dismiss()

                val view = (dlg as? android.app.Dialog)?.window?.decorView
                    ?: window.decorView
                view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)

                val updatedMappings = prefs.appEqMappings.toMutableMap()
                if (which == 0) {
                    // "Default" selected — remove mapping
                    updatedMappings.remove(packageName)
                    presetView.text = "Default"
                } else {
                    val selectedName = names[which]
                    updatedMappings[packageName] = selectedName
                    presetView.text = selectedName
                }
                prefs.appEqMappings = updatedMappings
            }
            .setNegativeButton("Cancel") { dlg, _ ->
                dlg.dismiss()
            }
            .create()

        dialog.show()
    }
}
