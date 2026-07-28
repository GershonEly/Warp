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
 * `TRANSITION_ANIMATION_SCALE` is 0 when the user has disabled animations —
 * including via battery saver, which also turns them off.
 */
@Composable
fun rememberAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    return runCatching {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.TRANSITION_ANIMATION_SCALE,
            1f,
        ) != 0f
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
