package com.example.nabu.utils

import android.content.Context
import com.example.nabu.kokoro.RunEp

object SettingsManager {
    private const val KEY_INIT_COMPLETE = "init_complete"
    private const val KEY_KOKORO_AUTO_DOWNLOAD = "kokoro_auto_download"
    private const val KEY_SUPERTONIC_MODEL_ID = "supertonic_model_id"
    private const val KEY_LAST_BOOK_URI = "last_book_uri"

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

    fun setTtsEngine(context: Context, engine: String) {
        DatabaseManager.setSetting(context, "tts_engine", engine)
    }

    fun getTtsEngine(context: Context, default: String = "kokoro"): String =
        DatabaseManager.getSetting(context, "tts_engine") ?: default

    fun setInitComplete(context: Context, complete: Boolean) {
        DatabaseManager.setSetting(context, KEY_INIT_COMPLETE, if (complete) "1" else "0")
    }

    fun isInitComplete(context: Context): Boolean =
        (DatabaseManager.getSetting(context, KEY_INIT_COMPLETE) ?: "0") == "1"

    fun setKokoroAutoDownload(context: Context, enabled: Boolean) {
        DatabaseManager.setSetting(context, KEY_KOKORO_AUTO_DOWNLOAD, if (enabled) "1" else "0")
    }

    fun isKokoroAutoDownloadEnabled(context: Context, default: Boolean = true): Boolean {
        val fallback = if (default) "1" else "0"
        return (DatabaseManager.getSetting(context, KEY_KOKORO_AUTO_DOWNLOAD) ?: fallback) == "1"
    }

    fun setSupertonicModelId(context: Context, modelId: String?) {
        if (modelId.isNullOrBlank()) {
            DatabaseManager.setSetting(context, KEY_SUPERTONIC_MODEL_ID, "")
        } else {
            DatabaseManager.setSetting(context, KEY_SUPERTONIC_MODEL_ID, modelId)
        }
    }

    fun getSupertonicModelId(context: Context): String? =
        DatabaseManager.getSetting(context, KEY_SUPERTONIC_MODEL_ID)?.ifBlank { null }

    fun setLastBookUri(context: Context, uri: String?) {
        DatabaseManager.setSetting(context, KEY_LAST_BOOK_URI, uri ?: "")
    }

    fun getLastBookUri(context: Context): String? =
        DatabaseManager.getSetting(context, KEY_LAST_BOOK_URI)?.ifBlank { null }

    fun setVibrationsEnabled(context: Context, enabled: Boolean) {
        DatabaseManager.setSetting(context, "vibrations_enabled", if (enabled) "1" else "0")
    }

    fun isVibrationsEnabled(context: Context, default: Boolean = true): Boolean {
        val fallback = if (default) "1" else "0"
        return (DatabaseManager.getSetting(context, "vibrations_enabled") ?: fallback) == "1"
    }
}
