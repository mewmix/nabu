package com.example.nabu.utils

import android.content.Context
import com.example.nabu.kokoro.RunEp

object SettingsManager {
    fun setDebug(context: Context, enabled: Boolean) {
        DatabaseManager.setSetting(context, "debug", if (enabled) "1" else "0")
    }

    fun isDebug(context: Context): Boolean =
        (DatabaseManager.getSetting(context, "debug") ?: "0") == "1"

    fun setBenchmark(context: Context, enabled: Boolean) {
        DatabaseManager.setSetting(context, "benchmark", if (enabled) "1" else "0")
    }

    fun isBenchmark(context: Context): Boolean =
        (DatabaseManager.getSetting(context, "benchmark") ?: "0") == "1"

    fun setStyle(context: Context, style: String) {
        DatabaseManager.setSetting(context, "style", style)
    }

    fun getStyle(context: Context, default: String = "af_sky"): String =
        DatabaseManager.getSetting(context, "style") ?: default

    fun setSpeed(context: Context, speed: Float) {
        DatabaseManager.setSetting(context, "speed", speed.toString())
    }

    fun getSpeed(context: Context, default: Float = 1.0f): Float =
        DatabaseManager.getSetting(context, "speed")?.toFloat() ?: default

    fun setTtsEnabled(context: Context, enabled: Boolean) {
        DatabaseManager.setSetting(context, "tts_enabled", if (enabled) "1" else "0")
    }

    fun isTtsEnabled(context: Context, default: Boolean = true): Boolean {
        val fallback = if (default) "1" else "0"
        return (DatabaseManager.getSetting(context, "tts_enabled") ?: fallback) == "1"
    }

    fun setRuntimePreference(context: Context, ep: RunEp) {
        DatabaseManager.setSetting(context, "kokoro_ep", ep.name)
    }

    fun getRuntimePreference(context: Context, default: RunEp = RunEp.AUTO): RunEp =
        DatabaseManager.getSetting(context, "kokoro_ep")?.let {
            runCatching { RunEp.valueOf(it) }.getOrNull()
        } ?: default

    // LLM Diagnostics
    fun setLlmLoggingEnabled(context: Context, enabled: Boolean) {
        DatabaseManager.setSetting(context, "llm_logging_enabled", if (enabled) "1" else "0")
        if (enabled) {
            LlmStructuredLogger.initialize(context)
        }
    }

    fun isLlmLoggingEnabled(context: Context): Boolean =
        (DatabaseManager.getSetting(context, "llm_logging_enabled") ?: "0") == "1"

    fun setLlmExperimentsEnabled(context: Context, enabled: Boolean) {
        DatabaseManager.setSetting(context, "llm_experiments_enabled", if (enabled) "1" else "0")
    }

    fun isLlmExperimentsEnabled(context: Context): Boolean =
        (DatabaseManager.getSetting(context, "llm_experiments_enabled") ?: "0") == "1"

    fun setContextTokenCapUi(context: Context, cap: Int) {
        DatabaseManager.setSetting(context, "context_token_cap_ui", cap.toString())
    }

    fun getContextTokenCapUi(context: Context, default: Int = 1024): Int {
        return DatabaseManager.getSetting(context, "context_token_cap_ui")?.toIntOrNull() ?: default
    }
}
