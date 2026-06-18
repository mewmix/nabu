import androidx.compose.foundation.shape.RoundedCornerShape
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mewmix.nabu.ui.design.LocalNabuChrome
import com.mewmix.nabu.ui.design.LocalNabuUiMode
import com.mewmix.nabu.ui.design.NabuChrome
import com.mewmix.nabu.ui.design.NabuUiMode
import com.mewmix.nabu.ui.theme.appTypography
import com.mewmix.nabu.ui.theme.createDarkColorScheme
import com.mewmix.nabu.ui.theme.createLightColorScheme
import com.mewmix.nabu.utils.ThemeManager

@Composable
fun NabuTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val isDynamicColorSupported = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val customTheme = ThemeManager.getTheme(context, darkTheme)
    val themeMode = ThemeManager.getThemeMode(context)
    val uiMode = if (themeMode == ThemeManager.ThemeMode.BRUTAL) {
        NabuUiMode.Brutal
    } else {
        NabuUiMode.Modern
    }
    val chrome = if (uiMode == NabuUiMode.Brutal) {
        NabuChrome.Brutal
    } else {
        NabuChrome.Modern.copy(
            panelRadius = customTheme.panelRadiusDp.dpOrDefault(NabuChrome.Modern.panelRadius),
            controlRadius = customTheme.controlRadiusDp.dpOrDefault(NabuChrome.Modern.controlRadius),
            borderWidth = customTheme.borderWidthDp.dpOrDefault(NabuChrome.Modern.borderWidth)
        )
    }

    val colorScheme = when {
        isDynamicColorSupported && darkTheme -> dynamicDarkColorScheme(context)
        isDynamicColorSupported && !darkTheme -> dynamicLightColorScheme(context)
        darkTheme -> createDarkColorScheme(customTheme)
        else -> createLightColorScheme(customTheme)
    }

    CompositionLocalProvider(
        LocalNabuUiMode provides uiMode,
        LocalNabuChrome provides chrome
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = appTypography(brutal = uiMode == NabuUiMode.Brutal),
            shapes = Shapes(
                extraSmall = RoundedCornerShape(chrome.controlRadius / 3f),
                small = RoundedCornerShape(chrome.controlRadius / 2f),
                medium = RoundedCornerShape(chrome.controlRadius),
                large = RoundedCornerShape(chrome.panelRadius),
                extraLarge = RoundedCornerShape(chrome.panelRadius)
            ),
            content = content
        )
    }
}

private fun Float?.dpOrDefault(default: Dp): Dp =
    this?.takeIf { it > 0f }?.dp ?: default
