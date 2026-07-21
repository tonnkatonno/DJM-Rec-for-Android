package com.audiopro.djmrec.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp

private val DjmRecColorScheme = darkColorScheme(
    primary = AccentLime,
    onPrimary = BackgroundDark,
    secondary = AccentAmber,
    onSecondary = BackgroundDark,
    tertiary = AccentCyan,
    onTertiary = BackgroundDark,
    background = BackgroundDark,
    surface = SurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    outline = BorderHairline,
    error = AccentOrange,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary
)

// Material3 defaults to a full/stadium shape for buttons and largish radii for large/extraLarge
// (dialogs, bottom sheets) -- neither fits the brand's "sharp, mechanical, never bigger than
// 5dp" rule. Overriding all five tokens means every default-shaped component (Button, Card,
// TextField, Dialog, ...) inherits brand-correct radii even where we haven't set an explicit
// `shape =` at the call site.
private val DjmRecShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(3.dp),
    medium = RoundedCornerShape(4.dp),
    large = RoundedCornerShape(4.dp),
    extraLarge = RoundedCornerShape(5.dp)
)

/** A pro-audio, permanently-dark theme — matches the visual language of hardware DJ gear,
 *  restyled per the `neurofunk-design` skill (near-black blue-shifted surfaces, neon
 *  cyan/orange/lime accents, hairline borders, tiny tracked uppercase labels). */
@Composable
fun DjmRecTheme(content: @Composable () -> Unit) {
    // Intentionally ignore system light/dark mode: a VU meter and transport UI needs a
    // consistent, low-glare dark surface regardless of device theme, same as any DAW.
    MaterialTheme(
        colorScheme = DjmRecColorScheme,
        typography = DjmRecTypography,
        shapes = DjmRecShapes
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            content()
            // Always-on CRT scan-line shimmer, per the brand's scan-line overlay token --
            // barely-there, never intercepts touches (no pointer input attached to it).
            ScanlineOverlay(modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun ScanlineOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val period = 4.dp.toPx()
        val lineColor = AccentCyan.copy(alpha = 0.03f)
        var y = 0f
        while (y < size.height) {
            drawLine(
                color = lineColor,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 2.dp.toPx()
            )
            y += period
        }
    }
}
