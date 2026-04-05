package com.thirtytwo.steps

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class StepsTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val prefs = PrefsManager(this)
        prefs.enabled = !prefs.enabled
        updateTile()
    }

    private fun updateTile() {
        val prefs = PrefsManager(this)
        val tile = qsTile ?: return
        tile.state = if (prefs.enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "32steps"
        tile.subtitle = if (prefs.enabled) "${prefs.totalSteps} steps" else "Off"
        tile.updateTile()
    }
}
