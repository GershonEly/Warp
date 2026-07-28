package dev.ely.warp.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Warp's palette, taken from the desktop Antigravity IDE.
 *
 * Deliberately restrained: one accent colour, tonal surfaces instead of
 * borders, and no gradients. It should read as a professional developer tool.
 */

// ── Light ────────────────────────────────────────────────────────────────
val LightBackground = Color(0xFFFFFFFF)
val LightSurface = Color(0xFFF8F9FA)
val LightSurfaceContainer = Color(0xFFF1F3F4)
val LightOnSurface = Color(0xFF1F1F1F)
val LightOnSurfaceVariant = Color(0xFF444746)
val LightOutline = Color(0xFF747775)
val LightOutlineVariant = Color(0xFFC4C7C5)
val LightPrimaryContainer = Color(0xFFD2E3FC)

// ── Dark ─────────────────────────────────────────────────────────────────
val DarkBackground = Color(0xFF131314)
val DarkSurface = Color(0xFF1E1F20)
val DarkSurfaceContainer = Color(0xFF282A2C)
val DarkOnSurface = Color(0xFFE3E3E3)
val DarkOnSurfaceVariant = Color(0xFFC4C7C5)
val DarkOutline = Color(0xFF8E918F)
val DarkOutlineVariant = Color(0xFF444746)
val DarkPrimaryContainer = Color(0xFF004A77)

// ── Shared accents ───────────────────────────────────────────────────────

/**
 * Warp's blue, sampled from the mark itself.
 *
 * This deliberately replaces #1A73E8, which is Google's own brand blue — part
 * of why the app read as a Google app rather than as its own product. Taking
 * the colour from the logo instead means the icon and the interface are the
 * same thing.
 */
val WarpPrimary = Color(0xFF1098E0)

/** The lighter cyan from the mark, for highlights and the thinking state. */
val WarpAccent = Color(0xFF18B8F0)

val WarpError = Color(0xFFD93025)
val WarpSuccess = Color(0xFF1E8E3E)
val WarpWarning = Color(0xFFF9AB00)
