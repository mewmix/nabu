package com.example.nabu.utils

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.util.UUID

object LlmStructuredLogger {
    private const val LOGCAT_TAG = "NABU_LLM_JSON"
    private const val FILENAME = "llm_debug.jsonl"
    private const val SCHEMA_VERSION = "1.0.0"

    private var logFile: File? = null
    private val gson = Gson()
    private val logChannel = Channel<String>(Channel.UNLIMITED)
    private val scope = CoroutineScope(Dispatchers.IO)

    fun initialize(context: Context) {
        val logDirectory = File(context.getExternalFilesDir(null), "logs")
        if (!logDirectory.exists()) {
            logDirectory.mkdirs()
        }
        logFile = File(logDirectory, FILENAME)

        // Start the writer coroutine
        scope.launch {
            for (jsonLine in logChannel) {
                writeLine(jsonLine)
            }
        }
    }

    fun logEvent(
        eventType: String,
        context: Context? = null,
        modelId: String? = null,
        extras: Map<String, Any?> = emptyMap()
    ) {
        val baseEvent = mutableMapOf<String, Any?>(
            "ts_ms" to System.currentTimeMillis(),
            "event_type" to eventType,
            "schema_version" to SCHEMA_VERSION,
            "session_id" to SessionManager.sessionId,
            "device" to "${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT})"
        )

        if (context != null) {
            baseEvent["app_version"] = getAppVersion(context)
        }

        if (modelId != null) {
            baseEvent["model_id"] = modelId
        }

        baseEvent.putAll(extras)

        val jsonLine = gson.toJson(baseEvent)

        // Emit to logcat immediately
        Log.i(LOGCAT_TAG, jsonLine)

        // Queue for file writing
        logChannel.trySend(jsonLine)
    }

    private fun writeLine(line: String) {
        logFile?.let { file ->
            try {
                FileWriter(file, true).use { writer ->
                    writer.write(line)
                    writer.write("\n")
                }
            } catch (e: IOException) {
                Log.e(LOGCAT_TAG, "Failed to write to JSONL log", e)
            }
        }
    }
}

object SessionManager {
    val sessionId: String = UUID.randomUUID().toString()
}

// Helper to avoid circular dependency if getAppVersion is in a different module,
// but assuming it's available or we can duplicate/simplify.
// The existing getAppVersion is in core-utils/src/main/java/com/example/nabu/utils/AppUtils.kt likely,
// or directly in the file we saw SettingsScreen use.
// We will assume the user passes the version string or context.
fun getAppVersion(context: Context): String {
    return try {
        val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        pInfo.versionName
    } catch (e: Exception) {
        "unknown"
    }
}
