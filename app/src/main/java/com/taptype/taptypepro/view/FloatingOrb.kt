package com.taptype.taptypepro.view

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.ProgressBar
import com.taptype.taptypepro.R
import com.taptype.taptypepro.service.RecordingForegroundService
import com.taptype.taptypepro.util.DebugLog
import com.taptype.taptypepro.util.Settings
import kotlin.math.PI
import kotlin.math.cos

/**
 * Floating dictation button with Apple-inspired motion design.
 *
 * SAFETY RULES (do not break — these caused repeated button failures):
 *  - NEVER animate WindowManager.LayoutParams. Only animate view properties
 *    (scaleX/scaleY/alpha/rotation) on the inflated child views.
 *  - The OnTouchListener drag coordinate math (ACTION_DOWN/ACTION_MOVE/ACTION_UP
 *    moving params.x/params.y via updateViewLayout) must stay byte-for-byte
 *    identical. Press/release scale feedback is layered on top without touching
 *    those coordinates.
 */
@SuppressLint("InflateParams")
class FloatingOrb(private val context: Context) {
    private val TAG = "FloatingOrb"

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val view: View = LayoutInflater.from(context).inflate(R.layout.floating_orb, null)
    private val orbImage = view.findViewById<ImageView>(R.id.orbImage)
    private val spinner = view.findViewById<ProgressBar>(R.id.orbSpinner)

    private val orbSizePx = (Settings.orbSizeDp() * context.resources.displayMetrics.density).toInt()

    private var params = WindowManager.LayoutParams(
        orbSizePx,
        orbSizePx,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 0
        y = 600
    }

    private var isShowing = false
    private var isRecording = false
    private var isProcessing = false
    private var isDragging = false
    private var initialX = 0
    private var initialY = 0
    private var touchX = 0f
    private var touchY = 0f
    private val handler = Handler(Looper.getMainLooper())

    // Apple-style spring for state transitions: gentle overshoot then settle.
    private val spring = OvershootInterpolator(1.8f)
    private val quickOut = DecelerateInterpolator(2.5f)

