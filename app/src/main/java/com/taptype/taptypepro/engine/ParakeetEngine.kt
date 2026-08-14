package com.taptype.taptypepro.engine

import ai.onnxruntime.*
import com.taptype.taptypepro.util.DebugLog
import java.io.File

class ParakeetEngine : SpeechEngine {
    private val TAG = "ParakeetEngine"
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    override var isLoaded = false
        private set
    override var loadedModelId = ""
        private set

    override val type: EngineType = EngineType.PARAKEET

    override fun load(modelDir: File, modelId: String): Boolean {
        val model = ModelRegistry.byId(modelId) ?: return false
        val modelFile = File(modelDir, model.filename)
        if (!modelFile.exists()) return false

        return try {
            env?.close()
            env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(4)
                setInterOpNumThreads(2)
                addNnapi()
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            session = env?.createSession(modelFile.absolutePath, opts)
            isLoaded = true
            loadedModelId = modelId
            DebugLog.i(TAG, "Loaded $modelId")
            true
        } catch (e: Exception) {
            DebugLog.e(TAG, "Failed to load $modelId", e)
            false
        }
    }

    override fun transcribe(audioData: FloatArray): String {
        val sess = session ?: return ""
        return try {
            DebugLog.w(TAG, "Parakeet transcribe not fully implemented yet")
            "[Parakeet output placeholder]"
        } catch (e: Exception) {
            DebugLog.e(TAG, "Transcription failed", e)
            ""
        }
    }

    override fun release() {
        try {
            session?.close()
            env?.close()
        } catch (e: Exception) {
            DebugLog.e(TAG, "Release error", e)
        }
        session = null
        env = null
        isLoaded = false
        loadedModelId = ""
    }
}
