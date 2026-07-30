package dev.ely.warp.ui.theme

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.random.Random

/**
 * The light and the texture the app is made of.
 *
 * Two ideas live here, and they are meant to be used together — the grain is
 * what makes the light look like light rather than like a sticker.
 */

// ── grain ────────────────────────────────────────────────────────────────

/**
 * A tile of noise, built once.
 *
 * **This is the cheapest and most important thing in this file.** A smooth
 * gradient across a large dark area in 8-bit colour produces visible stripes —
 * banding — and on an OLED panel at low brightness they are obvious. Every glow
 * added to a flat surface makes it worse. A little noise breaks the steps up and
 * the banding disappears.
 *
 * It also does the other half of the job: flat dark surfaces read as construction
 * paper, and two per cent of grain is the difference between a surface that looks
 * printed and one that looks lit. It is most of what separates the interfaces
 * people call expensive from the ones they cannot fault but do not like.
 *
 * 128×128 and tiled, so the whole app costs one 64KB bitmap. Seeded, so it is
 * identical on every launch and every device — noise that changes between frames
 * is film grain, which is a different and much more annoying effect.
 */
@Composable
private fun rememberGrainBrush(): ShaderBrush = remember {
    val size = 128
    val pixels = IntArray(size * size)
    val random = Random(0x5EED)

    for (i in pixels.indices) {
        // Symmetric around mid-grey and very narrow: the tile is drawn at a low
        // alpha, so what matters is that neighbouring pixels differ at all, not
        // by how much.
        val v = 118 + random.nextInt(20)
        pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }

    val bitmap = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    ShaderBrush(
        ImageShader(bitmap.asImageBitmap(), TileMode.Repeated, TileMode.Repeated)
    )
}

/**
 * Lay grain over whatever this modifies.
 *
 * Drawn *over* the content rather than under it, so it sits on the text as well
 * as the background. That is deliberate — grain under the surface only would
 * leave the type looking cut out and pasted on, which is the exact effect it
 * exists to remove.
 */
@Composable
fun Modifier.grain(alpha: Float = 0.035f): Modifier {
    val brush = rememberGrainBrush()
    return this.drawWithContent {
        drawContent()
        drawRect(brush = brush, alpha = alpha)
    }
}

// ── aurora ───────────────────────────────────────────────────────────────

/**
 * Three soft fields of light, mixed.
 *
 * The first version of this was a single radial gradient in one hue, and it read
 * as exactly what it was: a blob somebody drew. Light in a room arrives from
 * more than one direction and changes colour as it falls off, so this is three
 * overlapping fields in three related hues — **three at six per cent look like
 * light where one at eighteen looks like a sticker.**
 *
 * All three are anchored past the bottom edge, so only the top of each falloff is
 * ever on screen and the brightest part never is. That is the difference between
 * light coming from somewhere and a circle with a centre you can point at.
 *
 * @param strength scales all three at once — the effort setting drives this, so
 *   the room deepens as the model is asked to think harder.
 * @param drift a slowly moving value; each field responds at a different rate, so
 *   the arrangement never repeats and never reads as a loop.
 */
fun Modifier.aurora(
    strength: Float,
    drift: Float,
    primary: Color,
    accent: Color,
    indigo: Color,
): Modifier = drawBehind {
    if (strength <= 0f) return@drawBehind

    val w = size.width
    val h = size.height

    fun field(color: Color, alpha: Float, cx: Float, cy: Float, radius: Float) {
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(color.copy(alpha = alpha * strength), Color.Transparent),
                center = Offset(cx, cy),
                radius = radius,
            )
        )
    }

    // Every radius is a fraction of **height**, never of width, and that is a
    // fix rather than a preference. Sized by width these worked in portrait and
    // destroyed landscape: at 2712px wide the radius came out larger than the
    // whole screen was tall, so the entire display sat inside the bright core of
    // the gradient and the phone showed a wall of blue.
    //
    // Height is the axis the light actually travels along — it rises from the
    // bottom edge — so tying the falloff to it keeps the same shape whichever way
    // the phone is held. The horizontal positions stay in fractions of width, so
    // a wide screen spreads the three pools apart instead of stacking them.

    // Widest and deepest, straight below the composer: the base light.
    field(primary, 0.55f, w * (0.5f + drift * 0.06f), h * 1.02f, h * 0.52f)

    // Cooler, from the left, and the fastest mover of the three.
    field(accent, 0.42f, w * (0.16f - drift * 0.10f), h * (1.06f + drift * 0.02f), h * 0.38f)

    // Cold and tight, from the right, moving against the other two so the mix
    // shifts rather than sliding as one piece.
    field(indigo, 0.38f, w * (0.88f + drift * 0.07f), h * (1.10f - drift * 0.03f), h * 0.31f)
}
