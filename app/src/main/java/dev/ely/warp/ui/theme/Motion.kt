package dev.ely.warp.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * Warp's motion, in one place.
 *
 * Durations are named here rather than typed as numbers across the app, so the
 * whole thing can be paced at once instead of hunting literals.
 *
 * Deliberately no springs and no overshoot. Bouncy motion is what makes a
 * developer tool read as a toy, and the plan rules it out explicitly.
 */
object WarpMotion {

    /** Small things: a press, a colour, a fade. */
    const val QUICK = 150

    /** The default. Cards expanding, content appearing, list changes. */
    const val NORMAL = 250

    /** Whole screens moving. */
    const val SLOW = 300

    /** Material's standard curve: leaves quickly, arrives gently. */
    val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** For things entering the screen — decelerates into place. */
    val Enter: Easing = CubicBezierEasing(0f, 0f, 0f, 1f)

    /** For things leaving — accelerates away, no lingering. */
    val Exit: Easing = CubicBezierEasing(0.3f, 0f, 1f, 1f)

    /** How far a message rises as it fades in. */
    const val RISE_DP = 8
}

/**
 * Whether animations should play at all.
 *
 * False when the user has turned animations off in Android's accessibility
 * settings. Some people get motion sick; others just want the app to be
 * instant. Motion is polish, and polish must never become a barrier.
 */
val LocalAnimationsEnabled = compositionLocalOf { true }

/**
 * Reads the system animation setting.
 *
 * Android keeps **three** independent scales, and picking the wrong one is easy:
 *
 * - `WINDOW_ANIMATION_SCALE` — windows opening and closing
 * - `TRANSITION_ANIMATION_SCALE` — transitions *between activities*
 * - `ANIMATOR_DURATION_SCALE` — ObjectAnimator, and so everything Compose
 *   animates inside a screen
 *
 * This originally read only `TRANSITION_ANIMATION_SCALE`, which is the one that
 * has nothing to do with Warp's animations. It happened to work for the case it
 * was written for — accessibility's "Remove animations" sets all three to zero —
 * but Developer Options sets them independently, and turning off only
 * `animator_duration_scale`, which is the usual choice, left every animation in
 * the app running at full length.
 *
 * Any of the three being zero is now taken as "off". Someone who has silenced
 * one of them did not mean "except in this app".
 */
@Composable
fun rememberAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    return runCatching {
        val scales = listOf(
            Settings.Global.ANIMATOR_DURATION_SCALE,
            Settings.Global.TRANSITION_ANIMATION_SCALE,
            Settings.Global.WINDOW_ANIMATION_SCALE,
        )
        scales.none { Settings.Global.getFloat(context.contentResolver, it, 1f) == 0f }
    }.getOrDefault(true)
}

/**
 * A duration that collapses to zero when animations are off.
 *
 * Every animation in Warp should take its duration from here rather than from
 * a constant, so honouring the setting is automatic instead of remembered.
 */
@Composable
@ReadOnlyComposable
fun motionDuration(millis: Int): Int =
    if (LocalAnimationsEnabled.current) millis else 0

/** The standard spec for most animations, already respecting the setting. */
@Composable
@ReadOnlyComposable
fun <T> warpTween(
    durationMillis: Int = WarpMotion.NORMAL,
    easing: Easing = WarpMotion.Standard,
): FiniteAnimationSpec<T> = tween(motionDuration(durationMillis), easing = easing)
