package com.example.kokoro82m.utils

import android.util.Log

object DebugLogger {
    private val logs = mutableListOf<String>()

    @Synchronized
    fun log(message: String) {
        if (logs.size > 50) logs.removeAt(0)
        logs.add(message)
        Log.d("Kokoro", message)
    }

    fun getLogs(): List<String> = logs.toList()
}
