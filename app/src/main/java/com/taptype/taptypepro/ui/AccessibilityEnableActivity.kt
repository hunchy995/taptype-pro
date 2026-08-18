package com.taptype.taptypepro.ui

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.taptype.taptypepro.R
import com.taptype.taptypepro.service.TapTypeAccessibilityService
import com.taptype.taptypepro.util.DebugLog

/**
 * Lightweight dialog-style activity launched from the Quick Settings tile when the
 * TapType accessibility service is disabled. Shows a dialog that directs the user to
 * re-enable the accessibility service. Using a dedicated dialog activity avoids the
 * heavy initialization of MainActivity and works around OEM restrictions on starting
 * a normal launcher activity from a TileService.
 */
class AccessibilityEnableActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AccessibilityEnable"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugLog.i(TAG, "Showing accessibility enable dialog from tile")
        showEnableDialog()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        DebugLog.i(TAG, "New intent received")
    }

    private fun showEnableDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.enable_taptype)
            .setMessage(R.string.accessibility_message)
            .setCancelable(true)
            .setPositiveButton(R.string.open_accessibility) { _, _ ->
                openTapTypeAccessibilitySettings()
                finish()
            }
            .setNegativeButton(R.string.app_info) { _, _ ->
                val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                i.data = Uri.parse("package:$packageName")
                startActivity(i)
                finish()
            }
            .setOnDismissListener { finish() }
            .show()
    }

    /**
     * Opens the SPECIFIC TapType accessibility settings page (the page with the on/off
     * toggle), falling back to the general accessibility list on devices that don't
     * support ACTION_ACCESSIBILITY_DETAILS_SETTINGS.
     */
    private fun openTapTypeAccessibilitySettings() {
        try {
            val component = ComponentName(packageName, TapTypeAccessibilityService::class.java.name)
            // Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS is not exposed as a public constant,
            // so use its string value. Opens the specific TapType toggle page.
            val intent = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").apply {
                putExtra(Intent.EXTRA_COMPONENT_NAME, component)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            DebugLog.e(TAG, "Direct accessibility settings failed, falling back", e)
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (e2: Exception) {
                DebugLog.e(TAG, "Fallback accessibility settings also failed", e2)
            }
        }
    }
}
