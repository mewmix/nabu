import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

val LightColorScheme = lightColorScheme(
    primary = Color(0xFFB388FF),
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFFEDE7F6),
    onPrimaryContainer = Color(0xFF1B0033),
    secondary = Color(0xFF8C9EFF),
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFFE8EAF6),
    onSecondaryContainer = Color(0xFF121A48),
)

val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFB388FF),
    onPrimary = Color(0xFF371E73),
    primaryContainer = Color(0xFF6C43A5),
    onPrimaryContainer = Color(0xFFEDE7F6),
    secondary = Color(0xFF8C9EFF),
    onSecondary = Color(0xFF1A237E),
    secondaryContainer = Color(0xFF3949AB),
    onSecondaryContainer = Color(0xFFE8EAF6),
)

val LinkColorLight = Color(0xFF0000EE)
val LinkColorDark = Color(0xFF9E9EFF)