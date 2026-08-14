package com.taptype.taptypepro.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.taptype.taptypepro.R
import com.taptype.taptypepro.util.HistoryEntry

class HistoryAdapter(private var items: List<HistoryEntry>) : RecyclerView.Adapter<HistoryAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val date: TextView = view.findViewById(R.id.historyDate)
        val meta: TextView = view.findViewById(R.id.historyMeta)
        val text: TextView = view.findViewById(R.id.historyText)
    }

    fun update(newItems: List<HistoryEntry>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false)
        return VH(view)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = items[position]
        holder.date.text = entry.formattedDate()
        holder.meta.text = "${entry.engine} · ${entry.model} · ${entry.durationMs / 1000}s"
        holder.text.text = entry.text
    }
}
