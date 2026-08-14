package com.taptype.taptypepro.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.taptype.taptypepro.databinding.ActivityHistoryBinding
import com.taptype.taptypepro.util.HistoryStore

class HistoryActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHistoryBinding
    private lateinit var adapter: HistoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = HistoryAdapter(HistoryStore.load())
        binding.historyList.layoutManager = LinearLayoutManager(this)
        binding.historyList.adapter = adapter

        binding.clearBtn.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear history?")
                .setPositiveButton("Clear") { _, _ ->
                    HistoryStore.clear()
                    adapter.update(emptyList())
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
}
