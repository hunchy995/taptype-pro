package com.taptype.taptypepro.util

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class HistoryEntry(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val engine: String,
    val model: String,
    val durationMs: Long,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun formattedDate(): String {
        return SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(timestamp))
    }
}

object HistoryStore {
    private const val MAX_ENTRIES = 100
    private const val FILE_NAME = "history_v1.json"
    private val lock = Any()
    private lateinit var file: File

    fun init(context: Context) {
        file = File(context.filesDir, FILE_NAME)
    }

    fun add(entry: HistoryEntry) {
        synchronized(lock) {
            val list = load().toMutableList()
            list.add(0, entry)
            if (list.size > MAX_ENTRIES) list.removeAt(list.lastIndex)
            save(list)
        }
    }

    fun load(): List<HistoryEntry> {
        synchronized(lock) {
            if (!::file.isInitialized || !file.exists()) return emptyList()
            return try {
                JsonExt.decodeHistoryList(file.readText())
            } catch (e: Exception) {
                DebugLog.e("HistoryStore", "Failed to load history", e)
                emptyList()
            }
        }
    }

    private fun save(list: List<HistoryEntry>) {
        try {
            file.writeText(JsonExt.encodeHistoryList(list))
        } catch (e: Exception) {
            DebugLog.e("HistoryStore", "Failed to save history", e)
        }
    }

    fun clear() {
        synchronized(lock) {
            file.writeText("[]")
        }
    }
}
