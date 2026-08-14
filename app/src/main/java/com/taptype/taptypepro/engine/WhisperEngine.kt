package com.taptype.taptypepro.engine

import com.taptype.taptypepro.util.DebugLog
import java.io.File

class WhisperEngine : SpeechEngine {
    private val TAG = "WhisperEngine"

    init {
        System.loadLibrary("whisper")
    }

    private var nativePtr: Long = 0
    override var isLoaded = false
        private set
    override var loadedModelId = ""
        private set

    override val type: EngineType = EngineType.WHISPER

    override fun load(modelDir: File, modelId: String): Boolean {
        val model = ModelRegistry.byId(modelId) ?: return false
        val modelFile = File(modelDir, model.filename)
        if (!modelFile.exists()) return false

        if (nativePtr != 0L) release()
        return try {
            nativePtr = nativeLoadModel(modelFile.absolutePath, 4)
            isLoaded = nativePtr != 0L
            loadedModelId = modelId
            DebugLog.i(TAG, "Loaded $modelId, ptr=$nativePtr")
            isLoaded
        } catch (e: Exception) {
            DebugLog.e(TAG, "Failed to load $modelId", e)
            false
        }
    }

    override fun transcribe(audioData: FloatArray): String {
        if (nativePtr == 0L) return ""
        return try {
            nativeTranscribe(nativePtr, audioData)
        } catch (e: Exception) {
            DebugLog.e(TAG, "Transcription failed", e)
            ""
        }
    }

    override fun release() {
        if (nativePtr != 0L) {
            try {
                nativeRelease(nativePtr)
            } catch (e: Exception) {
                DebugLog.e(TAG, "Release error", e)
            }
            nativePtr = 0L
        }
        isLoaded = false
        loadedModelId = ""
    }

    private external fun nativeLoadModel(path: String, nThreads: Int): Long
    private external fun nativeTranscribe(ptr: Long, samples: FloatArray): String
    private external fun nativeRelease(ptr: Long)
}
