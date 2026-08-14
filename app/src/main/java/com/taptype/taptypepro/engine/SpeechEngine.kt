package com.taptype.taptypepro.engine

import java.io.File

enum class EngineType {
    PARAKEET,
    WHISPER
}

interface SpeechEngine {
    val type: EngineType
    val isLoaded: Boolean
    val loadedModelId: String
    fun load(modelDir: File, modelId: String): Boolean
    fun transcribe(audioData: FloatArray): String
    fun release()
}

interface VAD {
    fun load(modelDir: File): Boolean
    fun reset()
    fun process(samples: FloatArray): Boolean // true = speech
    fun release()
}
