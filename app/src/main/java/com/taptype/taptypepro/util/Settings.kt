package com.taptype.taptypepro.util

import android.content.Context
import com.taptype.taptypepro.engine.EngineType

object Settings {
    private const val KEY_ACTIVE_ENGINE = "active_engine"
    private const val KEY_ACTIVE_MODEL = "active_model"
    private const val KEY_AUTO_STOP = "auto_stop"
    private const val KEY_ORB_SIZE = "orb_size"
    private const val KEY_AUTO_CAPITALIZE = "auto_capitalize"
    private const val KEY_AUTO_PUNCTUATION = "auto_punctuation"
    private const val KEY_HAPTICS = "haptics"

    fun init(context: Context) {
        SecurePrefs.init(context)
        if (activeEngine().isBlank()) {
            setActiveEngine(EngineType.WHISPER.name)
        }
    }

    fun activeEngine(): String = SecurePrefs.getString(KEY_ACTIVE_ENGINE, EngineType.WHISPER.name)
    fun setActiveEngine(engine: String) = SecurePrefs.putString(KEY_ACTIVE_ENGINE, engine)

    fun activeModel(engine: String): String = SecurePrefs.getString("active_model_$engine", "")
    fun setActiveModel(engine: String, modelId: String) = SecurePrefs.putString("active_model_$engine", modelId)

    fun autoStopEnabled(): Boolean = SecurePrefs.getBoolean(KEY_AUTO_STOP, false)
    fun setAutoStop(enabled: Boolean) = SecurePrefs.putBoolean(KEY_AUTO_STOP, enabled)

    // Floating button size in dp (default 56).
    fun orbSizeDp(): Int = SecurePrefs.getInt(KEY_ORB_SIZE, 56)
    fun setOrbSizeDp(dp: Int) = SecurePrefs.putInt(KEY_ORB_SIZE, dp)

    fun autoCapitalize(): Boolean = SecurePrefs.getBoolean(KEY_AUTO_CAPITALIZE, true)
    fun setAutoCapitalize(enabled: Boolean) = SecurePrefs.putBoolean(KEY_AUTO_CAPITALIZE, enabled)

    fun autoPunctuation(): Boolean = SecurePrefs.getBoolean(KEY_AUTO_PUNCTUATION, true)
    fun setAutoPunctuation(enabled: Boolean) = SecurePrefs.putBoolean(KEY_AUTO_PUNCTUATION, enabled)

    fun hapticsEnabled(): Boolean = SecurePrefs.getBoolean(KEY_HAPTICS, true)
    fun setHapticsEnabled(enabled: Boolean) = SecurePrefs.putBoolean(KEY_HAPTICS, enabled)
}
