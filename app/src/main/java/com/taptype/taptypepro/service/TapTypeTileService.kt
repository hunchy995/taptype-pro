package com.taptype.taptypepro.service

import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.taptype.taptypepro.R
import com.taptype.taptypepro.util.DebugLog

class TapTypeTileService : TileService() {

    companion object {
        private const val TAG = "TapTypeTile"
    }

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        if (isAccessibilityEnabled()) {
            DebugLog.i(TAG, "Tile clicked: disabling accessibility service")
            // Post the re-enable notification BEFORE disabling, because disableSelf() may kill
            // the service/process and the notification needs to survive independently.
            com.taptype.taptypepro.util.ReenableNotification.show(this)
            TapTypeAccessibilityService.instance?.disableSelf()
            updateTile(Tile.STATE_INACTIVE)
        } else {
            // Re-enable: do NOT rely on startActivityAndCollapse — ColorOS silently drops
            // activity launches from a tile. Instead, (re)post the notification, whose
            // PendingIntent is granted the activity-launch privilege tiles don't get.
            DebugLog.i(TAG, "Tile clicked while disabled: posting re-enable notification")
            com.taptype.taptypepro.util.ReenableNotification.show(this)
        }
    }

    private fun refreshTile() {
        val enabled = isAccessibilityEnabled()
        updateTile(if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE)
    }

    private fun updateTile(state: Int) {
        qsTile?.apply {
            this.state = state
            if (state == Tile.STATE_ACTIVE) {
                label = getString(R.string.tile_taptype_active)
                contentDescription = getString(R.string.tile_taptype_active)
            } else {
                label = getString(R.string.tile_taptype_inactive)
                contentDescription = getString(R.string.tile_taptype_inactive)
            }
            updateTile()
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val componentName = ComponentName(packageName, TapTypeAccessibilityService::class.java.name)
        return enabledServices.contains(componentName.flattenToString()) ||
                enabledServices.contains(componentName.flattenToShortString())
    }
}
