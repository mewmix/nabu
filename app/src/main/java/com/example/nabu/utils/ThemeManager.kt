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
    val DEFAULT_LIGHT = AppTheme()
    val DEFAULT_DARK = AppTheme(
        primary = 0xFF899190,
        primaryContainer = 0xFFDDE2FF,
        onPrimaryContainer = 0xFF001257,
        secondary = 0xFF212121,
        secondaryContainer = 0xFFF1F2F6,
        onSecondaryContainer = 0xFF1B1B1F
    )

    fun saveTheme(context: Context, theme: AppTheme) {
        val json = gson.toJson(theme)
        DatabaseManager.setSetting(context, PREF_KEY_THEME, json)
        // Also auto-export for persistence outside app data
        exportTheme(context, theme, "current_theme.json")
    }

    fun getTheme(context: Context): AppTheme {
        val json = DatabaseManager.getSetting(context, PREF_KEY_THEME)
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
            // Try public Documents first if possible, else external files dir
            val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            val nabuDir = File(publicDir, EXPORT_DIR_NAME)

            // Fallback to app external dir if public one is not writable (though on 10+ we might need SAF for public, let's try getExternalFilesDir first for guaranteed access)
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
        // This is a simplified import that expects a file path.
        // For real URI handling (SAF), we'd need ContentResolver.
        // Assuming user puts file in the export dir for now.
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
}
