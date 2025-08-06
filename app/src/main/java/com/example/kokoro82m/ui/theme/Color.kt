import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

val LightColorScheme = lightColorScheme(
    primary = Color(0xFF3F51B5),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC5CAE9),
    onPrimaryContainer = Color(0xFF1A237E),
    secondary = Color(0xFF212121),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE0E0E0),
    onSecondaryContainer = Color(0xFF212121),
)

val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF3F51B5),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF303F9F),
    onPrimaryContainer = Color(0xFFE8EAF6),
    secondary = Color(0xFFBDBDBD),
    onSecondary = Color(0xFF212121),
    secondaryContainer = Color(0xFF424242),
    onSecondaryContainer = Color(0xFFE0E0E0),
)

val LinkColorLight = Color(0xFF3F51B5)
val LinkColorDark = Color(0xFF9FA8DA)