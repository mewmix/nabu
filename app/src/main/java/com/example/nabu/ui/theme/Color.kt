import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// The brutalist palette is intentionally stark and relies on a limited
// range of utilitarian greys.  Bright accents are avoided so that the
// central audio visualiser becomes the only source of motion and colour
// within the interface.

val LightColorScheme = lightColorScheme(
    primary = Color(0xFF000000),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF444444),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF000000),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF000000)
)

val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFFFFFFF),
    onPrimary = Color(0xFF000000),
    secondary = Color(0xFF888888),
    onSecondary = Color(0xFF000000),
    background = Color(0xFF000000),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFFFFFFF)
)

// Links retain a subtle hint of colour to indicate interactivity while
// remaining within the overall monochrome scheme.
val LinkColorLight = Color(0xFF3F51B5)
val LinkColorDark = Color(0xFF9FA8DA)