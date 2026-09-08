package com.taptype.taptypepro.util

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Applies system-bar (status bar + navigation bar) insets as padding on a root view.
 *
 * Android 15 (targetSdk 35) enforces edge-to-edge: content draws UNDER the status bar
 * and navigation bar, and the framework no longer auto-insets it. Without this, every
 * screen bleeds vertically — the title sits under the status bar at the top and the
 * bottom content hides under the nav bar.
 *
 * Best practice: apply the system-bar insets as padding on each screen's root view.
 * The root's own base padding (e.g. 16dp) is captured once and preserved, so the inset
 * is added on top rather than clobbering it.
 */
object WindowInsetsHelper {

    fun apply(root: View) {
        // Capture the layout's own padding once, before any inset is applied.
        val baseLeft = root.paddingLeft
        val baseTop = root.paddingTop
        val baseRight = root.paddingRight
        val baseBottom = root.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                baseLeft + bars.left,
                baseTop + bars.top,
                baseRight + bars.right,
                baseBottom + bars.bottom
            )
            insets
        }
    }
}
