package com.taptype.taptypepro.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.taptype.taptypepro.R
import com.taptype.taptypepro.service.TapTypeAccessibilityService
import com.taptype.taptypepro.util.Settings

/**
 * A SeekBar-based preference for the floating button size, with a live preview
 * that scales in real time as the user drags.
 */
class OrbSizePreference(context: Context, attrs: AttributeSet) : Preference(context, attrs) {

    companion object {
        const val MIN_DP = 40
        const val MAX_DP = 104
        const val DEFAULT_DP = 56
    }

    private var preview: ImageView? = null
    private var valueText: TextView? = null

    init {
        layoutResource = R.layout.preference_orb_size
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.itemView.isClickable = false

        val seekBar = holder.itemView.findViewById<SeekBar>(R.id.sizeSeekBar)
        preview = holder.itemView.findViewById(R.id.sizePreview)
        valueText = holder.itemView.findViewById(R.id.sizeValue)

        val current = Settings.orbSizeDp().coerceIn(MIN_DP, MAX_DP)
        seekBar.progress = current - MIN_DP
        updatePreview(current)

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                val dp = progress + MIN_DP
                valueText?.text = "${dp}dp"
                updatePreview(dp)
                if (fromUser) {
                    Settings.setOrbSizeDp(dp)
                    // Live-update the actual floating button if it's on screen.
                    TapTypeAccessibilityService.instance?.onOrbSizeChanged(dp)
                }
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
    }

    private fun updatePreview(dp: Int) {
        val px = (dp * context.resources.displayMetrics.density).toInt()
        preview?.layoutParams = preview?.layoutParams?.apply {
            width = px
            height = px
        }
        preview?.requestLayout()
    }
}
