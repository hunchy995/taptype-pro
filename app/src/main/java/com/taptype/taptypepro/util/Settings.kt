package com.taptype.taptypepro.util

import android.content.Context
import com.taptype.taptypepro.engine.EngineType

object Settings {
    private const val KEY_ACTIVE_ENGINE = "active_engine"
    private const val KEY_ACTIVE_MODEL = "active_model"
    private const val KEY_AUTO_STOP = "auto_stop"

    fun init(context: Context) {
        SecurePrefs.init(context)
        if (activeEngine().isBlank()) {
            setActiveEngine(EngineType.PARAKEET.name)
        }
    }

    fun activeEngine(): String = SecurePrefs.getString(KEY_ACTIVE_ENGINE, EngineType.PARAKEET.name)
    fun setActiveEngine(engine: String) = SecurePrefs.putString(KEY_ACTIVE_ENGINE, engine)

    fun activeModel(engine: String): String = SecurePrefs.getString("active_model_$engine", "")
    fun setActiveModel(engine: String, modelId: String) = SecurePrefs.putString("active_model_$engine", modelId)

    fun autoStopEnabled(): Boolean = SecurePrefs.getBoolean(KEY_AUTO_STOP, false)
    fun setAutoStop(enabled: Boolean) = SecurePrefs.putBoolean(KEY_AUTO_STOP, enabled)
}
