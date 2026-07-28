package dev.ely.warp.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Warp's palette.
 *
 * **Every neutral is tinted blue.** Pure grey is the single most common reason a
 * dark interface looks cheap — it reads as absence of colour rather than as a
 * decision, and it leaves the accent stranded in a different world. A few points
 * of blue in every surface makes the accent belong.
 *
 * Restrained on purpose: one accent, tonal surfaces instead of borders, no
 * gradients except the one field behind the app and the mark itself.
 */

// ── Dark ─────────────────────────────────────────────────────────────────
val DarkBackground = Color(0xFF0B0D10)        // near-black, blue cast
val DarkSurface = Color(0xFF12151A)
val DarkSurfaceContainer = Color(0xFF181C22)  // cards, the composer
val DarkSurfaceHigh = Color(0xFF1F242C)       // raised: sheets, menus
val DarkOnSurface = Color(0xFFE8EAED)
val DarkOnSurfaceVariant = Color(0xFF99A1AC)
val DarkOutline = Color(0xFF39414C)
val DarkPrimaryContainer = Color(0xFF0E3350)

// ── Light ────────────────────────────────────────────────────────────────
val LightBackground = Color(0xFFFCFCFD)
val LightSurface = Color(0xFFF5F6F8)
val LightSurfaceContainer = Color(0xFFEEF0F3)
val LightSurfaceHigh = Color(0xFFFFFFFF)
val LightOnSurface = Color(0xFF16181C)
val LightOnSurfaceVariant = Color(0xFF5A626C)
val LightOutline = Color(0xFFC7CCD3)
val LightPrimaryContainer = Color(0xFFD6EBFA)

// ── Accents ──────────────────────────────────────────────────────────────

/**
 * Warp's blue, sampled from the mark itself.
 *
 * Replaces #1A73E8 — Google's own brand blue, and part of why the app read as a
 * Google app rather than as its own product. Taking the colour from the logo
 * means the icon and the interface are the same thing.
 *
 * **Rationed.** It appears on the mark, the send button when armed, the selected
 * navigation item, and a focused field. Nowhere else. An accent used everywhere
 * stops being an accent.
 */
val WarpPrimary = Color(0xFF1098E0)

/** The lighter cyan from the mark, for highlights and the thinking state. */
val WarpAccent = Color(0xFF18B8F0)

val WarpError = Color(0xFFE5484D)
val WarpSuccess = Color(0xFF30A46C)
val WarpWarning = Color(0xFFE5A00D)

// ── Hairlines ────────────────────────────────────────────────────────────

/**
 * Depth comes from a hairline and a small step in lightness, not from shadows.
 * Shadows are invisible on a dark background and look cheap on a light one.
 */
val DarkHairline = Color(0x14FFFFFF)   // white at 8%
val LightHairline = Color(0x140B0D10)  // near-black at 8%
