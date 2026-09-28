package com.audiopro.djmrec.ui.theme

import androidx.compose.ui.graphics.Color

// Neurofunk design system tokens (see the `neurofunk-design` skill) -- near-black,
// blue-shifted surfaces with a neon cyan/orange/lime accent trio. The semantic names used by
// upstream screens (AccentGreen/Amber/Red, StatusLive, OutlineSubtle, ...) are kept and mapped
// onto this palette, so every screen picks up the brand without touching its call sites.

val BackgroundDark = Color(0xFF070A0F)       // --bg
val SurfaceDark = Color(0xFF0D1219)          // --panel
val SurfaceVariantDark = Color(0xFF0A1018)   // --panel2 (nested sections go darker, not lighter)
val BorderHairline = Color(0xFF1A2535)       // --border

val AccentCyan = Color(0xFF00E5FF)    // --accent: primary / info
val AccentOrange = Color(0xFFFF3D00)  // --accent2: danger / stop / kick
val AccentLime = Color(0xFF76FF03)    // --accent3: play / success / bass
val AccentMagenta = Color(0xFFE040FB) // --fx-color
val AccentYellow = Color(0xFFFFD600)  // --chain-color

// Semantic roles. Green = armed / ready / connected. Amber = warning / attention.
// Red = on air & recording (and destructive) -- the brand's "danger" IS this orange.
val AccentGreen = AccentLime
val AccentAmber = Color(0xFFFFAB00)   // --snare-color
val AccentRed = AccentOrange

/** On-air / recording status color. */
val StatusLive = AccentRed

/** Hairlines, tile borders and dividers. */
val OutlineSubtle = BorderHairline

val TextPrimary = Color(0xFFDCE8F5)   // bright but blue-shifted, never pure white
val TextSecondary = Color(0xFF8BA3C1) // --text
val TextDim = Color(0xFF334155)       // --dim: tiny tracked labels

// Waveform spectrum colors (data visualization only, not general accents).
val WaveformLow = Color(0xFFFF315E)
val WaveformMid = Color(0xFF29F19C)
val WaveformHigh = Color(0xFF25A7FF)

// CDJ-3000 "3Band" waveform layers: blue body (low) -> amber (mid) -> white (high).
val WaveformCdjLow = Color(0xFF2E6BFF)
val WaveformCdjMid = Color(0xFFF0A93C)
val WaveformCdjHi = Color(0xFFFFFFFF)

// VU meter gradient stops (green -> amber -> red as level approaches 0 dBFS).
val MeterGreen = AccentGreen
val MeterAmber = AccentAmber
val MeterRed = AccentRed
