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
import kotlinx.coroutines.launch

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
        lifecycleScope.launch {
            state.status = ModelAdapter.Status.DOWNLOADING
            adapter.notifyItemChanged(adapter.models.indexOf(state))
            try {
                val file = ModelDownloader.download(this@MainActivity, state.model) { pct ->
                    state.progress = pct
                    adapter.notifyItemChanged(adapter.models.indexOf(state))
                }
                if (file != null) {
                    state.status = ModelAdapter.Status.INSTALLED
                    activateModel(state)
                } else {
                    state.status = ModelAdapter.Status.NOT_INSTALLED
                    adapter.notifyItemChanged(adapter.models.indexOf(state))
                    Toast.makeText(this@MainActivity, "Download failed", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                DebugLog.e("MainActivity", "Download error", e)
                state.status = ModelAdapter.Status.NOT_INSTALLED
                adapter.notifyItemChanged(adapter.models.indexOf(state))
                Toast.makeText(this@MainActivity, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun activateModel(state: ModelAdapter.ModelState) {
        lifecycleScope.launch {
            Settings.setActiveModel(state.model.engine.name, state.model.id)
            val loaded = EngineManager.loadEngine(this@MainActivity, state.model.engine)
            if (loaded != null && loaded.isLoaded) {
                adapter.models.forEach { it.isActive = (it.model.id == state.model.id) }
                adapter.notifyDataSetChanged()
                refreshUi()
                Toast.makeText(this@MainActivity, "${state.model.name} active", Toast.LENGTH_SHORT).show()
            } else {
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
