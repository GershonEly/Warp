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
//
// The whole ladder was lifted about eight points after seeing it on the phone
// with real content in it. It had been near-black — the page was RGB(11,13,16) —
// and near-black is a large part of why the drawer read as a console: every edge
// becomes a hard line and every piece of text sits at maximum contrast against
// it. Claude's sidebar is nearer RGB(38,38,36) and reads as a surface rather
// than as a hole.
//
// The steps between the rungs are unchanged, so depth still works the same way;
// the ladder simply starts higher. The blue cast stays — that was right.
val DarkBackground = Color(0xFF13161B)        // the page — soft dark, blue cast
val DarkSurface = Color(0xFF1A1E24)           // drawer, plain panels
val DarkSurfaceContainer = Color(0xFF21252D)  // cards, the composer
val DarkSurfaceHigh = Color(0xFF292F38)       // raised: sheets, menus
val DarkOnSurface = Color(0xFFE8EAED)
val DarkOnSurfaceVariant = Color(0xFF99A1AC)
val DarkOutline = Color(0xFF39414C)
val DarkPrimaryContainer = Color(0xFF12405F)  // lifted with the rest, so a
                                              // selected row still reads as a
                                              // step up from the new surface

// ── Light ────────────────────────────────────────────────────────────────
//
// Light mode inverts how depth is built, and getting this backwards is what
// made the first light pass look washed out: in the dark theme raised things
// are *lighter* than the page, but in the light theme raised things are
// **white** and the page is the grey one. Cards, the composer and sheets are
// pure white; the background sits a step below them.
val LightBackground = Color(0xFFF4F6F8)       // the page — a light grey
val LightSurface = Color(0xFFFFFFFF)          // drawer, plain panels
val LightSurfaceContainer = Color(0xFFFFFFFF) // cards, the composer
val LightSurfaceHigh = Color(0xFFFFFFFF)      // sheets, menus
val LightSurfaceVariant = Color(0xFFE9ECF1)   // recessed: selected rows, badges
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
val LightHairline = Color(0x1F0B0D10)  // near-black at 12% — white cards need
                                       // a firmer edge than dark ones do

// ── Code panels ──────────────────────────────────────────────────────────

/**
 * The background behind compiler output and anything else the machine wrote.
 *
 * It needs its own token because "recessed" is not a fixed colour: on dark it
 * is a step *below* the card, on light a step below white. Deriving it from
 * `surface` made it vanish the moment light-mode cards became white too.
 */
val DarkCodeSurface = Color(0xFF0F1216)  // a step below the lifted page, so
                                         // machine output still reads as recessed
val LightCodeSurface = Color(0xFFF1F3F7)
