// Variable-font settings are still an experimental API. Opting in knowingly:
// one file per family instead of one per weight is worth it, and the fallback
// if it ever changes is simply declaring the weights separately.
@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package dev.ely.warp.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.ely.warp.R

/**
 * Warp's type, as specified in the plan.
 *
 * Both faces are variable fonts, so every weight comes from one file rather
 * than one file per weight — the whole family costs a single download.
 *
 * Roboto Flex stands in for Google Sans, which is licensed for Google's own
 * apps only. It is near-identical and keeps the repository legally clean.
 */

private fun weight(w: Int) = FontVariation.Settings(FontVariation.weight(w))

/** UI face. */
val RobotoFlex = FontFamily(
    Font(R.font.roboto_flex, FontWeight.Normal, variationSettings = weight(400)),
    Font(R.font.roboto_flex, FontWeight.Medium, variationSettings = weight(500)),
    Font(R.font.roboto_flex, FontWeight.SemiBold, variationSettings = weight(600)),
    Font(R.font.roboto_flex, FontWeight.Bold, variationSettings = weight(700)),
)

/** Code face — the real one from the desktop IDE, and openly licensed. */
val GoogleSansCode = FontFamily(
    Font(R.font.google_sans_code, FontWeight.Normal, variationSettings = weight(400)),
    Font(R.font.google_sans_code, FontWeight.Medium, variationSettings = weight(500)),
)

val WarpTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = RobotoFlex,
        fontWeight = FontWeight.Normal,
        fontSize = 34.sp,
        lineHeight = 42.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = RobotoFlex,
        fontWeight = FontWeight.Normal,
        fontSize = 26.sp,
        lineHeight = 32.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = RobotoFlex,
        fontWeight = FontWeight.Normal,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = RobotoFlex,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = RobotoFlex,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = RobotoFlex,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 23.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = RobotoFlex,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = RobotoFlex,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = RobotoFlex,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = RobotoFlex,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = RobotoFlex,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
    ),
)

/** Logs, file paths, JSON, and anything the build engine prints. */
val WarpMono = TextStyle(
    fontFamily = GoogleSansCode,
    fontSize = 12.sp,
    lineHeight = 18.sp,
)