    // --- Recording: "heartbeat" pulse on the button itself.
    //     Scales between 0.90 and 1.00 so it NEVER exceeds the window bounds —
    //     this is what stops the circle from being cropped at large sizes. ---
    private val orbPulse = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 900
        repeatMode = ValueAnimator.RESTART
        repeatCount = ValueAnimator.INFINITE
        interpolator = android.view.animation.LinearInterpolator()
        addUpdateListener { animator ->
            val p = animator.animatedValue as Float
            val t = p * 2.0 * PI
            val scale = 0.95f + 0.05f * cos(t).toFloat() // 1.00 -> 0.90 -> 1.00
            orbImage.scaleX = scale
            orbImage.scaleY = scale
        }
    }

    init {
        // Drag logic — KEEP BYTE-FOR-BYTE IDENTICAL. Only the scale-feedback calls
        // on ACTION_DOWN / ACTION_UP are added around it.
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isDragging = false
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    if (!isRecording && !isProcessing) pressIn()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (kotlin.math.abs(dx) > 20 || kotlin.math.abs(dy) > 20) {
                        if (!isDragging) pressOut() // started dragging: un-press
                        isDragging = true
                    }
                    params.x = (initialX + dx).toInt()
                    params.y = (initialY + dy).toInt()
                    windowManager.updateViewLayout(view, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        onOrbTapped()
                    } else {
                        pressOut()
                    }
                    true
                }
                else -> false
            }
        }
    }

    // --- Press feedback: scale down on touch, spring back on release ---
    private fun pressIn() {
        orbImage.animate().cancel()
        orbImage.animate()
            .scaleX(0.88f).scaleY(0.88f)
            .setDuration(110)
            .setInterpolator(quickOut)
            .start()
    }

    private fun pressOut() {
        orbImage.animate().cancel()
        orbImage.animate()
            .scaleX(1f).scaleY(1f)
            .setDuration(320)
            .setInterpolator(spring)
            .start()
    }

    fun show() {
        if (isShowing) return
        try {
            windowManager.addView(view, params)
            isShowing = true
            // Gentle Apple-style settle on first appearance.
            orbImage.scaleX = 0f
            orbImage.scaleY = 0f
            orbImage.animate()
                .scaleX(1f).scaleY(1f)
                .setDuration(380)
                .setInterpolator(spring)
                .start()
            DebugLog.d(TAG, "Orb shown")
        } catch (e: Exception) {
            DebugLog.e(TAG, "Show orb failed", e)
        }
    }

    fun hide() {
        if (!isShowing) return
        try {
            if (isRecording) stopRecording()
            windowManager.removeView(view)
            isShowing = false
            DebugLog.d(TAG, "Orb hidden")
        } catch (e: Exception) {
            DebugLog.e(TAG, "Hide orb failed", e)
        }
    }

    fun destroy() {
        hide()
    }

    private fun onOrbTapped() {
        if (isProcessing) return  // ignore taps while transcribing
        if (isRecording) stopRecording() else startRecording()
    }

    private fun startRecording() {
        isRecording = true
        pressOut()
        // Crossfade the icon to the stop glyph, then start the pulse.
        crossfadeIcon(R.drawable.ic_stop, R.drawable.orb_background_recording) {
            orbPulse.start()
        }
        hapticStart()
        RecordingForegroundService.start(context)
        DebugLog.i(TAG, "Orb: start recording requested")
    }

    private fun stopRecording() {
        isRecording = false
        orbPulse.cancel()
        // Snap scale back to 1 with a small spring, then show the processing spinner.
        orbImage.animate().cancel()
        orbImage.animate()
            .scaleX(1f).scaleY(1f)
            .setDuration(200)
            .setInterpolator(quickOut)
            .start()
        showProcessing()
        hapticStop()
        RecordingForegroundService.stop(context)
        DebugLog.i(TAG, "Orb: stop recording requested")
    }

    /**
     * Fades the orb icon out, swaps the drawable, then springs it back in.
     * Gives a smooth Apple-style morph between mic/stop instead of a hard swap.
     */
    private fun crossfadeIcon(newIcon: Int, newBackground: Int, onDone: () -> Unit) {
        orbImage.animate().cancel()
        orbImage.animate()
            .alpha(0f)
            .scaleX(0.85f).scaleY(0.85f)
            .setDuration(110)
            .setInterpolator(quickOut)
            .withEndAction {
                orbImage.setImageResource(newIcon)
                orbImage.setBackgroundResource(newBackground)
                orbImage.animate()
                    .alpha(1f)
                    .scaleX(1f).scaleY(1f)
                    .setDuration(360)
                    .setInterpolator(spring)
                    .withEndAction(onDone)
                    .start()
            }
            .start()
    }

    private fun showProcessing() {
        isProcessing = true
        // Fade the icon out and crossfade the spinner in.
        orbImage.animate().cancel()
        orbImage.animate()
            .alpha(0f)
            .setDuration(130)
            .setInterpolator(quickOut)
            .withEndAction {
                orbImage.setImageDrawable(null)
                orbImage.setBackgroundResource(R.drawable.orb_background)
                orbImage.alpha = 1f
                spinner.alpha = 0f
                spinner.visibility = View.VISIBLE
                spinner.animate()
                    .alpha(1f)
                    .scaleX(1f).scaleY(1f)
                    .setDuration(260)
                    .setInterpolator(spring)
                    .start()
            }
            .start()
    }

    fun onTranscriptionComplete() {
        handler.post {
            isProcessing = false
            // Crossfade the spinner out and the mic icon back in.
            spinner.animate().cancel()
            spinner.animate()
                .alpha(0f)
                .scaleX(0.8f).scaleY(0.8f)
                .setDuration(140)
                .setInterpolator(quickOut)
                .withEndAction {
                    spinner.visibility = View.GONE
                    spinner.alpha = 1f
                    spinner.scaleX = 1f
                    spinner.scaleY = 1f
                    orbImage.setImageResource(R.drawable.ic_mic)
                    orbImage.setBackgroundResource(R.drawable.orb_background)
                    orbImage.alpha = 0f
                    orbImage.scaleX = 0.9f
                    orbImage.scaleY = 0.9f
                    orbImage.animate()
                        .alpha(1f)
                        .scaleX(1f).scaleY(1f)
                        .setDuration(340)
                        .setInterpolator(spring)
                        .start()
                }
                .start()
            DebugLog.i(TAG, "Orb: transcription complete, back to idle")
        }
    }

    // Live-resize the running overlay window to the new dp size.
    fun onOrbSizeChanged(dp: Int) {
        handler.post {
            try {
                val px = (dp * context.resources.displayMetrics.density).toInt()
                params.width = px
                params.height = px
                // Resize the inner content too so icon/ring scale with the window.
                view.layoutParams = params
                if (isShowing) {
                    windowManager.updateViewLayout(view, params)
                }
                DebugLog.i(TAG, "Orb resized to ${dp}dp ($px px)")
            } catch (e: Exception) {
                DebugLog.e(TAG, "Resize orb failed", e)
            }
        }
    }

    private fun hapticStart() {
        if (!Settings.hapticsEnabled()) return
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(40)
        }
    }

    private fun hapticStop() {
        if (!Settings.hapticsEnabled()) return
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(60)
        }
    }
}
