package com.taptype.taptypepro.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.taptype.taptypepro.R
import com.taptype.taptypepro.audio.AudioRecorder
import com.taptype.taptypepro.engine.EngineManager
import com.taptype.taptypepro.util.DebugLog
import com.taptype.taptypepro.util.HistoryEntry
import com.taptype.taptypepro.util.HistoryStore
import com.taptype.taptypepro.util.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class RecordingForegroundService : Service() {
    companion object {
        private const val TAG = "RecordingService"
        private const val CHANNEL_ID = "taptype_pro_recording"
        private const val NOTIFICATION_ID = 1

        var isRunning = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, RecordingForegroundService::class.java)
            intent.action = "START"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, RecordingForegroundService::class.java)
            intent.action = "STOP"
            context.startService(intent)
        }
    }

    private lateinit var recorder: AudioRecorder
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var silenceJob: Job? = null
    private var startTime = 0L

    override fun onCreate() {
        super.onCreate()
        recorder = AudioRecorder(this)
        createNotificationChannel()
        HistoryStore.init(this)
        Settings.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START" -> startRecording()
            "STOP" -> stopRecording()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startRecording() {
        if (isRunning) return
        if (!recorder.start()) {
            DebugLog.e(TAG, "Failed to start recorder")
            stopSelf()
            return
        }
        isRunning = true
        startTime = System.currentTimeMillis()
        startForeground(NOTIFICATION_ID, buildNotification("Recording…"))
        DebugLog.i(TAG, "Foreground recording started")

        serviceScope.launch {
            recorder.recordFlow().collect { chunk ->
                if (Settings.autoStopEnabled()) checkSilence(chunk)
            }
        }
    }

    private fun stopRecording() {
        if (!isRunning) return
        isRunning = false
        val samples = recorder.stop()
        silenceJob?.cancel()
        val duration = System.currentTimeMillis() - startTime
        DebugLog.i(TAG, "Stopped recording, duration=${duration}ms, samples=${samples?.size ?: 0}")

        if (samples != null && samples.isNotEmpty()) {
            serviceScope.launch(Dispatchers.IO) {
                transcribeAndInject(samples, duration)
                stopSelf()
            }
        } else {
            stopSelf()
        }
    }

    private suspend fun transcribeAndInject(samples: FloatArray, duration: Long) {
        try {
            val engine = EngineManager.getActiveEngine(this@RecordingForegroundService)
            if (engine == null || !engine.isLoaded) {
                DebugLog.e(TAG, "No active engine loaded")
                notifyOrbDone()
                return
            }
            val text = engine.transcribe(samples)
            if (text.isBlank()) {
                DebugLog.w(TAG, "Transcription returned empty text")
                notifyOrbDone()
                return
            }
            HistoryStore.add(HistoryEntry(text = text, engine = engine.type.name, model = engine.loadedModelId, durationMs = duration))

            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                TapTypeAccessibilityService.instance?.injectText(text)
                TapTypeAccessibilityService.instance?.onTranscriptionComplete()
            }
        } catch (e: Exception) {
            DebugLog.e(TAG, "Transcribe/inject failed", e)
            notifyOrbDone()
        }
    }

    private fun notifyOrbDone() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            TapTypeAccessibilityService.instance?.onTranscriptionComplete()
        }
    }

    private fun checkSilence(chunk: FloatArray) {
        val rms = kotlin.math.sqrt(chunk.map { it * it }.average().toFloat())
        if (rms < 0.015f) {
            if (silenceJob == null || silenceJob?.isActive != true) {
                silenceJob = serviceScope.launch {
                    delay(1500)
                    if (recorder.isRecording()) {
                        DebugLog.i(TAG, "Auto-stop triggered by silence")
                        stopRecording()
                    }
                }
            }
        } else {
            silenceJob?.cancel()
            silenceJob = null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "TapType Pro Recording",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(content: String): Notification {
        val stopIntent = Intent(this, RecordingForegroundService::class.java).apply { action = "STOP" }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TapType Pro")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_mic)
            .setOngoing(true)
            .addAction(0, "Stop", stopPending)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        if (recorder.isRecording()) recorder.stop()
    }
}
