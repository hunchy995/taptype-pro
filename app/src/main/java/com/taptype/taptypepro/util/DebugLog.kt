package com.taptype.taptypepro.util

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DebugLog {
    private const val FILE_NAME = "debug.log"
    private const val MAX_LINES = 5000
    private lateinit var file: File
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        file = File(context.filesDir, FILE_NAME)
    }

    fun i(tag: String, message: String) = log("I", tag, message)
    fun d(tag: String, message: String) = log("D", tag, message)
    fun w(tag: String, message: String) = log("W", tag, message)
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val extra = throwable?.let { "\n${it.stackTraceToString()}" } ?: ""
        log("E", tag, "$message$extra")
    }

    private fun log(level: String, tag: String, message: String) {
        if (!::file.isInitialized) return
        val line = "${dateFormat.format(Date())} $level/$tag: $message\n"
        synchronized(this) {
            FileWriter(file, true).use { it.write(line) }
            trimIfNeeded()
        }
    }

    private fun trimIfNeeded() {
        val lines = file.readLines()
        if (lines.size > MAX_LINES) {
            file.writeText(lines.takeLast(MAX_LINES).joinToString("\n") + "\n")
        }
    }

    fun getText(): String {
        if (!::file.isInitialized || !file.exists()) return "No logs yet."
        return file.readText()
    }

    fun clear() {
        if (::file.isInitialized) file.writeText("")
    }
}
