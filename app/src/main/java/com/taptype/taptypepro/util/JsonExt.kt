package com.taptype.taptypepro.util

import android.content.Context
import java.io.File

// Stub for JSON helper until serialization is wired.
object JsonExt {
    fun decodeHistoryList(json: String): List<HistoryEntry> {
        return try {
            val regex = """\{"text":"(.*?)","engine":"(.*?)","model":"(.*?)","durationMs":(\d+),"timestamp":(\d+)(,"id":"(.*?)")?\}""".toRegex()
            regex.findAll(json).map { m ->
                val id = m.groupValues[7].ifBlank { java.util.UUID.randomUUID().toString() }
                HistoryEntry(
                    id = id,
                    text = unescape(m.groupValues[1]),
                    engine = m.groupValues[2],
                    model = m.groupValues[3],
                    durationMs = m.groupValues[4].toLong(),
                    timestamp = m.groupValues[5].toLong()
                )
            }.toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun encodeHistoryList(list: List<HistoryEntry>): String {
        val sb = StringBuilder("[")
        list.forEachIndexed { i, e ->
            if (i > 0) sb.append(",")
            sb.append("{\"id\":\"").append(e.id).append("\",")
                .append("\"text\":\"").append(escape(e.text)).append("\",")
                .append("\"engine\":\"").append(e.engine).append("\",")
                .append("\"model\":\"").append(e.model).append("\",")
                .append("\"durationMs\":").append(e.durationMs).append(",")
                .append("\"timestamp\":").append(e.timestamp).append("}")
        }
        sb.append("]")
        return sb.toString()
    }

    private fun escape(s: String): String {
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    private fun unescape(s: String): String {
        return s.replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }
}
