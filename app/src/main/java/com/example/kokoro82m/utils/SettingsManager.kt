package com.example.kokoro82m.utils

import android.content.Context

object SettingsManager {
    private const val PREFS_NAME = "user_settings"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun setDebug(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("debug", enabled).apply()
    }

    fun isDebug(context: Context): Boolean =
        prefs(context).getBoolean("debug", false)

    fun setStyle(context: Context, style: String) {
        prefs(context).edit().putString("style", style).apply()
    }

    fun getStyle(context: Context, default: String = "af_sarah"): String =
        prefs(context).getString("style", default) ?: default

    fun setSpeed(context: Context, speed: Float) {
        prefs(context).edit().putFloat("speed", speed).apply()
    }

    fun getSpeed(context: Context, default: Float = 1.0f): Float =
        prefs(context).getFloat("speed", default)
}
