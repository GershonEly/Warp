package dev.ely.warp.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Space and shape, as scales rather than as numbers typed per component.
 *
 * Most interfaces look approximate because their spacing is approximate — a
 * 14 here, an 18 there. Nothing in Warp sits at a value that is not on these
 * scales.
 */
object WarpSpace {
    /** Everything is a multiple of this. */
    val unit = 4.dp

    /** Screen edges. Generous space is the cheapest way to look considered. */
    val screen = 24.dp

    /** Between one message and the next. */
    val message = 24.dp

    /** Inside a card. */
    val card = 16.dp

    /** Between one section and another. */
    val section = 32.dp

    val tiny = 4.dp
    val small = 8.dp
    val medium = 12.dp
    val large = 16.dp
}

/**
 * Corner radii.
 *
 * Larger than Material's defaults throughout. Small radii are a signature of
 * the previous generation of Android; current design treats shape as something
 * expressive rather than as a uniform 4 dp everywhere.
 */
object WarpRadius {
    /** Chips and small controls. */
    val small = 12.dp

    /** Cards, tool cards, provider cards. */
    val medium = 20.dp

    /** Sheets and dialogs. */
    val large = 28.dp
}

val WarpShapes = Shapes(
    extraSmall = RoundedCornerShape(WarpRadius.small),
    small = RoundedCornerShape(WarpRadius.small),
    medium = RoundedCornerShape(WarpRadius.medium),
    large = RoundedCornerShape(WarpRadius.large),
    extraLarge = RoundedCornerShape(WarpRadius.large),
)

/** A hairline is 1 dp everywhere; depth is its colour, not its width. */
val HairlineWidth = 1.dp
