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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

    // Set when the capture loop has fully exited (read() unblocked and returned).
    // stop() waits on this so it never drains/releases while the loop is mid-read.
    private var captureDone = CountDownLatch(1)

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
        captureDone = CountDownLatch(1)
        DebugLog.i(TAG, "Recording started")
        return true
    }

    /**
     * Stop capture and return the COMPLETE PCM buffer.
     *
     * 🔴 "Eats last words" fix. Two failure modes were dropping the tail:
     *
     *  1. RACE: the old code called `ar.stop()` + `ar.release()` while the capture
     *     loop was still blocked inside `read()`. On many devices `release()` while
     *     a `read()` is in flight either throws or silently discards the samples the
     *     hardware had already captured — the final word's tail vanished.
     *
     *  2. NO TRAILING SILENCE: Parakeet is a CTC model. If the audio ends exactly on
     *     the last word with no trailing silence, the decoder has no blank frames to
     *     flush the final token, so the last word (or its last syllable) is dropped.
     *
     *  Fix: signal the loop to stop, WAIT for it to fully exit (so no read() is in
     *  flight), then drain whatever the hardware still buffered, then append a short
     *  tail of digital silence so the CTC decoder can flush its final token.
     */
    fun stop(): FloatArray? {
        if (!isRecording) return null
        isRecording = false

        val ar = audioRecord
        // Unblock the capture loop's read() and wait for it to finish cleanly.
        try { ar?.stop() } catch (_: Exception) {}
        try { captureDone.await(500, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}

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
            try { ar.release() } catch (_: Exception) {}
        }
        audioRecord = null

        val data = synchronized(bufferLock) { recordedBuffer }
        recordedBuffer = FloatArray(0)

        // Append a short tail of digital silence (~300ms) so the CTC decoder has
        // blank frames to flush the final token. Without this, audio that ends
        // exactly on the last word loses its final syllable/word.
        val silence = FloatArray(SAMPLE_RATE * 3 / 10)
        val withTail = FloatArray(data.size + silence.size)
        System.arraycopy(data, 0, withTail, 0, data.size)
        System.arraycopy(silence, 0, withTail, data.size, silence.size)

        DebugLog.i(TAG, "Recording stopped, ${data.size} samples (+${silence.size} silence)")
        return withTail
    }

    fun isRecording() = isRecording

    fun recordFlow(): Flow<FloatArray> = flow {
        val buffer = ShortArray(minBufferSize)
        try {
            while (isRecording && audioRecord != null) {
                val read = audioRecord!!.read(buffer, 0, buffer.size)
                if (read > 0) {
                    val floats = FloatArray(read)
                    for (i in 0 until read) floats[i] = buffer[i] / 32768.0f
                    synchronized(bufferLock) { recordedBuffer += floats }
                    emit(floats)
                }
            }
        } finally {
            // Signal stop() that the loop has fully exited — no read() is in flight.
            captureDone.countDown()
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
