package com.kybercypher.marauder.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp

/**
 * Monospace throughout, deliberately. Everything this app shows is aligned
 * columns of hex, MACs, channels and signal levels; a proportional face breaks
 * the alignment that makes those readable at a glance.
 *
 * FontFamily.Monospace maps to the platform mono face, which avoids shipping a
 * font binary. If a bundled face is wanted later, JetBrains Mono is the natural
 * pick for its slashed zero and tabular figures.
 */
private val Mono = FontFamily.Monospace

val MarauderTypography = Typography(
    displaySmall = TextStyle(fontFamily = Mono, fontSize = 28.sp, fontWeight = FontWeight.Bold),
    titleLarge   = TextStyle(fontFamily = Mono, fontSize = 20.sp, fontWeight = FontWeight.Bold),
    titleMedium  = TextStyle(fontFamily = Mono, fontSize = 16.sp, fontWeight = FontWeight.Bold),
    bodyLarge    = TextStyle(fontFamily = Mono, fontSize = 15.sp),
    bodyMedium   = TextStyle(fontFamily = Mono, fontSize = 13.sp),
    bodySmall    = TextStyle(fontFamily = Mono, fontSize = 11.sp),
    labelSmall   = TextStyle(fontFamily = Mono, fontSize = 10.sp, textAlign = TextAlign.Start),
)

private val Scheme = darkColorScheme(
    primary = Palette.Violet,
    onPrimary = Palette.White,
    secondary = Palette.Green,
    onSecondary = Palette.Ground,
    background = Palette.Ground,
    onBackground = Palette.Green,
    surface = Palette.Ground,
    onSurface = Palette.Green,
    surfaceVariant = Palette.VioletDim,
    onSurfaceVariant = Palette.Dim,
    // NOTE: error is ORANGE, not red. Red is reserved for "the radio is
    // transmitting" and nothing else, inherited from the firmware's own
    // semantic grammar. If the UI shows red, the board is emitting.
    error = Palette.Orange,
    onError = Palette.Ground,
    outline = Palette.VioletDim,
)

/** Always dark. There is no light variant — the palette is the point. */
@Composable
fun MarauderTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = MarauderTypography, content = content)
}
