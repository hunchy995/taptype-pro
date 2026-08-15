package com.taptype.taptypepro.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.taptype.taptypepro.R
import com.taptype.taptypepro.engine.ModelRegistry
import java.io.File

class ModelAdapter(
    private var items: List<ModelState>,
    private val onAction: (ModelState, String) -> Unit
) : RecyclerView.Adapter<ModelAdapter.VH>() {

    enum class Status { NOT_INSTALLED, DOWNLOADING, INSTALLED }

    class ModelState(val model: ModelRegistry.Model) {
        var status = Status.NOT_INSTALLED
        var isActive = false
        var isLoading = false
        var progress = 0
    }

    val models: List<ModelState>
        get() = items

    fun indexOfModel(modelId: String): Int = items.indexOfFirst { it.model.id == modelId }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.modelName)
        val desc: TextView = view.findViewById(R.id.modelDesc)
        val size: TextView = view.findViewById(R.id.modelSize)
        val actionBtn: MaterialButton = view.findViewById(R.id.actionBtn)
        val progressBar: ProgressBar = view.findViewById(R.id.progressBar)
        val progressText: TextView = view.findViewById(R.id.progressText)
    }

    fun updateModels(newItems: List<ModelState>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_model, parent, false)
        return VH(view)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val state = items[position]
        holder.name.text = state.model.name + when {
            state.isActive -> " ●"
            state.isLoading -> " …"
            else -> ""
        }
        holder.desc.text = state.model.description
        holder.size.text = "${state.model.sizeMB} MB"
        holder.progressBar.progress = state.progress
        holder.progressText.text = "${state.progress}%"

        when {
            state.isLoading -> {
                holder.actionBtn.text = "Loading…"
                holder.actionBtn.visibility = View.VISIBLE
                holder.actionBtn.isEnabled = false
                holder.progressBar.visibility = View.GONE
                holder.progressText.visibility = View.GONE
            }
            state.status == Status.NOT_INSTALLED -> {
                holder.actionBtn.text = "Download"
                holder.actionBtn.visibility = View.VISIBLE
                holder.actionBtn.isEnabled = true
                holder.progressBar.visibility = View.GONE
                holder.progressText.visibility = View.GONE
                holder.actionBtn.setOnClickListener { onAction(state, "download") }
            }
            state.status == Status.DOWNLOADING -> {
                holder.actionBtn.visibility = View.GONE
                holder.progressBar.visibility = View.VISIBLE
                holder.progressText.visibility = View.VISIBLE
            }
            state.status == Status.INSTALLED -> {
                holder.actionBtn.text = if (state.isActive) "Active" else "Activate"
                holder.actionBtn.visibility = View.VISIBLE
                holder.actionBtn.isEnabled = true
                holder.progressBar.visibility = View.GONE
                holder.progressText.visibility = View.GONE
                holder.actionBtn.setOnClickListener { if (!state.isActive) onAction(state, "activate") }
                holder.itemView.setOnLongClickListener {
                    onAction(state, "delete")
                    true
                }
            }
        }
    }
}

fun ModelRegistry.Model.toState(context: android.content.Context): ModelAdapter.ModelState {
    val state = ModelAdapter.ModelState(this)
    val file = ModelRegistry.modelFile(context, this)
    if (file.exists() && file.length() > 0) {
        state.status = ModelAdapter.Status.INSTALLED
    }
    val activeId = com.taptype.taptypepro.util.Settings.activeModel(this.engine.name)
    state.isActive = (activeId == this.id && state.status == ModelAdapter.Status.INSTALLED)
    return state
}
