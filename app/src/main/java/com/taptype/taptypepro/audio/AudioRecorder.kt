package com.taptype.taptypepro.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.taptype.taptypepro.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

class AudioRecorder(private val context: Context) {
    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val TAG = "AudioRecorder"
    }

    private var audioRecord: AudioRecord? = null

    // 🔴 Must be volatile: read on the IO capture thread, written on the main thread.
    @Volatile
    private var isRecording = false

    private var minBufferSize = 0
    private var recordedBuffer = FloatArray(0)
    private val bufferLock = Any()

    fun hasPermission(): Boolean {
        return context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    fun start(): Boolean {
        if (isRecording) return true
        if (!hasPermission()) {
            DebugLog.e(TAG, "Missing RECORD_AUDIO permission")
            return false
        }

        minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBufferSize <= 0) {
            DebugLog.e(TAG, "Invalid min buffer size: $minBufferSize")
            return false
        }

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            minBufferSize
        ).apply {
            if (state != AudioRecord.STATE_INITIALIZED) {
                DebugLog.e(TAG, "AudioRecord failed to initialize")
                return false
            }
        }

        audioRecord?.startRecording()
        isRecording = true
        DebugLog.i(TAG, "Recording started")
        return true
    }

    /**
     * Stop capture and return the COMPLETE PCM buffer.
     *
     * 🔴 "Eats letters" fix: the old code set `isRecording = false` BEFORE stopping
     * the hardware, so the read loop exited immediately and any samples still in
     * AudioRecord's internal buffer were discarded — the tail of the final word got
     * lost ("mongolian beef" → "mangl"). We now stop the hardware first (which unblocks
     * the read loop), then drain whatever it already buffered, then release.
     */
    fun stop(): FloatArray? {
        if (!isRecording) return null
        isRecording = false

        val ar = audioRecord
        ar?.stop()

        // Drain samples the hardware captured but the read loop had not consumed yet.
        if (ar != null) {
            val buf = ShortArray(minBufferSize)
            try {
                while (true) {
                    val n = ar.read(buf, 0, buf.size)
                    if (n <= 0) break
                    val floats = FloatArray(n)
                    for (i in 0 until n) floats[i] = buf[i] / 32768.0f
                    synchronized(bufferLock) { recordedBuffer += floats }
                }
            } catch (_: Exception) {
                // Some devices reject read() after stop(); the buffered data is already
                // gone on those, so nothing more we can do.
            }
            ar.release()
        }
        audioRecord = null

        val data = synchronized(bufferLock) { recordedBuffer }
        recordedBuffer = FloatArray(0)
        DebugLog.i(TAG, "Recording stopped, ${data.size} samples")
        return data
    }

    fun isRecording() = isRecording

    fun recordFlow(): Flow<FloatArray> = flow {
        val buffer = ShortArray(minBufferSize)
        while (isRecording && audioRecord != null) {
            val read = audioRecord!!.read(buffer, 0, buffer.size)
            if (read > 0) {
                val floats = FloatArray(read)
                for (i in 0 until read) floats[i] = buffer[i] / 32768.0f
                synchronized(bufferLock) { recordedBuffer += floats }
                emit(floats)
            }
        }
    }.flowOn(Dispatchers.IO)

    fun currentRms(): Float {
        val tail = synchronized(bufferLock) {
            if (recordedBuffer.isEmpty()) FloatArray(0)
            else recordedBuffer.copyOfRange(maxOf(0, recordedBuffer.size - SAMPLE_RATE / 10), recordedBuffer.size)
        }
        if (tail.isEmpty()) return 0f
        val sumSq = tail.sumOf { (it * it).toDouble() }
        return kotlin.math.sqrt(sumSq / tail.size).toFloat()
    }

    /** Snapshot of the accumulated audio so far (for live partial transcription). */
    fun snapshot(): FloatArray = synchronized(bufferLock) { recordedBuffer.copyOf() }
}
