import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

import com.example.nabu.ui.theme.AppTheme

fun createLightColorScheme(theme: AppTheme) = lightColorScheme(
    primary = Color(theme.primary),
    onPrimary = Color(theme.onPrimary),
    primaryContainer = Color(theme.primaryContainer),
    onPrimaryContainer = Color(theme.onPrimaryContainer),
    secondary = Color(theme.secondary),
    onSecondary = Color(theme.onSecondary),
    secondaryContainer = Color(theme.secondaryContainer),
    onSecondaryContainer = Color(theme.onSecondaryContainer),
)

fun createDarkColorScheme(theme: AppTheme) = darkColorScheme(
    primary = Color(theme.primary),
    onPrimary = Color(theme.onPrimary),
    primaryContainer = Color(theme.primaryContainer),
    onPrimaryContainer = Color(theme.onPrimaryContainer),
    secondary = Color(theme.secondary),
    onSecondary = Color(theme.onSecondary),
    secondaryContainer = Color(theme.secondaryContainer),
    onSecondaryContainer = Color(theme.onSecondaryContainer),
)

val LinkColorLight = Color(0xFF3F51B5)
val LinkColorDark = Color(0xFF9FA8DA)