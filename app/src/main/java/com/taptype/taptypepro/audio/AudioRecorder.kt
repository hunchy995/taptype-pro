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
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioRecorder(private val context: Context) {
    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val TAG = "AudioRecorder"
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var minBufferSize = 0

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

    fun stop(): FloatArray? {
        if (!isRecording) return null
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        val data = recordedBuffer
        recordedBuffer = FloatArray(0)
        DebugLog.i(TAG, "Recording stopped, ${data.size} samples")
        return data
    }

    fun isRecording() = isRecording

    private var recordedBuffer = FloatArray(0)

    fun recordFlow(): Flow<FloatArray> = flow {
        val buffer = ShortArray(minBufferSize)
        while (isRecording && audioRecord != null) {
            val read = audioRecord!!.read(buffer, 0, buffer.size)
            if (read > 0) {
                val floats = ShortArray(read).mapIndexed { i, _ -> buffer[i] / 32768.0f }.toFloatArray()
                recordedBuffer += floats
                emit(floats)
            }
        }
    }.flowOn(Dispatchers.IO)

    fun currentRms(): Float {
        if (recordedBuffer.isEmpty()) return 0f
        val tail = recordedBuffer.takeLast(SAMPLE_RATE / 10)
        val sumSq = tail.sumOf { (it * it).toDouble() }
        return kotlin.math.sqrt(sumSq / tail.size).toFloat()
    }

    /** Snapshot of the accumulated audio so far (for live partial transcription). */
    fun snapshot(): FloatArray = recordedBuffer.copyOf()
}
