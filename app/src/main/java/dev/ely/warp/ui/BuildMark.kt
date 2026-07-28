package dev.ely.warp.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ely.warp.ui.theme.LocalAnimationsEnabled
import dev.ely.warp.ui.theme.WarpAccent
import dev.ely.warp.ui.theme.warpTween

/**
 * The mark, drawn edge by edge, as a build progress display.
 *
 * The vector file draws all thirty edges as one path, which cannot show
 * partial progress — so the same computed geometry is drawn here in code
 * instead, letting each edge be lit on its own.
 *
 * As a build advances the solid assembles: dark edges brighten group by group
 * until, at the last stage, the shape is whole. It is the same object that
 * turns while the model thinks and sits on the home screen — one symbol
 * carrying every state, rather than a progress bar borrowed from elsewhere.
 */
@Composable
fun BuildMark(
    progress: Float,
    modifier: Modifier = Modifier,
    size: Dp = 180.dp,
    active: Boolean = false,
    failed: Boolean = false,
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        // Slow: edges should be seen arriving, not blink on.
        animationSpec = warpTween(700),
        label = "buildProgress",
    )

    // A gentle turn while working, so the shape reads as alive without
    // spinning like a loading indicator.
    val animate = LocalAnimationsEnabled.current && active
    val transition = rememberInfiniteTransition(label = "buildSpin")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(24_000, easing = LinearEasing), RepeatMode.Restart),
        label = "buildAngle",
    )

    val lit = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val glow = if (failed) MaterialTheme.colorScheme.error else WarpAccent
    // The unlit edges must still draw the whole solid, so the shape is legible
    // before the build starts. outlineVariant is an 8% hairline — far too faint
    // for that; this is the text grey, dimmed.
    val dim = MaterialTheme.colorScheme.onSurfaceVariant

    Canvas(modifier = modifier.size(size)) {
        val scale = this.size.minDimension / VIEWPORT
        val stroke = (this.size.minDimension / 90f).coerceAtLeast(1.5f)

        rotate(if (animate) angle else 0f) {
            EDGES.forEachIndexed { index, edge ->
                // Each edge has its own threshold, so the solid fills in a
                // steady sweep rather than all at once.
                val threshold = index.toFloat() / EDGES.size
                val on = animatedProgress > threshold

                // The most recently lit edges carry the brighter cyan, which
                // gives the assembly a visible leading edge.
                val leading = on && animatedProgress - threshold < 0.18f

                drawLine(
                    color = when {
                        leading -> glow
                        on -> lit
                        else -> dim.copy(alpha = 0.35f)
                    },
                    start = Offset(edge[0] * scale, edge[1] * scale),
                    end = Offset(edge[2] * scale, edge[3] * scale),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

/** The drawing space the coordinates below were computed in. */
private const val VIEWPORT = 24f

/**
 * The thirty edges of an icosahedron projected down a 3-fold axis.
 *
 * Identical geometry to the vector drawables — generated from the same
 * projection, so the drawn mark and the icon are the same object.
 */
private val EDGES: List<FloatArray> = listOf(
    floatArrayOf(17.62f, 8.76f, 12.00f, 18.49f),
    floatArrayOf(17.62f, 8.76f, 6.38f, 8.76f),
    floatArrayOf(17.62f, 8.76f, 12.00f, 1.50f),
    floatArrayOf(17.62f, 8.76f, 21.09f, 17.25f),
    floatArrayOf(17.62f, 8.76f, 21.09f, 6.75f),
    floatArrayOf(12.00f, 18.49f, 6.38f, 8.76f),
    floatArrayOf(12.00f, 18.49f, 12.00f, 22.50f),
    floatArrayOf(12.00f, 18.49f, 2.91f, 17.25f),
    floatArrayOf(12.00f, 18.49f, 21.09f, 17.25f),
    floatArrayOf(6.38f, 8.76f, 2.91f, 6.75f),
    floatArrayOf(6.38f, 8.76f, 2.91f, 17.25f),
    floatArrayOf(6.38f, 8.76f, 12.00f, 1.50f),
    floatArrayOf(12.00f, 22.50f, 2.91f, 17.25f),
    floatArrayOf(12.00f, 22.50f, 21.09f, 17.25f),
    floatArrayOf(12.00f, 22.50f, 6.38f, 15.24f),
    floatArrayOf(12.00f, 22.50f, 17.62f, 15.24f),
    floatArrayOf(2.91f, 6.75f, 2.91f, 17.25f),
    floatArrayOf(2.91f, 6.75f, 12.00f, 1.50f),
    floatArrayOf(2.91f, 6.75f, 6.38f, 15.24f),
    floatArrayOf(2.91f, 6.75f, 12.00f, 5.51f),
    floatArrayOf(2.91f, 17.25f, 6.38f, 15.24f),
    floatArrayOf(12.00f, 1.50f, 21.09f, 6.75f),
    floatArrayOf(12.00f, 1.50f, 12.00f, 5.51f),
    floatArrayOf(21.09f, 17.25f, 21.09f, 6.75f),
    floatArrayOf(21.09f, 17.25f, 17.62f, 15.24f),
    floatArrayOf(21.09f, 6.75f, 12.00f, 5.51f),
    floatArrayOf(21.09f, 6.75f, 17.62f, 15.24f),
    floatArrayOf(6.38f, 15.24f, 12.00f, 5.51f),
    floatArrayOf(6.38f, 15.24f, 17.62f, 15.24f),
    floatArrayOf(12.00f, 5.51f, 17.62f, 15.24f),
)
