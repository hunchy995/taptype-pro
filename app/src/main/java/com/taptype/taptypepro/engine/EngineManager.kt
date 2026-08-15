package com.taptype.taptypepro.engine

import android.content.Context
import com.taptype.taptypepro.util.Settings
import java.io.File

object EngineManager {
    private var currentEngine: SpeechEngine? = null
    private val lock = Any()

    fun getActiveEngine(context: Context): SpeechEngine? = synchronized(lock) {
        val type = EngineType.valueOf(Settings.activeEngine())
        if (currentEngine?.type == type && currentEngine?.isLoaded == true) {
            return@synchronized currentEngine
        }
        loadEngineLocked(context, type)
    }

    fun loadEngine(context: Context, type: EngineType): SpeechEngine? = synchronized(lock) {
        loadEngineLocked(context, type)
    }

    private fun loadEngineLocked(context: Context, type: EngineType): SpeechEngine? {
        currentEngine?.release()
        currentEngine = when (type) {
            EngineType.PARAKEET -> ParakeetEngine()
            EngineType.WHISPER -> WhisperEngine()
        }
        val modelId = Settings.activeModel(type.name)
        if (modelId.isNotBlank()) {
            val modelDir = ModelRegistry.modelDir(context)
            val model = ModelRegistry.byId(modelId)
            val file = model?.let { ModelRegistry.modelFile(context, it) }
            if (file?.exists() == true) {
                currentEngine?.load(modelDir, modelId)
            }
        }
        return currentEngine
    }

    fun release() = synchronized(lock) {
        currentEngine?.release()
        currentEngine = null
    }
}
