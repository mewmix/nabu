package com.example.nabu.utils

import android.content.Context

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

    fun setTtsEngine(context: Context, engine: TtsEngine) {
        DatabaseManager.setSetting(context, "tts_engine", engine.name)
    }

    fun getTtsEngine(context: Context, default: TtsEngine = TtsEngine.KOKORO): TtsEngine =
        DatabaseManager.getSetting(context, "tts_engine")?.let {
            runCatching { TtsEngine.valueOf(it) }.getOrNull()
        } ?: default

    fun setTtsEnabled(context: Context, enabled: Boolean) {
        DatabaseManager.setSetting(context, "tts_enabled", if (enabled) "1" else "0")
    }

    fun isTtsEnabled(context: Context, default: Boolean = true): Boolean {
        val fallback = if (default) "1" else "0"
        return (DatabaseManager.getSetting(context, "tts_enabled") ?: fallback) == "1"
    }

    fun setChatterboxExaggeration(context: Context, value: Float) {
        DatabaseManager.setSetting(context, "chatterbox_exaggeration", value.toString())
    }

    fun getChatterboxExaggeration(context: Context, default: Float = 0.5f): Float =
        DatabaseManager.getSetting(context, "chatterbox_exaggeration")?.toFloatOrNull() ?: default

    fun setChatterboxReferenceVoice(context: Context, path: String?) {
        if (path == null) {
            DatabaseManager.setSetting(context, "chatterbox_reference_voice", "")
        } else {
            DatabaseManager.setSetting(context, "chatterbox_reference_voice", path)
        }
    }

    fun getChatterboxReferenceVoice(context: Context): String? =
        DatabaseManager.getSetting(context, "chatterbox_reference_voice")?.takeIf { it.isNotBlank() }

    fun setChatterboxNnapi(context: Context, enabled: Boolean) {
        DatabaseManager.setSetting(context, "chatterbox_nnapi", if (enabled) "1" else "0")
    }

    fun isChatterboxNnapi(context: Context, default: Boolean = false): Boolean {
        val fallback = if (default) "1" else "0"
        return (DatabaseManager.getSetting(context, "chatterbox_nnapi") ?: fallback) == "1"
    }
}
