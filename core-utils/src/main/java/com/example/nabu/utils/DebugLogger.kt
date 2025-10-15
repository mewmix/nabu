package com.example.nabu.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DebugLogger {
    private const val MAX_BUFFERED_LOGS = 100
    private const val TAG = "Nabu"

    private var logFile: File? = null
    private val logs = mutableListOf<String>()
    private var logcatAvailable = true

    @Synchronized
    fun initialize(context: Context) {
        if (logFile == null) {
            val logDirectory = File(context.getExternalFilesDir(null), "logs")
            if (!logDirectory.exists()) {
                logDirectory.mkdirs()
            }
            logFile = File(logDirectory, "nabu_log.txt")
        }
    }

    @Synchronized
    fun log(message: String) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val logMessage = "$timestamp: $message"

        if (logs.size >= MAX_BUFFERED_LOGS) {
            logs.removeAt(0)
        }
        logs.add(logMessage)

        if (logcatAvailable) {
            runCatching { Log.d(TAG, logMessage) }
                .onFailure { logcatAvailable = false }
        }

        logFile?.let { destination ->
            runCatching {
                FileWriter(destination, true).use {
                    it.appendLine(logMessage)
                }
            }.onFailure {
                if (logcatAvailable) {
                    runCatching { Log.e(TAG, "Failed to write to log file", it) }
                        .onFailure { logcatAvailable = false }
                }
            }
        }
    }

    fun getLogs(): List<String> = logs.toList()
}
