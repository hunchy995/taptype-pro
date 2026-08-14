package com.taptype.taptypepro.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.taptype.taptypepro.databinding.ActivityDebugLogBinding
import com.taptype.taptypepro.util.DebugLog

class DebugLogActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDebugLogBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDebugLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        refreshLog()

        binding.copyBtn.setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("TapType Pro Debug", binding.logText.text))
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        }

        binding.clearBtn.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear debug log?")
                .setPositiveButton("Clear") { _, _ ->
                    DebugLog.clear()
                    refreshLog()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun refreshLog() {
        binding.logText.setText(DebugLog.getText())
    }
}
