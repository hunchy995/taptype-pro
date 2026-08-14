package com.taptype.taptypepro.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.taptype.taptypepro.R
import com.taptype.taptypepro.engine.EngineType
import com.taptype.taptypepro.util.DebugLog
import com.taptype.taptypepro.util.Settings

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportFragmentManager
            .beginTransaction()
            .replace(android.R.id.content, SettingsFragment())
            .commit()
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            val autoStop = findPreference<SwitchPreferenceCompat>("auto_stop")
            autoStop?.setOnPreferenceChangeListener { _, newValue ->
                Settings.setAutoStop(newValue as Boolean)
                true
            }
        }
    }
}
