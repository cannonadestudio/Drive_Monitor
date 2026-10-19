package pl.drivemonitor

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Kolory spójne z ikoną aplikacji: granat + zieleń śladu GPS. */
private val Light = lightColorScheme(
    primary = Color(0xFF1565C0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7E3FF),
    onPrimaryContainer = Color(0xFF001B3F),
    secondary = Color(0xFF1E8E57),
    onSecondary = Color.White,
    background = Color(0xFFF5F7FB),
    onBackground = Color(0xFF181C22),
    surface = Color(0xFFF5F7FB),
    onSurface = Color(0xFF181C22),
    surfaceVariant = Color(0xFFE4E9F1),
    onSurfaceVariant = Color(0xFF59616D),
    outlineVariant = Color(0xFFD3D9E2),
    error = Color(0xFFC62828),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9EC3FF),
    onPrimary = Color(0xFF00315F),
    primaryContainer = Color(0xFF0B3A7A),
    onPrimaryContainer = Color(0xFFD7E3FF),
    secondary = Color(0xFF6FD8A0),
    onSecondary = Color(0xFF00391F),
    background = Color(0xFF0F1318),
    onBackground = Color(0xFFE1E4EA),
    surface = Color(0xFF0F1318),
    onSurface = Color(0xFFE1E4EA),
    surfaceVariant = Color(0xFF1D232B),
    onSurfaceVariant = Color(0xFFA8B1BE),
    outlineVariant = Color(0xFF2E3640),
    error = Color(0xFFFF8A80),
)

/** Gradient karty statusu – jak tło ikony. */
val HeroTop = Color(0xFF1565C0)
val HeroBottom = Color(0xFF0A2E6E)

/** Kolory kategorii prędkości – te same co na mapie trasy. */
val SpeedColors = listOf(
    Color(0xFF9AA0A6), // 0: brak limitu
    Color(0xFF2E9E5B), // 1: w limicie
    Color(0xFFE0B400), // 2: +0–10
    Color(0xFFF08A24), // 3: +10–20
    Color(0xFFE0412B), // 4: +20–30
    Color(0xFF8E1B6D), // 5: >+30
)

@Composable
fun DriveTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
