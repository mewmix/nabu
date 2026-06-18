package com.mewmix.nabu.utils

import android.content.Context
import android.os.Environment
import com.mewmix.nabu.ui.theme.AppTheme
import com.google.gson.Gson
import java.io.File
import java.io.FileWriter
import java.io.FileReader

object ThemeManager {
    private const val PREF_KEY_THEME = "current_theme"
    private const val PREF_KEY_THEME_MODE = "theme_mode"
    private const val EXPORT_DIR_NAME = "Nabu/Themes"
    private val gson = Gson()

    enum class ThemeMode(val storageValue: String, val label: String) {
        MODERN("modern", "Modern"),
        BRUTAL("brutal", "Brutal"),
        CUSTOM("custom", "Custom");

        companion object {
            fun fromStorage(value: String?): ThemeMode =
                entries.firstOrNull { it.storageValue == value } ?: MODERN
        }
    }

    // Default themes
    val DEFAULT_LIGHT = AppTheme(
        primary = 0xFFE86F51,
        onPrimary = 0xFFFFFFFF,
        primaryContainer = 0xFFFFDED4,
        onPrimaryContainer = 0xFF3D160D,
        secondary = 0xFF3E8D72,
        onSecondary = 0xFFFFFFFF,
        secondaryContainer = 0xFFC9F0DF,
        onSecondaryContainer = 0xFF0D2D22,
        tertiary = 0xFF4E6FAE,
        onTertiary = 0xFFFFFFFF,
        background = 0xFFFAF6F0,
        onBackground = 0xFF2D2518,
        surface = 0xFFFFFFFF,
        onSurface = 0xFF2D2518,
        surfaceVariant = 0xFFF0E8DC,
        onSurfaceVariant = 0xFF5B5147,
        outline = 0xFFD4C8B8
    )
    val DEFAULT_DARK = AppTheme(
        primary = 0xFFFF9B7F,
        onPrimary = 0xFF3D160D,
        primaryContainer = 0xFF663225,
        onPrimaryContainer = 0xFFFFDED4,
        secondary = 0xFF7EDDB9,
        onSecondary = 0xFF0D2D22,
        secondaryContainer = 0xFF265A49,
        onSecondaryContainer = 0xFFC9F0DF,
        tertiary = 0xFF9DB9F7,
        onTertiary = 0xFF13264F,
        background = 0xFF201A14,
        onBackground = 0xFFFAF6F0,
        surface = 0xFF2D2518,
        onSurface = 0xFFFAF6F0,
        surfaceVariant = 0xFF3D3629,
        onSurfaceVariant = 0xFFE0D8CC,
        outline = 0xFF6B5E4E
    )

    val BRUTAL_LIGHT = AppTheme(
        primary = 0xFFFFB020,
        onPrimary = 0xFF141414,
        primaryContainer = 0xFF2B2B2B,
        onPrimaryContainer = 0xFFFFF3D0,
        secondary = 0xFF77E6A3,
        onSecondary = 0xFF0D1A12,
        secondaryContainer = 0xFF1D2A23,
        onSecondaryContainer = 0xFFD7FFE5,
        tertiary = 0xFF9FA8DA,
        onTertiary = 0xFF111827,
        background = 0xFF111214,
        onBackground = 0xFFEDEDED,
        surface = 0xFF17191C,
        onSurface = 0xFFEDEDED,
        surfaceVariant = 0xFF24272B,
        onSurfaceVariant = 0xFFB8B8B8,
        outline = 0xFF555A62
    )

    val BRUTAL_DARK = BRUTAL_LIGHT.copy(
        background = 0xFF0B0C0E,
        surface = 0xFF121417,
        surfaceVariant = 0xFF1A1D21
    )

    fun setThemeMode(context: Context, mode: ThemeMode) {
        DatabaseManager.setSetting(context, PREF_KEY_THEME_MODE, mode.storageValue)
    }

    fun getThemeMode(context: Context): ThemeMode =
        ThemeMode.fromStorage(DatabaseManager.getSetting(context, PREF_KEY_THEME_MODE))

    fun saveTheme(context: Context, theme: AppTheme) {
        val json = gson.toJson(theme)
        DatabaseManager.setSetting(context, PREF_KEY_THEME, json)
        setThemeMode(context, ThemeMode.CUSTOM)
        // Also auto-export for persistence outside app data
        exportTheme(context, theme, "current_theme.json")
    }

    fun getTheme(context: Context): AppTheme {
        return when (getThemeMode(context)) {
            ThemeMode.MODERN -> DEFAULT_LIGHT
            ThemeMode.BRUTAL -> BRUTAL_LIGHT
            ThemeMode.CUSTOM -> getCustomTheme(context)
        }
    }

    fun getTheme(context: Context, darkTheme: Boolean): AppTheme {
        return when (getThemeMode(context)) {
            ThemeMode.MODERN -> if (darkTheme) DEFAULT_DARK else DEFAULT_LIGHT
            ThemeMode.BRUTAL -> if (darkTheme) BRUTAL_DARK else BRUTAL_LIGHT
            ThemeMode.CUSTOM -> getCustomTheme(context)
        }
    }

    private fun getCustomTheme(context: Context): AppTheme {
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
