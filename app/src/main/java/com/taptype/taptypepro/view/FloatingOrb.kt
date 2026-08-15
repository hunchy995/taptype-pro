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
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.ProgressBar
import com.taptype.taptypepro.R
import com.taptype.taptypepro.service.RecordingForegroundService
import com.taptype.taptypepro.util.DebugLog
import com.taptype.taptypepro.util.Settings

@SuppressLint("InflateParams")
class FloatingOrb(private val context: Context) {
    private val TAG = "FloatingOrb"

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val view: View = LayoutInflater.from(context).inflate(R.layout.floating_orb, null)
    private val orbImage = view.findViewById<ImageView>(R.id.orbImage)
    private val ringView = view.findViewById<View>(R.id.recordingRing)
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

    // Pulsing red ring while recording
    private val ringAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1200
        repeatMode = ValueAnimator.RESTART
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { animator ->
            val progress = animator.animatedValue as Float
            val scale = 1f + 0.30f * progress
            ringView.scaleX = scale
            ringView.scaleY = scale
            ringView.alpha = 1f - progress
        }
    }

    // Breathing scale on the orb itself while recording
    private val orbBreathing = ValueAnimator.ofFloat(1f, 1.12f, 1f).apply {
        duration = 1400
        repeatMode = ValueAnimator.RESTART
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { animator ->
            val scale = animator.animatedValue as Float
            orbImage.scaleX = scale
            orbImage.scaleY = scale
        }
    }

    init {
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isDragging = false
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (kotlin.math.abs(dx) > 20 || kotlin.math.abs(dy) > 20) isDragging = true
                    params.x = (initialX + dx).toInt()
                    params.y = (initialY + dy).toInt()
                    windowManager.updateViewLayout(view, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) onOrbTapped()
                    true
                }
                else -> false
            }
        }
    }

    fun show() {
        if (isShowing) return
        try {
            windowManager.addView(view, params)
            isShowing = true
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
        // Switch icon and background to recording state
        orbImage.setImageResource(R.drawable.ic_stop)
        orbImage.setBackgroundResource(R.drawable.orb_background_recording)
        // Start pulse ring + breathing
        ringView.visibility = View.VISIBLE
        ringView.scaleX = 1f
        ringView.scaleY = 1f
        ringAnimator.start()
        orbBreathing.start()
        // Tactile feedback
        hapticStart()
        RecordingForegroundService.start(context)
        DebugLog.i(TAG, "Orb: start recording requested")
    }

    private fun stopRecording() {
        isRecording = false
        // Cancel animations and reset scale
        ringAnimator.cancel()
        orbBreathing.cancel()
        ringView.visibility = View.GONE
        orbImage.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        // Show loading spinner while the service transcribes
        showProcessing()
        // Tactile feedback
        hapticStop()
        RecordingForegroundService.stop(context)
        DebugLog.i(TAG, "Orb: stop recording requested")
    }

    private fun showProcessing() {
        isProcessing = true
        orbImage.setImageDrawable(null)           // hide mic/stop icon
        orbImage.setBackgroundResource(R.drawable.orb_background)
        spinner.visibility = View.VISIBLE
    }

    fun onTranscriptionComplete() {
        handler.post {
            isProcessing = false
            spinner.visibility = View.GONE
            orbImage.setImageResource(R.drawable.ic_mic)
            orbImage.setBackgroundResource(R.drawable.orb_background)
            DebugLog.i(TAG, "Orb: transcription complete, back to idle")
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
