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
 * Geist, not Roboto. Roboto is the Android system font, so an app set in it
 * inherits the look of stock Android and cannot read as its own product — which
 * was the single largest reason the first design pass still looked dated.
 *
 * Six styles, not twenty. Hierarchy comes from size and colour, never from
 * bolding inside a paragraph.
 *
 * The two details doing most of the work here are **negative tracking on large
 * text** and a **generous body line height**. Default tracking at large sizes is
 * exactly what makes headings look untouched, and cramped body text is what
 * makes an interface feel like a form.
 */

private fun weight(w: Int) = FontVariation.Settings(FontVariation.weight(w))

/** UI face — drawn for developer products, openly licensed. */
val Geist = FontFamily(
    Font(R.font.geist, FontWeight.Normal, variationSettings = weight(400)),
    Font(R.font.geist, FontWeight.Medium, variationSettings = weight(500)),
    Font(R.font.geist, FontWeight.SemiBold, variationSettings = weight(600)),
)

/** Code face — same family, so logs and prose belong to one voice. */
val GeistMono = FontFamily(
    Font(R.font.geist_mono, FontWeight.Normal, variationSettings = weight(400)),
    Font(R.font.geist_mono, FontWeight.Medium, variationSettings = weight(500)),
)

// ── the six ──────────────────────────────────────────────────────────────

private val Display = TextStyle(
    fontFamily = Geist,
    fontWeight = FontWeight.Medium,
    fontSize = 32.sp,
    lineHeight = 37.sp,
    letterSpacing = (-0.02).em,
)

private val Headline = TextStyle(
    fontFamily = Geist,
    fontWeight = FontWeight.Medium,
    fontSize = 24.sp,
    lineHeight = 30.sp,
    letterSpacing = (-0.015).em,
)

private val Title = TextStyle(
    fontFamily = Geist,
    fontWeight = FontWeight.Medium,
    fontSize = 17.sp,
    lineHeight = 22.sp,
    letterSpacing = (-0.01).em,
)

private val Body = TextStyle(
    fontFamily = Geist,
    fontWeight = FontWeight.Normal,
    fontSize = 16.sp,
    // 1.55 — the difference between text that reads and text that is merely
    // legible. Most interfaces sit near 1.2 and feel tight without anyone
    // being able to say why.
    lineHeight = 25.sp,
)

private val Label = TextStyle(
    fontFamily = Geist,
    fontWeight = FontWeight.Medium,
    fontSize = 13.sp,
    lineHeight = 16.sp,
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
