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

    fun setRadialWaveform(context: Context, enabled: Boolean) {
        DatabaseManager.setSetting(context, "radial_waveform", if (enabled) "1" else "0")
    }

    fun isRadialWaveform(context: Context): Boolean =
        (DatabaseManager.getSetting(context, "radial_waveform") ?: "0") == "1"
}
