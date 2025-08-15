import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

val LightColorScheme = lightColorScheme(
    primary = Color(0xFF3F51B5), // Your main Indigo color (Unchanged)
    onPrimary = Color(0xFFFFFFFF),

    // User's message bubble: A brighter, more modern light blue.
    primaryContainer = Color(0xFFDDE2FF),
    onPrimaryContainer = Color(0xFF001257), // Dark blue text for readability

    secondary = Color(0xFF212121),
    onSecondary = Color(0xFFFFFFFF),

    // Assistant's message bubble: A clean, modern light gray.
    secondaryContainer = Color(0xFFF1F2F6),
    onSecondaryContainer = Color(0xFF1B1B1F), // Dark gray text for readability
)

// --- UPDATED DARK COLOR SCHEME ---
val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF899190), // Your main Indigo color (Unchanged)
    onPrimary = Color(0xFFFFFFFF),

    // User's message bubble: A brighter, more modern light blue.
    primaryContainer = Color(0xFFDDE2FF),
    onPrimaryContainer = Color(0xFF001257), // Dark blue text for readability

    secondary = Color(0xFF212121),
    onSecondary = Color(0xFFFFFFFF),
    // Assistant's message bubble: A clean, modern light gray.
    secondaryContainer = Color(0xFFF1F2F6),
    onSecondaryContainer = Color(0xFF1B1B1F), // Dark gray text for readability
)
val LinkColorLight = Color(0xFF3F51B5)
val LinkColorDark = Color(0xFF9FA8DA)