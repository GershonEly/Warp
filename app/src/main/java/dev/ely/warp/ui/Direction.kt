package dev.ely.warp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection

/**
 * Forces left-to-right layout for its content.
 *
 * Warp's UI should mirror properly on a right-to-left phone — Hebrew, Arabic —
 * but code must not. Compiler output, file paths, JSON and timings all read
 * left to right; mirroring them moves punctuation to the wrong end and makes
 * paths unreadable.
 *
 * Wrap anything that is code or machine output in this.
 */
@Composable
fun Ltr(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        content()
    }
}

/**
 * Which way a piece of writing runs, decided by the writing itself.
 *
 * The rule every messaging app uses, and the reason they all feel right: the
 * **first strong directional character** sets the direction of the whole
 * paragraph. A message that opens in Hebrew is a Hebrew message even if it
 * quotes an English class name in the middle; one that opens in English stays
 * English even if a Hebrew word appears later.
 *
 * "Strong" is the important word. Digits, punctuation, spaces and emoji are
 * neutral — they take the direction of what is around them — so `"123 שלום"`
 * is correctly Hebrew and `"שלום"` prefixed with a smiley still is. Scanning
 * for the first strong character rather than the first character is what stops
 * a leading bracket or number from deciding the layout of a sentence.
 *
 * Unicode bidi does the rest inside the line. This only chooses which edge the
 * line starts from, which is the part bidi cannot know.
 */
fun startsRightToLeft(text: String): Boolean {
    for (ch in text) {
        when (Character.getDirectionality(ch)) {
            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return false

            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
            -> return true

            // Neutral. Keep looking — this is the whole point.
            else -> continue
        }
    }
    // Nothing strong in it at all: digits, punctuation, or empty. Left to right,
    // which is also what an empty composer should show.
    return false
}

/**
 * The same rule, as a text style: the paragraph starts from whichever edge its
 * own first strong character calls for.
 *
 * Kept beside [startsRightToLeft] because they are one decision in two forms —
 * this one for text on its own, that one for a row the text sits in.
 */
fun TextStyle.byContent(): TextStyle = copy(
    textDirection = TextDirection.Content,
    textAlign = TextAlign.Start,
)

/**
 * Lays its content out in the direction the given text runs.
 *
 * For rows that have a start and an end — a bullet and its line, a label and its
 * value — where the text flipping but the furniture not flipping is worse than
 * neither flipping. Text alone needs only `TextDirection.Content`; this is for
 * when something sits *beside* the text.
 */
@Composable
fun ByContent(text: String, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalLayoutDirection provides
            if (startsRightToLeft(text)) LayoutDirection.Rtl else LayoutDirection.Ltr,
    ) {
        content()
    }
}
