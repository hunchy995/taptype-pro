package com.taptype.taptypepro.view

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.taptype.taptypepro.R

/**
 * A small floating text bubble that shows the live (partial) transcription while
 * recording. It is a SEPARATE overlay window from the orb — its position is
 * derived from the orb's params.x/y, but it never touches the orb's LayoutParams
 * and never animates its own LayoutParams (only sets x/y directly when the text
 * updates, which is a discrete reposition, not an animation).
 *
 * The final injected text is NOT taken from this preview — it stays a full-buffer
 * transcription in the service, so partial preview can never degrade output quality.
 */
class FloatingPreview(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val textView: TextView = TextView(context).apply {
        setBackgroundResource(R.drawable.preview_bubble)
        setTextColor(0xFFFFFFFF.toInt())
        textSize = 15f
        setPadding(dp(16), dp(10), dp(16), dp(10))
        alpha = 0f
        visibility = View.GONE
        maxWidth = dp(280)
    }

    private var isShowing = false

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 0
        y = 0
    }

    /**
     * Show/update the preview text. [anchorX]/[anchorY] are the orb's current
     * top-left position in pixels; the bubble is placed above the orb.
     */
    fun show(text: String, anchorX: Int, anchorY: Int) {
        textView.text = text
        // Position above the orb, clamped to the top of the screen.
        val metrics = context.resources.displayMetrics
        val screenW = metrics.widthPixels
        val bubbleW = dp(280)
        params.x = (anchorX - bubbleW / 2).coerceIn(0, screenW - bubbleW)
        params.y = (anchorY - dp(60)).coerceAtLeast(0)

        if (!isShowing) {
            try {
                textView.alpha = 0f
                textView.visibility = View.VISIBLE
                windowManager.addView(textView, params)
                isShowing = true
                textView.animate().alpha(1f).setDuration(180).start()
            } catch (e: Exception) {
                // Fall through — preview is non-critical.
            }
        } else {
            try {
                windowManager.updateViewLayout(textView, params)
            } catch (e: Exception) {
                // Non-critical.
            }
        }
    }

    fun hide() {
        if (!isShowing) return
        try {
            textView.animate().alpha(0f).setDuration(120).withEndAction {
                try {
                    textView.visibility = View.GONE
                    windowManager.removeView(textView)
                } catch (e: Exception) {
                    // Already removed.
                }
            }.start()
        } catch (e: Exception) {
            // Non-critical.
        }
        isShowing = false
    }

    fun destroy() {
        hide()
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
}
