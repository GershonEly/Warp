package dev.ely.warp.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import dev.ely.warp.R
import dev.ely.warp.ui.theme.LocalAnimationsEnabled

/**
 * Warp's mark.
 *
 * The same figure everywhere — app icon, chat, empty state — so the app has one
 * identity rather than a different symbol per screen.
 *
 * Two drawings, one shape: the full thirty-edge icosahedron above roughly 20 dp,
 * and a reduced twelve-edge version below it. Thirty edges inside 16 dp turn to
 * mush, and a mark that cannot be read is not a mark.
 */
@Composable
fun WarpMark(
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    Image(
        painter = markPainter(size),
        contentDescription = null,
        colorFilter = ColorFilter.tint(tint),
        modifier = modifier.size(size),
    )
}

/**
 * The mark, turning — shown while the model is working.
 *
 * A slow, even rotation with no easing: it has no beginning or end, so it never
 * looks like it is starting over. The whole figure spins rather than a separate
 * spinner appearing, which is what ties "Warp is thinking" to Warp itself.
 *
 * Rotation happens in code on a vector, so it stays sharp at any size, costs
 * almost nothing, and recolours with the theme. A looping image file could do
 * none of that.
 */
@Composable
fun ThinkingMark(
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    // The transition is only created when it will actually be used. Building it
    // and then ignoring its value — which is what happened before — leaves an
    // animation clock running forever, invalidating this layer every frame to
    // draw the identical picture. Wasted frames matter most on exactly the
    // devices where someone turns animations off.
    val angle = if (LocalAnimationsEnabled.current) {
        val transition = rememberInfiniteTransition(label = "thinking")
        val a by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                // Linear on purpose: any easing would make one point of the turn
                // read as the "start", and this loop has none.
                animation = tween(ROTATION_MILLIS, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "angle",
        )
        a
    } else {
        0f
    }

    Image(
        painter = markPainter(size),
        contentDescription = "Thinking",
        colorFilter = ColorFilter.tint(tint),
        modifier = modifier
            .size(size)
            // Honours the system's reduce-animations setting: the mark still
            // appears, it simply holds still.
            .rotate(angle),
    )
}

/** Below this the full wireframe stops being legible. */
private val SIMPLIFY_BELOW = 20.dp

/** One full turn. Slow enough to read as deliberate rather than as loading. */
private const val ROTATION_MILLIS = 3600

@Composable
private fun markPainter(size: Dp): Painter = painterResource(
    if (size < SIMPLIFY_BELOW) R.drawable.ic_warp_mark_small else R.drawable.ic_warp_mark
)
