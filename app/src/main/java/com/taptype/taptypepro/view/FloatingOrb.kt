package com.taptype.taptypepro.view

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import com.taptype.taptypepro.R
import com.taptype.taptypepro.service.RecordingForegroundService
import com.taptype.taptypepro.util.DebugLog

@SuppressLint("InflateParams")
class FloatingOrb(private val context: Context) {
    private val TAG = "FloatingOrb"

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val view: View = LayoutInflater.from(context).inflate(R.layout.floating_orb, null)
    private val orbImage = view.findViewById<ImageView>(R.id.orbImage)
    private val ringView = view.findViewById<View>(R.id.recordingRing)

    private var params = WindowManager.LayoutParams(
        context.resources.getDimensionPixelSize(R.dimen.orb_size),
        context.resources.getDimensionPixelSize(R.dimen.orb_size),
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
    private var isDragging = false
    private var initialX = 0
    private var initialY = 0
    private var touchX = 0f
    private var touchY = 0f
    private val handler = Handler(Looper.getMainLooper())

    private val ringAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1200
        repeatMode = ValueAnimator.RESTART
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { animator ->
            val scale = 1f + 0.15f * (animator.animatedValue as Float)
            ringView.scaleX = scale
            ringView.scaleY = scale
            ringView.alpha = 1f - (animator.animatedValue as Float)
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
        if (isRecording) stopRecording() else startRecording()
    }

    private fun startRecording() {
        isRecording = true
        ringView.visibility = View.VISIBLE
        ringAnimator.start()
        RecordingForegroundService.start(context)
        DebugLog.i(TAG, "Orb: start recording requested")
    }

    private fun stopRecording() {
        isRecording = false
        ringAnimator.cancel()
        ringView.visibility = View.GONE
        RecordingForegroundService.stop(context)
        DebugLog.i(TAG, "Orb: stop recording requested")
    }
}
