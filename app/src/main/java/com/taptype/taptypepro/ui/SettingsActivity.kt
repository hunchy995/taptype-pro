package com.taptype.taptypepro.ui

import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.taptype.taptypepro.R
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

            findPreference<SwitchPreferenceCompat>("auto_stop")?.setOnPreferenceChangeListener { _, newValue ->
                Settings.setAutoStop(newValue as Boolean)
                true
            }

            findPreference<SwitchPreferenceCompat>("auto_capitalize")?.setOnPreferenceChangeListener { _, newValue ->
                Settings.setAutoCapitalize(newValue as Boolean)
                true
            }

            findPreference<SwitchPreferenceCompat>("auto_punctuation")?.setOnPreferenceChangeListener { _, newValue ->
                Settings.setAutoPunctuation(newValue as Boolean)
                true
            }

            findPreference<SwitchPreferenceCompat>("smart_punctuation")?.setOnPreferenceChangeListener { _, newValue ->
                Settings.setSmartPunctuation(newValue as Boolean)
                true
            }

            findPreference<SwitchPreferenceCompat>("auto_numbers")?.setOnPreferenceChangeListener { _, newValue ->
                Settings.setAutoNumbers(newValue as Boolean)
                true
            }

            findPreference<SwitchPreferenceCompat>("haptics")?.setOnPreferenceChangeListener { _, newValue ->
                Settings.setHapticsEnabled(newValue as Boolean)
                true
            }

            findPreference<Preference>("filter_words")?.setOnPreferenceClickListener {
                showFilterWordsDialog()
                true
            }

            findPreference<Preference>("blocked_apps")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), BlockedAppsActivity::class.java))
                true
            }
        }

        private fun showFilterWordsDialog() {
            val words = Settings.filterWords()
            val items = words.toTypedArray()

            val builder = AlertDialog.Builder(requireContext())
                .setTitle(R.string.filter_words_manage_title)

            if (items.isEmpty()) {
                builder.setMessage(R.string.filter_words_empty)
            } else {
                builder.setItems(items) { _, which ->
                    confirmRemoveWord(items[which])
                }
            }

            builder
                .setPositiveButton(R.string.filter_words_add) { _, _ ->
                    showAddWordDialog()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        private fun showAddWordDialog() {
            val input = EditText(requireContext())
            input.hint = getString(R.string.filter_words_add_hint)
            input.setSingleLine(true)

            AlertDialog.Builder(requireContext())
                .setTitle(R.string.filter_words_add)
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    Settings.addFilterWord(input.text.toString())
                    showFilterWordsDialog()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        private fun confirmRemoveWord(word: String) {
            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.delete_model_title, word))
                .setMessage(getString(R.string.filter_words_summary))
                .setPositiveButton(R.string.delete) { _, _ ->
                    Settings.removeFilterWord(word)
                    showFilterWordsDialog()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }
}
