package com.audiopro.djmrec.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

// Neurofunk design system uses Orbitron (display/readouts) + Share Tech Mono (everything else).
// Both are Google Fonts; since bundling the actual font files isn't practical here, the closest
// bundled equivalents stand in: system sans-serif at Black weight reads as a geometric display
// face at these tiny tracked sizes, and the system monospace carries the "terminal" body feel
// Share Tech Mono is going for. Swap in real vendored .ttf files under res/font/ later for exact
// brand fidelity if it matters.
val NfDisplayFontFamily = FontFamily.SansSerif
val NfMonoFontFamily = FontFamily.Monospace

/** "SYNTH PRESETS", "PATTERNS" -- section headers. Apply `.uppercase()` to the string. */
val NfSectionTitle = TextStyle(
    fontFamily = NfMonoFontFamily,
    fontSize = 10.sp,
    letterSpacing = 0.25.em,
    color = TextDim
)

/** Knob/control labels, smaller still. Apply `.uppercase()` to the string. */
val NfLabel = TextStyle(
    fontFamily = NfMonoFontFamily,
    fontSize = 8.sp,
    letterSpacing = 0.22.em,
    color = TextDim
)

/** Knob/meter readouts, e.g. "380Hz", "+3.0 dB". */
val NfValue = TextStyle(
    fontFamily = NfMonoFontFamily,
    fontSize = 10.sp,
    letterSpacing = 0.1.em,
    color = AccentCyan
)

/** Big numeric displays -- elapsed timer, dB readout. */
val NfReadout = TextStyle(
    fontFamily = NfDisplayFontFamily,
    fontWeight = FontWeight.Black,
    fontSize = 26.sp,
    color = AccentLime
)

val DjmRecTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = NfDisplayFontFamily,
        fontWeight = FontWeight.Black,
        fontSize = 22.sp,
        letterSpacing = 0.05.em
    ),
    titleMedium = TextStyle(
        fontFamily = NfMonoFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        letterSpacing = 0.08.em
    ),
    titleSmall = TextStyle(
        fontFamily = NfMonoFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        letterSpacing = 0.08.em
    ),
    bodyMedium = TextStyle(
        fontFamily = NfMonoFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        color = TextSecondary
    ),
    bodySmall = TextStyle(
        fontFamily = NfMonoFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        color = TextSecondary
    ),
    labelSmall = TextStyle(
        fontFamily = NfMonoFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 9.sp,
        letterSpacing = 0.18.em
    ),
    labelMedium = TextStyle(
        fontFamily = NfMonoFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        letterSpacing = 0.15.em
    )
)
