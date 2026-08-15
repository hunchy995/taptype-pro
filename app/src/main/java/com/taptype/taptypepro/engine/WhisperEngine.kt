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
            val raw = nativeTranscribe(nativePtr, audioData)
            cleanTranscription(raw)
        } catch (e: Exception) {
            DebugLog.e(TAG, "Transcription failed", e)
            ""
        }
    }

    // Strip hallucinated filler tokens whisper.cpp emits on silence/noise
    // (e.g. "Message", "Thank you", "Thanks for watching", "[BLANK_AUDIO]").
    private fun cleanTranscription(raw: String): String {
        var text = raw.trim()
        if (text.isEmpty()) return ""

        // Drop standalone filler phrases at the very start (case-insensitive).
        val leadingFillers = listOf(
            "message inaudible",
            "message",
            "thank you for watching",
            "thanks for watching",
            "thank you",
            "please subscribe",
            "subscribe",
            "um",
            "uh"
        )
        var changed = true
        while (changed && text.isNotEmpty()) {
            changed = false
            for (filler in leadingFillers) {
                // Match the filler as a standalone word/phrase at the start,
                // optionally followed by punctuation, then remove it.
                val pattern = Regex(
                    "^\\s*" + Regex.escape(filler) + "([.,!?;:]*)(\\s+|$)",
                    RegexOption.IGNORE_CASE
                )
                val match = pattern.find(text) ?: continue
                text = text.removeRange(match.range).trimStart()
                changed = true
                break
            }
        }
        return text
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
