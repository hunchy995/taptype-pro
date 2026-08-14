package com.taptype.taptypepro.engine

import android.content.Context
import com.taptype.taptypepro.util.Settings
import java.io.File

object EngineManager {
    private var currentEngine: SpeechEngine? = null

    fun getActiveEngine(context: Context): SpeechEngine? {
        val type = EngineType.valueOf(Settings.activeEngine())
        if (currentEngine?.type == type && currentEngine?.isLoaded == true) {
            return currentEngine
        }
        return loadEngine(context, type)
    }

    fun loadEngine(context: Context, type: EngineType): SpeechEngine? {
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

    fun release() {
        currentEngine?.release()
        currentEngine = null
    }
}
