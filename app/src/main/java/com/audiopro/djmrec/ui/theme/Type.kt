package com.audiopro.djmrec.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

// Neurofunk design system uses Orbitron (display/readouts) + Share Tech Mono (everything else).
// The closest bundled equivalents stand in: system sans-serif at Black weight reads as a
// geometric display face, and the system monospace carries the "terminal" body feel.
val NfDisplayFontFamily = FontFamily.SansSerif
val NfMonoFontFamily = FontFamily.Monospace

/** Section headers. Apply `.uppercase()` to the string. */
val NfSectionTitle = TextStyle(
    fontFamily = NfMonoFontFamily,
    fontSize = 10.sp,
    letterSpacing = 0.25.em,
    color = TextDim
)

/** Control labels, smaller still. Apply `.uppercase()` to the string. */
val NfLabel = TextStyle(
    fontFamily = NfMonoFontFamily,
    fontSize = 8.sp,
    letterSpacing = 0.22.em,
    color = TextDim
)

/** Meter readouts, e.g. "+3.0 dB". */
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

/**
 * Full Material 3 scale in the brand's two faces: display/headline in the geometric display
 * face, everything else monospace with tracking. Body and label sizes stay at or above the
 * upstream legibility floor (~12sp) so the busier v0.45 screens remain readable.
 */
val DjmRecTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = NfDisplayFontFamily,
        fontWeight = FontWeight.Black,
        fontSize = 36.sp,
        letterSpacing = 0.04.em
    ),
    headlineLarge = TextStyle(
        fontFamily = NfDisplayFontFamily,
        fontWeight = FontWeight.Black,
        fontSize = 30.sp,
        letterSpacing = 0.05.em
    ),
    headlineSmall = TextStyle(
        fontFamily = NfDisplayFontFamily,
        fontWeight = FontWeight.Black,
        fontSize = 22.sp,
        letterSpacing = 0.05.em
    ),
    titleLarge = TextStyle(
        fontFamily = NfDisplayFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        letterSpacing = 0.08.em
    ),
    titleMedium = TextStyle(
        fontFamily = NfMonoFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp,
        letterSpacing = 0.06.em
    ),
    titleSmall = TextStyle(
        fontFamily = NfMonoFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        letterSpacing = 0.06.em
    ),
    bodyLarge = TextStyle(
        fontFamily = NfMonoFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = NfMonoFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp
    ),
    bodySmall = TextStyle(
        fontFamily = NfMonoFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp
    ),
    labelLarge = TextStyle(
        fontFamily = NfMonoFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        letterSpacing = 0.12.em
    ),
    labelMedium = TextStyle(
        fontFamily = NfMonoFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 0.12.em
    ),
    labelSmall = TextStyle(
        fontFamily = NfMonoFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.14.em
    )
)
