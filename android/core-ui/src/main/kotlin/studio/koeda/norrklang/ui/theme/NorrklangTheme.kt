package studio.koeda.norrklang.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

/*
 * Near-black surfaces, one "aurora" accent, high contrast for car screens.
 * Always dark, in day and night mode alike — standard for automotive media
 * apps, where a bright surface would glare at the driver. The window-level
 * themes in both app modules match this.
 */

private val AuroraTeal = Color(0xFF4FD8C4)
private val AuroraTealDim = Color(0xFF2E8A7E)
private val NightBackground = Color(0xFF0B0D10)
private val NightSurface = Color(0xFF14171C)
private val NightSurfaceHigh = Color(0xFF1D2127)
private val TextPrimary = Color(0xFFF2F4F7)
private val TextSecondary = Color(0xFFA8B0BB)
private val ErrorRed = Color(0xFFFF6B6B)

private val DarkColors = darkColorScheme(
    primary = AuroraTeal,
    onPrimary = Color(0xFF00201A),
    primaryContainer = AuroraTealDim,
    onPrimaryContainer = Color(0xFFE0FFF8),
    secondary = TextSecondary,
    onSecondary = NightBackground,
    background = NightBackground,
    onBackground = TextPrimary,
    surface = NightSurface,
    onSurface = TextPrimary,
    surfaceVariant = NightSurfaceHigh,
    onSurfaceVariant = TextSecondary,
    error = ErrorRed,
    onError = Color(0xFF330000),
    outline = Color(0xFF3A404A),
)

/**
 * Color scheme plus the window-size step (see Adaptive.kt): wide windows get
 * enlarged typography and roomier [LocalFormDimens].
 */
@Composable
fun NorrklangTheme(content: @Composable () -> Unit) {
    val expanded = isExpandedWindow()
    CompositionLocalProvider(
        LocalFormDimens provides if (expanded) ExpandedFormDimens else DefaultFormDimens,
    ) {
        MaterialTheme(
            colorScheme = DarkColors,
            typography = if (expanded) ExpandedTypography else Typography(),
            content = content,
        )
    }
}
