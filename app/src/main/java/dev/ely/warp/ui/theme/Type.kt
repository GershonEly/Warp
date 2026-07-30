// Variable-font settings are still an experimental API. Opting in knowingly:
// one file per family instead of one per weight, and the fallback if it ever
// changes is simply declaring the weights separately.
@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package dev.ely.warp.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.ely.warp.R

/**
 * Warp's type.
 *
 * **Rubik, everywhere.** Not Roboto — Roboto is the Android system font, so an
 * app set in it inherits the look of stock Android and cannot read as its own
 * product, which was the largest single reason the first design pass still
 * looked dated.
 *
 * Rubik replaced Geist across the whole interface rather than only in the
 * headings. The two-face version was tried first and it was the wrong call in
 * practice: with Rubik in two slots out of fifteen, exactly two headings in the
 * app changed and everything else — every row, label, button and paragraph —
 * stayed Geist. The new face was invisible, so the app had a new font and the
 * same voice.
 *
 * **Hierarchy comes from weight and colour, not from swapping families.** That
 * is the whole reason the entire 300–900 axis is loaded: a greeting at 700, a
 * heading at 600, a label at 500, a paragraph at 400, and a quiet caption at
 * 300 are one voice speaking at five volumes. Two families would have been two
 * voices, which is what makes an interface feel assembled rather than designed.
 *
 * Hebrew is why Rubik beat the alternatives. Warp is forced left-to-right today
 * because every label is English, and real right-to-left support means
 * translating the app rather than mirroring it. Rubik was drawn with Hebrew; the
 * faces considered beside it were Latin-only, so any of them would have meant
 * choosing again on the day Warp is translated.
 *
 * The two details doing most of the work here are **negative tracking on large
 * text** and a **generous body line height**. Default tracking at large sizes is
 * exactly what makes headings look untouched, and cramped body text is what
 * makes an interface feel like a form.
 */

private fun weight(w: Int) = FontVariation.Settings(FontVariation.weight(w))

/**
 * The interface face. One font file, five weights.
 *
 * **Each weight is its own XML resource, and that is not a style choice.** The
 * obvious spelling — five Compose `Font` entries all pointing at `rubik.ttf`
 * with different `variationSettings` — silently does not work: Android caches a
 * Typeface by resource id, so all five resolve to whichever instance was built
 * first and the entire app renders at 400.
 *
 * It failed quietly, which is the dangerous part. The font *was* loading, the
 * theme *was* wired, and nothing logged a warning — the app simply had a new
 * typeface and one weight, so changing the design changed nothing anyone could
 * see. It was caught by forcing weight 900 app-wide and finding the screen
 * unchanged.
 *
 * Each `rubik_wNNN.xml` names the same `rubik.ttf` and applies its own
 * `fontVariationSettings`, so the weight is cut by the platform and each weight
 * gets its own cache entry. Five small XML files, no duplicated font data, and
 * no experimental API.
 */
val Rubik = FontFamily(
    // Light exists for one job: a placeholder should invite, not assert. Without
    // this entry, asking for Light silently resolves to the nearest weight
    // present — which is Normal, so the request would have changed nothing.
    Font(R.font.rubik_w300, FontWeight.Light),
    Font(R.font.rubik_w400, FontWeight.Normal),
    Font(R.font.rubik_w500, FontWeight.Medium),
    Font(R.font.rubik_w600, FontWeight.SemiBold),
    Font(R.font.rubik_w700, FontWeight.Bold),
    Font(R.font.rubik_w800, FontWeight.ExtraBold),
)

/**
 * Code face — the one place a second family earns its keep.
 *
 * Compiler output, file paths and JSON are not prose and should not be set as
 * prose: a monospace column is how you see that a stack trace is aligned and
 * that a path has not been wrapped. Geist Sans is gone from the app, but its
 * mono stays, which is why the Geist licence still travels with the APK.
 */
val GeistMono = FontFamily(
    Font(R.font.geist_mono, FontWeight.Normal, variationSettings = weight(400)),
    Font(R.font.geist_mono, FontWeight.Medium, variationSettings = weight(500)),
)

// ── the scale ────────────────────────────────────────────────────────────
//
// One face, five volumes. The weight climbs with the size on purpose: large text
// carries its own emphasis through sheer area, so a heading that is *also* the
// heaviest thing on screen is what separates a hierarchy you feel from one you
// have to work out.
//
//   Display   32  Bold        700   the one big line on a screen
//   Headline  24  SemiBold    600   screen and section titles
//   Title     17  Medium      500   card titles, row emphasis
//   Body      16  Regular     400   everything you read
//   Label     13  Medium      500   captions, chips, states
//
// Colour does the rest, and the theme owns it: `onSurface` for what matters,
// `onSurfaceVariant` for what supports it, `primary` for what is active. That
// division is why this file has five styles and not twenty.

private val Display = TextStyle(
    fontFamily = Rubik,
    fontWeight = FontWeight.Bold,
    fontSize = 32.sp,
    lineHeight = 38.sp,
    // Less negative than a grotesque wants. Rubik's rounded shapes already sit
    // close together, and pulling them tighter only muddies the counters.
    letterSpacing = (-0.015).em,
)

private val Headline = TextStyle(
    fontFamily = Rubik,
    fontWeight = FontWeight.SemiBold,
    fontSize = 24.sp,
    lineHeight = 31.sp,
    letterSpacing = (-0.01).em,
)

private val Title = TextStyle(
    fontFamily = Rubik,
    fontWeight = FontWeight.Medium,
    fontSize = 17.sp,
    lineHeight = 23.sp,
    letterSpacing = (-0.005).em,
)

private val Body = TextStyle(
    fontFamily = Rubik,
    fontWeight = FontWeight.Normal,
    fontSize = 16.sp,
    // 1.55 — the difference between text that reads and text that is merely
    // legible. Most interfaces sit near 1.2 and feel tight without anyone
    // being able to say why.
    lineHeight = 25.sp,
)

private val Label = TextStyle(
    fontFamily = Rubik,
    fontWeight = FontWeight.Medium,
    fontSize = 13.sp,
    lineHeight = 17.sp,
    // Positive tracking at small sizes, and no negative tracking anywhere below
    // Title: tightening is a fix for large text only. Applied to a 12sp label it
    // is how small type turns into a smudge.
    letterSpacing = 0.01.em,
)

/** Logs, file paths, JSON — anything the machine wrote. */
val WarpMono = TextStyle(
    fontFamily = GeistMono,
    fontWeight = FontWeight.Normal,
    fontSize = 13.sp,
    lineHeight = 20.sp,
)

/**
 * Material's slots, filled from the six above.
 *
 * Several slots share a style on purpose: the scale is the design, and the slot
 * names are only how Material components ask for it.
 */
val WarpTypography = Typography(
    displayLarge = Display,
    displayMedium = Display,
    displaySmall = Display,

    headlineLarge = Headline,
    headlineMedium = Headline,
    headlineSmall = Headline.copy(fontSize = 21.sp, lineHeight = 27.sp),

    titleLarge = Title.copy(fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = Title,
    titleSmall = Title.copy(fontSize = 15.sp, lineHeight = 20.sp),

    bodyLarge = Body,
    bodyMedium = Body.copy(fontSize = 15.sp, lineHeight = 23.sp),
    bodySmall = Body.copy(fontSize = 13.sp, lineHeight = 19.sp),

    labelLarge = Label.copy(fontSize = 14.sp, lineHeight = 18.sp),
    labelMedium = Label,
    labelSmall = Label.copy(fontSize = 12.sp, lineHeight = 15.sp),
)
