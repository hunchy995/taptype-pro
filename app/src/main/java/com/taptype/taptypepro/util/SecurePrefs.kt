package com.taptype.taptypepro.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object SecurePrefs {
    private const val FILE_NAME = "taptype_pro_secure"
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        prefs = EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun ensureInitialized() {
        if (!::prefs.isInitialized) throw IllegalStateException("SecurePrefs not initialized")
    }

    fun putString(key: String, value: String) {
        ensureInitialized()
        prefs.edit().putString(key, value).apply()
    }

    fun getString(key: String, default: String = ""): String {
        ensureInitialized()
        return prefs.getString(key, default) ?: default
    }

    fun putBoolean(key: String, value: Boolean) {
        ensureInitialized()
        prefs.edit().putBoolean(key, value).apply()
    }

    fun getBoolean(key: String, default: Boolean = false): Boolean {
        ensureInitialized()
        return prefs.getBoolean(key, default)
    }

    fun putInt(key: String, value: Int) {
        ensureInitialized()
        prefs.edit().putInt(key, value).apply()
    }

    fun getInt(key: String, default: Int = 0): Int {
        ensureInitialized()
        return prefs.getInt(key, default)
    }

    fun remove(key: String) {
        ensureInitialized()
        prefs.edit().remove(key).apply()
    }
}
