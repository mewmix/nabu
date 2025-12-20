package com.example.nabu.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.example.nabu.utils.ThemeManager

fun createLightColorScheme(theme: AppTheme): androidx.compose.material3.ColorScheme {
    return lightColorScheme(
        primary = Color(theme.primary),
        onPrimary = Color(theme.onPrimary),
        primaryContainer = Color(theme.primaryContainer),
        onPrimaryContainer = Color(theme.onPrimaryContainer),
        secondary = Color(theme.secondary),
        onSecondary = Color(theme.onSecondary),
        secondaryContainer = Color(theme.secondaryContainer),
        onSecondaryContainer = Color(theme.onSecondaryContainer),
        tertiary = Color(theme.tertiary),
        onTertiary = Color(theme.onTertiary),
        tertiaryContainer = Color(theme.tertiaryContainer),
        onTertiaryContainer = Color(theme.onTertiaryContainer),
        error = Color(theme.error),
        onError = Color(theme.onError),
        errorContainer = Color(theme.errorContainer),
        onErrorContainer = Color(theme.onErrorContainer),
        background = Color(theme.background),
        onBackground = Color(theme.onBackground),
        surface = Color(theme.surface),
        onSurface = Color(theme.onSurface),
        surfaceVariant = Color(theme.surfaceVariant),
        onSurfaceVariant = Color(theme.onSurfaceVariant),
        outline = Color(theme.outline)
    )
}

fun createDarkColorScheme(theme: AppTheme): androidx.compose.material3.ColorScheme {
    // For dark mode, we ideally want to map the same keys but usually users want different colors.
    // Since AppTheme is a single data class, we are relying on ThemeManager to provide the correct AppTheme instance (light or dark).
    // The previous implementation switched defaults based on isSystemInDarkTheme in NabuTheme.
    // So here we just map 1:1.
    return darkColorScheme(
        primary = Color(theme.primary),
        onPrimary = Color(theme.onPrimary),
        primaryContainer = Color(theme.primaryContainer),
        onPrimaryContainer = Color(theme.onPrimaryContainer),
        secondary = Color(theme.secondary),
        onSecondary = Color(theme.onSecondary),
        secondaryContainer = Color(theme.secondaryContainer),
        onSecondaryContainer = Color(theme.onSecondaryContainer),
        tertiary = Color(theme.tertiary),
        onTertiary = Color(theme.onTertiary),
        tertiaryContainer = Color(theme.tertiaryContainer),
        onTertiaryContainer = Color(theme.onTertiaryContainer),
        error = Color(theme.error),
        onError = Color(theme.onError),
        errorContainer = Color(theme.errorContainer),
        onErrorContainer = Color(theme.onErrorContainer),
        background = Color(theme.background),
        onBackground = Color(theme.onBackground),
        surface = Color(theme.surface),
        onSurface = Color(theme.onSurface),
        surfaceVariant = Color(theme.surfaceVariant),
        onSurfaceVariant = Color(theme.onSurfaceVariant),
        outline = Color(theme.outline)
    )
}

@Composable
fun NabuTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val isDynamicColorSupported = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val customTheme = remember(context) { ThemeManager.getTheme(context) }

    // Note: ThemeManager.getTheme currently returns a single stored theme.
    // If the user wants separate Light/Dark themes, we need to handle that.
    // The previous code had a naive switch:
    // createDarkColorScheme(if (customTheme == ThemeManager.DEFAULT_LIGHT) ThemeManager.DEFAULT_DARK else customTheme)
    // This implies if the user hasn't customized (is using DEFAULT_LIGHT), we switch to DEFAULT_DARK.
    // If they HAVE customized, we use their custom theme (which might be light or dark).
    // We will preserve this logic for now, but in the future we might want separate "Custom Light" and "Custom Dark" slots.

    val colorScheme = when {
        isDynamicColorSupported && darkTheme -> dynamicDarkColorScheme(context)
        isDynamicColorSupported && !darkTheme -> dynamicLightColorScheme(context)
        darkTheme -> {
             // If the current custom theme is exactly the default light theme, swap to default dark.
             // Otherwise use the custom theme (assuming the user customized it for their preference).
             if (customTheme == ThemeManager.DEFAULT_LIGHT) {
                 createDarkColorScheme(ThemeManager.DEFAULT_DARK)
             } else {
                 createDarkColorScheme(customTheme)
             }
        }
        else -> createLightColorScheme(customTheme)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content
    )
}
