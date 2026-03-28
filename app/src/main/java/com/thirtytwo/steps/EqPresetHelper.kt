package com.thirtytwo.steps

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher

/**
 * Shared helper for EQ preset save/load/export/import dialogs.
 * Used by both GraphicEqActivity and ParametricEqActivity.
 */
object EqPresetHelper {

    fun showSaveDialog(context: Context, preset: EqPreset, onSaved: () -> Unit = {}) {
        val input = EditText(context)
        input.hint = "My EQ preset"
        input.setPadding(48, 32, 48, 32)
        input.setText(preset.name.takeIf { it != "Untitled" } ?: "")

        AlertDialog.Builder(context)
            .setTitle("Save EQ preset")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().takeIf { it.isNotBlank() } ?: "Untitled"
                val named = preset.copy(name = name)
                PrefsManager(context).saveEqPreset(named)
                onSaved()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun showLoadDialog(context: Context, onLoaded: (EqPreset) -> Unit) {
        val prefs = PrefsManager(context)
        val presets = prefs.getEqPresets()

        if (presets.isEmpty()) {
            AlertDialog.Builder(context)
                .setTitle("No saved presets")
                .setMessage("Save a preset first using the Save button")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val names = presets.map { it.name }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle("Load EQ preset")
            .setItems(names) { _, which ->
                onLoaded(presets[which])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun setupButtons(
        loadBtn: View,
        saveBtn: View,
        context: Context,
        getCurrentPreset: () -> EqPreset,
        onPresetLoaded: (EqPreset) -> Unit
    ) {
        saveBtn.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            showSaveDialog(context, getCurrentPreset())
        }
        loadBtn.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            showLoadDialog(context, onPresetLoaded)
        }
    }

    // ── File export/import ────────────────────────────────────────────────

    /** Export a preset to a JSON file via Storage Access Framework */
    fun exportPreset(@Suppress("UNUSED_PARAMETER") activity: Activity, preset: EqPreset, launcher: ActivityResultLauncher<Intent>) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "${preset.name}.json")
        }
        // Store preset temporarily for the result callback
        pendingExportPreset = preset
        launcher.launch(intent)
    }

    /** Call from the export ActivityResult callback */
    fun handleExportResult(context: Context, uri: Uri?) {
        val preset = pendingExportPreset ?: return
        pendingExportPreset = null
        if (uri == null) return
        try {
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(preset.toJson().toString(2).toByteArray())
            }
            Toast.makeText(context, "Exported: ${preset.name}", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(context, "Export failed", Toast.LENGTH_SHORT).show()
        }
    }

    /** Import a preset from a JSON file */
    fun importPreset(launcher: ActivityResultLauncher<Intent>) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        launcher.launch(intent)
    }

    /** Call from the import ActivityResult callback */
    fun handleImportResult(context: Context, uri: Uri?, onImported: (EqPreset) -> Unit) {
        if (uri == null) return
        try {
            val json = context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader().readText()
            } ?: return
            val obj = org.json.JSONObject(json)
            val preset = EqPreset.fromJson(obj)
            PrefsManager(context).saveEqPreset(preset)
            Toast.makeText(context, "Imported: ${preset.name}", Toast.LENGTH_SHORT).show()
            onImported(preset)
        } catch (_: Exception) {
            Toast.makeText(context, "Import failed — invalid file", Toast.LENGTH_SHORT).show()
        }
    }

    private var pendingExportPreset: EqPreset? = null
}
