import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.example.nabu.ui.theme.AppTypography

/**
 * Root theme that applies the "Audio‑Brutalist" aesthetic.  Dynamic colours are
 * intentionally avoided to preserve the strict monochrome palette.  Only the
 * central audio visualiser is expected to provide expressive motion or colour.
 */
@Composable
fun NabuTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content
    )
}