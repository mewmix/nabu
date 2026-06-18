import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.mewmix.nabu.ui.theme.AppTypography
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

    val colorScheme = when {
        isDynamicColorSupported && darkTheme -> dynamicDarkColorScheme(context)
        isDynamicColorSupported && !darkTheme -> dynamicLightColorScheme(context)
        darkTheme -> createDarkColorScheme(customTheme)
        else -> createLightColorScheme(customTheme)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content
    )
}
