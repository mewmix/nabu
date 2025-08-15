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
    primary = Color(0xFFB8C3FF), // Lighter Indigo for better contrast in dark mode (Unchanged, but good)
    onPrimary = Color(0xFF00228A),

    // User's message bubble: A rich, dark blue that stands out.
    primaryContainer = Color(0xFF283A9E),
    onPrimaryContainer = Color(0xFFDDE2FF), // Light blue text for readability

    secondary = Color(0xFFBDBDBD),
    onSecondary = Color(0xFF212121),

    // Assistant's message bubble: A dark slate-blue, more harmonious than plain gray.
    secondaryContainer = Color(0xFF2A2D3E),
    onSecondaryContainer = Color(0xFFE2E2E6), // Off-white text for readability
)

val LinkColorLight = Color(0xFF3F51B5)
val LinkColorDark = Color(0xFF9FA8DA)