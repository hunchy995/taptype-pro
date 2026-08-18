package com.taptype.taptypepro.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.taptype.taptypepro.R
import com.taptype.taptypepro.databinding.ActivityBlockedAppsBinding
import com.taptype.taptypepro.util.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BlockedAppsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBlockedAppsBinding
    private lateinit var adapter: BlockedAppsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBlockedAppsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.title = getString(R.string.blocked_apps_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        Settings.init(this)
        adapter = BlockedAppsAdapter(
            selected = Settings.blockedPackages().toMutableSet(),
            onToggle = { packageName, checked ->
                if (checked) Settings.addBlockedPackage(packageName)
                else Settings.removeBlockedPackage(packageName)
            }
        )

        binding.blockedAppsList.layoutManager = LinearLayoutManager(this)
        binding.blockedAppsList.adapter = adapter

        binding.addByPackageName.setOnClickListener { showAddByPackageDialog() }

        loadApps()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun loadApps() {
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.Default) { loadLaunchableApps(this@BlockedAppsActivity) }
            adapter.submitList(apps)
        }
    }

    private fun showAddByPackageDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.blocked_apps_package_hint)
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.blocked_apps_add_by_package)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val packageName = input.text.toString().trim()
                if (packageName.isNotEmpty()) {
                    Settings.addBlockedPackage(packageName)
                    adapter.select(packageName)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    data class AppItem(
        val packageName: String,
        val label: CharSequence,
        val icon: android.graphics.drawable.Drawable
    )

    private fun loadLaunchableApps(context: Context): List<AppItem> {
        val pm = context.packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        }
        return pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .map {
                val info = it.activityInfo.applicationInfo
                AppItem(
                    packageName = info.packageName,
                    label = info.loadLabel(pm),
                    icon = info.loadIcon(pm)
                )
            }
            .distinctBy { it.packageName }
            .filter { it.packageName != context.packageName }
            .sortedBy { it.label.toString().lowercase() }
    }

    class BlockedAppsAdapter(
        private val selected: MutableSet<String>,
        private val onToggle: (String, Boolean) -> Unit
    ) : RecyclerView.Adapter<BlockedAppsAdapter.ViewHolder>() {

        private var items: List<AppItem> = emptyList()

        fun submitList(newItems: List<AppItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        fun select(packageName: String) {
            selected.add(packageName)
            val idx = items.indexOfFirst { it.packageName == packageName }
            if (idx >= 0) notifyItemChanged(idx)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_blocked_app, parent, false)
            return ViewHolder(view)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val icon: ImageView = itemView.findViewById(R.id.appIcon)
            private val label: TextView = itemView.findViewById(R.id.appLabel)
            private val packageName: TextView = itemView.findViewById(R.id.appPackage)
            private val checkBox: CheckBox = itemView.findViewById(R.id.appCheckBox)

            fun bind(item: AppItem) {
                icon.setImageDrawable(item.icon)
                label.text = item.label
                packageName.text = item.packageName
                checkBox.setOnCheckedChangeListener(null)
                checkBox.isChecked = selected.contains(item.packageName)
                itemView.setOnClickListener { checkBox.toggle() }
                checkBox.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) selected.add(item.packageName) else selected.remove(item.packageName)
                    onToggle(item.packageName, isChecked)
                }
            }
        }
    }
}
