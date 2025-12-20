package com.example.nabu.utils

import android.content.Context
import android.os.Environment
import com.example.nabu.ui.theme.AppTheme
import com.google.gson.Gson
import java.io.File
import java.io.FileWriter
import java.io.FileReader

object ThemeManager {
    private const val PREF_KEY_THEME = "current_theme"
    private const val EXPORT_DIR_NAME = "Nabu/Themes"
    private val gson = Gson()

    // Default themes
    val DEFAULT_LIGHT = AppTheme() // Uses default values from AppTheme constructor
    val DEFAULT_DARK = AppTheme(
        primary = 0xFF899190,
        onPrimary = 0xFFFFFFFF, // Inferred default
        primaryContainer = 0xFFDDE2FF,
        onPrimaryContainer = 0xFF001257,
        secondary = 0xFF212121,
        onSecondary = 0xFFFFFFFF, // Inferred default
        secondaryContainer = 0xFFF1F2F6,
        onSecondaryContainer = 0xFF1B1B1F,
        tertiary = 0xFFCCC2DC,
        onTertiary = 0xFF332D41,
        tertiaryContainer = 0xFF4A4458,
        onTertiaryContainer = 0xFFE8DEF8,
        error = 0xFFF2B8B5,
        onError = 0xFF601410,
        errorContainer = 0xFF8C1D18,
        onErrorContainer = 0xFFF9DEDC,
        background = 0xFF1B1B1F,
        onBackground = 0xFFE3E2E6,
        surface = 0xFF1B1B1F,
        onSurface = 0xFFE3E2E6,
        surfaceVariant = 0xFF444746,
        onSurfaceVariant = 0xFFC4C7C5,
        outline = 0xFF8E9099
    )

    fun saveTheme(context: Context, theme: AppTheme) {
        val json = gson.toJson(theme)
        SettingsManager.setSetting(context, PREF_KEY_THEME, json)
        exportTheme(context, theme, "current_theme.json")
    }

    fun getTheme(context: Context): AppTheme {
        val json = SettingsManager.getSetting(context, PREF_KEY_THEME)
        return if (json != null) {
            try {
                gson.fromJson(json, AppTheme::class.java)
            } catch (e: Exception) {
                DebugLogger.log("ThemeManager: Failed to parse saved theme, using default. ${e.message}")
                DEFAULT_LIGHT
            }
        } else {
            DEFAULT_LIGHT
        }
    }

    fun exportTheme(context: Context, theme: AppTheme, filename: String): Boolean {
        return try {
            val json = gson.toJson(theme)
            val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            val nabuDir = File(publicDir, EXPORT_DIR_NAME)

            val targetDir = if (nabuDir.exists() || nabuDir.mkdirs()) {
                nabuDir
            } else {
                File(context.getExternalFilesDir(null), "Themes")
            }

            if (!targetDir.exists()) targetDir.mkdirs()

            val file = File(targetDir, filename)
            FileWriter(file).use { it.write(json) }
            DebugLogger.log("ThemeManager: Exported theme to ${file.absolutePath}")
            true
        } catch (e: Exception) {
            DebugLogger.log("ThemeManager: Failed to export theme: ${e.message}")
            false
        }
    }

    fun importTheme(context: Context, uri: String): AppTheme? {
        return try {
            val file = File(uri)
            if (file.exists()) {
                val json = FileReader(file).use { it.readText() }
                val theme = gson.fromJson(json, AppTheme::class.java)
                saveTheme(context, theme)
                theme
            } else {
                null
            }
        } catch (e: Exception) {
            DebugLogger.log("ThemeManager: Failed to import theme: ${e.message}")
            null
        }
    }

    fun resetToDefault(context: Context) {
        saveTheme(context, DEFAULT_LIGHT)
    }
}
