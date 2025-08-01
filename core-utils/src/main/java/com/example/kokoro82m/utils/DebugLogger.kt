package com.example.kokoro82m.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DebugLogger {
    private var logFile: File? = null
    private val logs = mutableListOf<String>()

    @Synchronized
    fun initialize(context: Context) {
        if (logFile == null) {
            val logDirectory = File(context.getExternalFilesDir(null), "logs")
            if (!logDirectory.exists()) {
                logDirectory.mkdirs()
            }
            logFile = File(logDirectory, "kokoro_log.txt")
        }
    }

    @Synchronized
    fun log(message: String) {
        if (logs.size > 100) {
            logs.removeAt(0)
        }
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val logMessage = "$timestamp: $message"
        logs.add(logMessage)
        Log.d("Kokoro", logMessage)

        // Write to file
        try {
            FileWriter(logFile, true).use {
                it.append(logMessage)
                it.append("\n")
            }
        } catch (e: IOException) {
            Log.e("Kokoro", "Failed to write to log file", e)
        }
    }

    fun getLogs(): List<String> = logs.toList()
}
