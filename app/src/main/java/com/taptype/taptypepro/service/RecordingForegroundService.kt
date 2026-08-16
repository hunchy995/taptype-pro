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
import kotlinx.coroutines.withContext

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
    // Single-thread dispatcher serializes ALL transcription calls (partial + final).
    // ONNX Runtime and whisper sessions are NOT safe for concurrent run() calls.
    private val transcribeDispatcher = Dispatchers.IO.limitedParallelism(1)
    private var silenceJob: Job? = null
    private var partialJob: Job? = null
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
                // Push live audio level to the orb meter (throttled to ~20Hz).
                val now = System.currentTimeMillis()
                if (now - lastLevelPushMs >= 50) {
                    lastLevelPushMs = now
                    TapTypeAccessibilityService.instance?.onAudioLevel(recorder.currentRms())
                }
            }
        }

        // Live partial transcription — injected straight into the focused field
        // in real time. Runs on the single-thread dispatcher so it never overlaps
        // the final transcription. The final injected text is still a full-buffer
        // transcription (which replaces the last partial), so partials never
        // degrade the final output quality.
        partialJob = serviceScope.launch {
            var lastPartialLen = 0
            while (isRunning) {
                delay(900)
                if (!isRunning) break
                val snap = recorder.snapshot()
                // Only transcribe if there's meaningful new audio (~1s + 0.4s new).
                if (snap.size < 16000 || snap.size < lastPartialLen + 6400) continue
                val engine = withContext(transcribeDispatcher) {
                    EngineManager.getActiveEngine(this@RecordingForegroundService)
                }
                if (engine == null || !engine.isLoaded) continue
                val partial = withContext(transcribeDispatcher) {
                    runCatching { applyTextSettings(engine.transcribe(snap)) }.getOrDefault("")
                }
                if (partial.isNotBlank()) {
                    lastPartialLen = snap.size
                    withContext(Dispatchers.Main) {
                        TapTypeAccessibilityService.instance?.onPartialTranscription(partial)
                    }
                }
            }
        }
    }

    private var lastLevelPushMs = 0L

    private fun stopRecording() {
        if (!isRunning) return
        isRunning = false
        partialJob?.cancel()
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
            // Run the final transcription on the single-thread dispatcher so it
            // never overlaps a partial transcription still in flight.
            val engine = EngineManager.getActiveEngine(this@RecordingForegroundService)
            if (engine == null || !engine.isLoaded) {
                DebugLog.e(TAG, "No active engine loaded")
                notifyOrbDone()
                return
            }
            val text = withContext(transcribeDispatcher) {
                applyTextSettings(engine.transcribe(samples))
            }
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

    // Smarter post-processing for CTC engines (Parakeet) that output raw
    // lowercase with no punctuation. Applied after transcription, regardless of engine.
    private fun applyTextSettings(raw: String): String {
        var text = raw.trim()
        if (text.isEmpty()) return ""

        // 1. Strip leading hallucinated fillers (Message, thank you, etc.).
        text = stripLeadingFillers(text)

        // 2. Fix standalone "i" → "I" (very common CTC lowercase artifact).
        text = Regex("\\bi\\b").replace(text) { "I" }

        // 3. Normalize whitespace and remove duplicate words that whisper sometimes emits.
        text = Regex("\\s+").replace(text, " ")
        text = Regex("\\b(\\w+)\\s+\\1\\b", RegexOption.IGNORE_CASE).replace(text) { it.groupValues[1] }

        // 4. Smart punctuation: split run-on speech into sentences/clauses.
        text = insertNaturalPunctuation(text)

        if (Settings.autoCapitalize()) {
            text = capitalizeSentences(text)
        }

        if (Settings.autoPunctuation()) {
            text = ensureTerminalPunctuation(text)
        } else {
            // Even if auto punctuation is disabled, trim trailing junk.
            text = text.trimEnd { it in ".,!?;:" }
        }
        return text.trim()
    }

    /** Remove common leading hallucinated tokens (case-insensitive, anchored). */
    private fun stripLeadingFillers(text: String): String {
        var s = text
        val fillers = listOf(
            "message inaudible", "message",
            "thank you for watching", "thanks for watching", "thank you",
            "please subscribe", "subscribe",
            "um", "uh", "er", "ah", "hmm", "like", "you know", "i mean"
        )
        var changed = true
        while (changed && s.isNotEmpty()) {
            changed = false
            for (word in fillers) {
                if (word.isBlank()) continue
                val regex = Regex("^" + Regex.escape(word) + "\\b[\\s.,!?;:]*", RegexOption.IGNORE_CASE)
                val after = regex.replaceFirst(s, "")
                if (after != s) { s = after.trimStart(); changed = true; break }
            }
        }
        return s.trimStart()
    }

    /**
     * Insert commas and periods into long run-on sentences using simple heuristics.
     * This is intentionally conservative — we only add punctuation where speech has a
     * strong natural pause marker (long conjunctions/adverbs) or the sentence is very long.
     */
    private fun insertNaturalPunctuation(text: String): String {
        // If the engine already provided punctuation, trust it but still fix spacing.
        if (Regex("[.!?]").containsMatchIn(text)) {
            return fixPunctuationSpacing(text)
        }

        val words = text.split(" ").toMutableList()
        if (words.size < 4) return text

        val result = StringBuilder()
        var wordsSinceBreak = 0
        var i = 0
        while (i < words.size) {
            val word = words[i]
            val lower = word.lowercase()
            result.append(word)

            // Strong pause words → period after if enough words have passed.
            val breakWords = setOf(
                "then", "next", "after", "finally", "so", "therefore", "however",
                "anyway", "also", "plus", "besides", "meanwhile", "otherwise"
            )
            if (wordsSinceBreak >= 6 && lower in breakWords && i < words.size - 2) {
                result.append(". ")
                wordsSinceBreak = 0
                i++
                continue
            }

            // Conjunctions/clause markers → comma if mid-sentence.
            val commaWords = setOf(
                "and", "but", "or", "so", "because", "when", "while", "although",
                "though", "if", "since", "unless", "before", "after", "which", "who"
            )
            if (wordsSinceBreak in 4..11 && lower in commaWords && i < words.size - 1) {
                result.append(", ")
                wordsSinceBreak = 0
                i++
                continue
            }

            // Long sentence with no marker? Force a period.
            if (wordsSinceBreak >= 14 && i < words.size - 2) {
                result.append(". ")
                wordsSinceBreak = 0
                i++
                continue
            }

            if (i < words.size - 1) result.append(" ")
            wordsSinceBreak++
            i++
        }

        var cleaned = result.toString()
        // Clean up duplicate punctuation/spaces left by the algorithm.
        cleaned = Regex("\\s+").replace(cleaned, " ")
        cleaned = Regex("\\s([.,!?;:])").replace(cleaned, "$1")
        cleaned = Regex("([.,!?;:]){2,}").replace(cleaned, "$1")
        return cleaned.trim()
    }

    /** Fix spacing around existing punctuation so "word . word" becomes "word. word". */
    private fun fixPunctuationSpacing(text: String): String {
        var cleaned = text
        cleaned = Regex("\\s+").replace(cleaned, " ")
        cleaned = Regex("\\s([.,!?;:])").replace(cleaned, "$1")
        cleaned = Regex("([.,!?;:])\\s*").replace(cleaned) { m -> m.groupValues[1] + " " }
        cleaned = Regex("([.,!?;:]){2,}").replace(cleaned, "$1")
        return cleaned.trim()
    }

    /** Capitalize the first letter of every sentence. */
    private fun capitalizeSentences(text: String): String {
        return Regex("(^|[.!?]\\s+)([a-z])").replace(text) { m ->
            m.groupValues[1] + m.groupValues[2].uppercase()
        }
    }

    /** Make sure the final sentence ends with . or ? depending on question detection. */
    private fun ensureTerminalPunctuation(text: String): String {
        val trimmed = text.trimEnd { it in ".,!?;:" }
        if (trimmed.isEmpty()) return ""
        val lastChar = trimmed.last()
        if (!lastChar.isLetterOrDigit()) return trimmed
        return trimmed + if (isQuestion(trimmed)) "?" else "."
    }

    // Heuristic question detection: first word is a question starter, or the
    // sentence ends with a question tag ("right", "okay", "correct").
    private fun isQuestion(text: String): Boolean {
        val words = text.trim().lowercase().split(Regex("\\s+"))
        if (words.isEmpty()) return false
        val first = words.first().trimEnd('?', '.', '!', ',')
        val questionStarters = setOf(
            "who", "what", "where", "when", "why", "how",
            "is", "are", "was", "were", "do", "does", "did",
            "can", "could", "would", "will", "shall", "should",
            "may", "might", "have", "has", "had", "am"
        )
        val questionTags = setOf("right", "okay", "correct", "yes", "no")
        return first in questionStarters || words.last().trimEnd('?', '.', '!', ',') in questionTags
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
