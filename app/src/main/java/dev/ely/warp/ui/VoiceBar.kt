package dev.ely.warp.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ely.warp.ui.theme.LocalAnimationsEnabled
import dev.ely.warp.ui.theme.WarpMotion
import dev.ely.warp.ui.theme.WarpRadius
import dev.ely.warp.ui.theme.WarpSpace
import dev.ely.warp.ui.theme.warpTween
import kotlin.math.abs
import kotlin.math.sin

/** How far up the finger must travel before releasing throws the recording away. */
val CANCEL_DISTANCE = 72.dp

/**
 * The hint that teaches the gesture.
 *
 * **A gesture nobody can see is a feature nobody has.** Cancel is a slide up and
 * nothing else, so the screen has to say so — and it says it by moving: a chevron
 * rising a few dp and fading out, on a loop. The movement *is* the instruction,
 * which is why this is not simply a line of text.
 *
 * It arrives a moment after recording starts rather than immediately. Help for
 * someone who paused, rather than clutter for someone who did not.
 *
 * @param progress 0..1, how far towards cancelling the finger has travelled.
 */
@Composable
fun CancelHint(progress: Float, modifier: Modifier = Modifier) {
    val committed = progress >= 1f

    // Rises and fades on its own until a finger is involved, then stops looping
    // and answers the drag instead — two motions at once would be noise.
    val loop = if (LocalAnimationsEnabled.current && progress <= 0f) {
        val t = rememberInfiniteTransition(label = "cancelHint")
        val phase by t.animateFloat(
            initialValue = 0f,
            targetValue = (2 * Math.PI).toFloat(),
            animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing)),
            label = "cancelHintPhase",
        )
        sin(phase)
    } else {
        0f
    }

    val tint by animateFloatAsState(
        targetValue = if (committed) 1f else 0f,
        animationSpec = warpTween(WarpMotion.QUICK),
        label = "cancelTint",
    )
    val color = lerpColor(
        MaterialTheme.colorScheme.onSurfaceVariant,
        MaterialTheme.colorScheme.error,
        tint,
    )

    Column(
        modifier = modifier.graphicsLayer {
            // Follows the finger, at a fraction of the distance — moving with it
            // one-for-one would run the hint off the top of the screen.
            translationY = -progress * 28.dp.toPx() + loop * 4.dp.toPx()
            alpha = 0.55f + 0.45f * progress + loop * 0.12f
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Outlined.KeyboardArrowUp,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = color,
        )
        Text(
            if (committed) "Release to cancel" else "Slide up to cancel",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = color,
        )
    }
}

/**
 * The composer while it is listening.
 *
 * Cancel is not here — it is the slide. What is here is the thing that says it is
 * working, and the one button that ends it.
 *
 * @param level 0..1 from the microphone.
 * @param progress 0..1 towards cancelling; the bars recede as it rises, so the
 *   recording is visibly going away before you let go.
 */
@Composable
fun VoiceBar(
    level: Float,
    progress: Float,
    onCancel: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(WarpSpace.tiny),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A real target, in the corner the mic came from.
        //
        // The slide is the fast way and the hint teaches it, but a gesture with
        // no visible target is a gesture people go looking for a button for —
        // observed doing exactly that, on the left, where the mic had been. So
        // the button is there, and it is also what the slide is sliding *from*:
        // it grows and reddens as the finger climbs, which is what makes the two
        // read as one idea rather than two ways of doing the same thing.
        CancelTarget(progress = progress, onClick = onCancel)

        Waveform(
            level = level,
            modifier = Modifier
                .weight(1f)
                .height(44.dp)
                .padding(horizontal = WarpSpace.medium)
                // Dims as the finger climbs. You should be able to see the
                // recording receding before you commit to losing it.
                .graphicsLayer { alpha = 1f - progress * 0.75f },
        )

        // Where the send button sits when not recording, so the thumb does not
        // have to move to finish.
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(44.dp),
            onClick = onDone,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Outlined.Check,
                    contentDescription = "Done",
                    modifier = Modifier.size(20.dp),
                    tint = Color.White,
                )
            }
        }
    }
}

/**
 * The cancel corner.
 *
 * Answers the slide as well as the tap: at rest it is a quiet outlined circle,
 * and as the finger climbs it fills, reddens and grows. By the time the gesture
 * would commit it is unmistakably the thing about to happen.
 */
@Composable
private fun CancelTarget(progress: Float, onClick: () -> Unit) {
    val error = MaterialTheme.colorScheme.error
    val fill by animateFloatAsState(
        targetValue = progress,
        animationSpec = warpTween(WarpMotion.QUICK),
        label = "cancelFill",
    )

    Box(
        modifier = Modifier
            .size(44.dp)
            .graphicsLayer {
                val grow = 1f + fill * 0.15f
                scaleX = grow
                scaleY = grow
            }
            .clip(CircleShape)
            .background(error.copy(alpha = fill * 0.9f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Outlined.Close,
            contentDescription = "Cancel",
            modifier = Modifier.size(20.dp),
            tint = lerpColor(MaterialTheme.colorScheme.onSurfaceVariant, Color.White, fill),
        )
    }
}

/**
 * Your voice, as bars.
 *
 * **Driven by the real microphone level**, not by a loop. A looping animation is
 * obvious inside a second and it is most of what makes voice interfaces feel
 * fake — it keeps dancing when you stop talking.
 *
 * Bars nearer the middle react more strongly than those at the edges, so a
 * syllable reads as a pulse spreading outward rather than as every bar jumping
 * by the same amount at the same instant.
 */
@Composable
private fun Waveform(level: Float, modifier: Modifier = Modifier) {
    val bars = 27
    val animated by animateFloatAsState(
        targetValue = level,
        animationSpec = warpTween(WarpMotion.QUICK),
        label = "voiceLevel",
    )

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(bars) { i ->
            // 1 at the centre, falling away to about 0.25 at the ends.
            val centre = 1f - abs(i - (bars - 1) / 2f) / ((bars - 1) / 2f)
            val weight = 0.25f + centre * 0.75f

            // A small standing shape so the bar is a row of dots at silence
            // rather than an empty gap — an empty control reads as broken.
            val idle = 0.10f + 0.06f * sin(i * 1.7f)
            val height = (idle + animated * weight * 0.9f).coerceIn(0.06f, 1f)

            Box(
                modifier = Modifier
                    .size(width = 3.dp, height = (height * 34).dp)
                    .clip(RoundedCornerShape(WarpRadius.small))
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

private fun lerpColor(from: Color, to: Color, t: Float) = Color(
    red = from.red + (to.red - from.red) * t,
    green = from.green + (to.green - from.green) * t,
    blue = from.blue + (to.blue - from.blue) * t,
    alpha = from.alpha + (to.alpha - from.alpha) * t,
)
