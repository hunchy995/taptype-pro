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
    private const val KEY_SMART_PUNCTUATION = "smart_punctuation"
    private const val KEY_HAPTICS = "haptics"
    private const val KEY_FILTER_WORDS = "filter_words"
    private const val KEY_BLOCKED_PACKAGES = "blocked_packages"

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

    fun smartPunctuation(): Boolean = SecurePrefs.getBoolean(KEY_SMART_PUNCTUATION, true)
    fun setSmartPunctuation(enabled: Boolean) = SecurePrefs.putBoolean(KEY_SMART_PUNCTUATION, enabled)

    fun hapticsEnabled(): Boolean = SecurePrefs.getBoolean(KEY_HAPTICS, true)
    fun setHapticsEnabled(enabled: Boolean) = SecurePrefs.putBoolean(KEY_HAPTICS, enabled)

    // User-defined words to strip when they appear at the start of a transcription.
    // Stored as a comma-separated string.
    fun filterWords(): List<String> =
        SecurePrefs.getString(KEY_FILTER_WORDS, "")
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    fun setFilterWords(words: List<String>) =
        SecurePrefs.putString(KEY_FILTER_WORDS, words.joinToString(",") { it.trim() })

    fun addFilterWord(word: String) {
        val current = filterWords().toMutableList()
        val trimmed = word.trim()
        if (trimmed.isNotEmpty() && trimmed !in current) {
            current.add(trimmed)
            setFilterWords(current)
        }
    }

    fun removeFilterWord(word: String) {
        setFilterWords(filterWords().filter { it != word.trim() })
    }

    // Packages for which the accessibility overlay and injection should be
    // suspended while the app is foreground (e.g. banking apps). The service
    // itself stays enabled so no manual re-enable is required afterward.
    fun blockedPackages(): Set<String> =
        SecurePrefs.getString(KEY_BLOCKED_PACKAGES, "")
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    fun setBlockedPackages(packages: Set<String>) =
        SecurePrefs.putString(KEY_BLOCKED_PACKAGES, packages.joinToString(",") { it.trim() })

    fun addBlockedPackage(packageName: String) {
        val current = blockedPackages().toMutableSet()
        val trimmed = packageName.trim()
        if (trimmed.isNotEmpty() && trimmed !in current) {
            current.add(trimmed)
            setBlockedPackages(current)
        }
    }

    fun removeBlockedPackage(packageName: String) {
        setBlockedPackages(blockedPackages() - packageName.trim())
    }
}
