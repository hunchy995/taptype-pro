package com.taptype.taptypepro.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.taptype.taptypepro.R
import com.taptype.taptypepro.databinding.ActivityMainBinding
import com.taptype.taptypepro.engine.EngineManager
import com.taptype.taptypepro.engine.EngineType
import com.taptype.taptypepro.engine.ModelRegistry
import com.taptype.taptypepro.util.DebugLog
import com.taptype.taptypepro.util.HistoryStore
import com.taptype.taptypepro.util.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ModelAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Settings.init(this)
        HistoryStore.init(this)
        ensurePermissions()

        binding.enableAccessibilityBtn.setOnClickListener { openAccessibilitySettings() }
        binding.openSettingsBtn.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.viewHistoryBtn.setOnClickListener { startActivity(Intent(this, HistoryActivity::class.java)) }
        binding.viewDebugBtn.setOnClickListener { startActivity(Intent(this, DebugLogActivity::class.java)) }

        binding.switchEngineBtn.setOnClickListener { showEngineSwitcher() }

        adapter = ModelAdapter(emptyList()) { state, action ->
            when (action) {
                "download" -> downloadModel(state)
                "activate" -> activateModel(state)
                "delete" -> deleteModel(state)
            }
        }
        binding.modelList.layoutManager = LinearLayoutManager(this)
        binding.modelList.adapter = adapter

        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    private fun ensurePermissions() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 100)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
        }
    }

    private fun refreshUi() {
        val engine = EngineType.valueOf(Settings.activeEngine())
        binding.activeEngineLabel.text = "Engine: ${engine.name.lowercase().replaceFirstChar { it.uppercase() }}"

        val models = ModelRegistry.forEngine(engine).map { it.toState(this) }
        adapter.updateModels(models)

        val activeModelId = Settings.activeModel(engine.name)
        val activeModelName = ModelRegistry.byId(activeModelId)?.name ?: "None"
        binding.activeModelLabel.text = "Model: $activeModelName"
    }

    private fun downloadModel(state: ModelAdapter.ModelState) {
        val modelId = state.model.id
        lifecycleScope.launch {
            state.status = ModelAdapter.Status.DOWNLOADING
            state.progress = 0
            adapter.notifyItemChanged(adapter.indexOfModel(modelId))
            try {
                val file = ModelDownloader.download(this@MainActivity, state.model) { pct ->
                    state.progress = pct
                    withContext(Dispatchers.Main) {
                        val idx = adapter.indexOfModel(modelId)
                        if (idx >= 0) adapter.notifyItemChanged(idx)
                    }
                }
                if (file != null) {
                    state.status = ModelAdapter.Status.INSTALLED
                    adapter.notifyItemChanged(adapter.indexOfModel(modelId))
                    activateModel(state)
                } else {
                    state.status = ModelAdapter.Status.NOT_INSTALLED
                    adapter.notifyItemChanged(adapter.indexOfModel(modelId))
                    Toast.makeText(this@MainActivity, "Download failed", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                DebugLog.e("MainActivity", "Download error", e)
                state.status = ModelAdapter.Status.NOT_INSTALLED
                adapter.notifyItemChanged(adapter.indexOfModel(modelId))
                Toast.makeText(this@MainActivity, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun activateModel(state: ModelAdapter.ModelState) {
        val modelId = state.model.id
        val modelName = state.model.name
        // Mark all models as inactive and this one as "loading" for immediate UI feedback.
        adapter.models.forEach {
            it.isActive = false
            it.isLoading = (it.model.id == modelId)
        }
        adapter.notifyDataSetChanged()
        Settings.setActiveModel(state.model.engine.name, modelId)

        lifecycleScope.launch {
            // Load the model off the main thread — loading a Whisper model is heavy
            // and blocks the UI (this was the "buggy/slow" switching).
            val loaded = withContext(Dispatchers.IO) {
                EngineManager.loadEngine(this@MainActivity, state.model.engine)
            }
            adapter.models.forEach {
                it.isLoading = false
                it.isActive = (loaded?.isLoaded == true && it.model.id == modelId)
            }
            if (loaded != null && loaded.isLoaded) {
                adapter.notifyDataSetChanged()
                refreshUi()
                Toast.makeText(this@MainActivity, "$modelName active", Toast.LENGTH_SHORT).show()
            } else {
                adapter.notifyDataSetChanged()
                Toast.makeText(this@MainActivity, "Failed to load model", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun deleteModel(state: ModelAdapter.ModelState) {
        AlertDialog.Builder(this)
            .setTitle("Delete ${state.model.name}?")
            .setMessage("You can re-download it later.")
            .setPositiveButton("Delete") { _, _ ->
                ModelRegistry.modelFile(this, state.model).delete()
                state.status = ModelAdapter.Status.NOT_INSTALLED
                adapter.notifyItemChanged(adapter.models.indexOf(state))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEngineSwitcher() {
        val engines = EngineType.entries.map { it.name.lowercase().replaceFirstChar { ch -> ch.uppercase() } }.toTypedArray()
        val current = Settings.activeEngine()
        val currentIndex = EngineType.entries.indexOfFirst { it.name == current }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Choose Engine")
            .setSingleChoiceItems(engines, currentIndex) { dialog, which ->
                val chosen = EngineType.entries[which]
                Settings.setActiveEngine(chosen.name)
                EngineManager.release()
                refreshUi()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openAccessibilitySettings() {
        AlertDialog.Builder(this)
            .setTitle("Enable TapType Pro")
            .setMessage(
                "Go to Settings → Accessibility → TapType Pro and enable it. " +
                "If the toggle is greyed out, open App Info → ⋮ → Allow restricted settings first."
            )
            .setPositiveButton("Open Accessibility") { _, _ ->
                startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton("App Info") { _, _ ->
                val i = Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS)
                i.data = Uri.parse("package:$packageName")
                startActivity(i)
            }
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() && grantResults[0] != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show()
        }
    }
}
