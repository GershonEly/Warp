package dev.ely.warp.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val WarpLightColors = lightColorScheme(
    primary = WarpPrimary,
    onPrimary = Color.White,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnSurface,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceHigh,
    // Not white, unlike the two rungs above it. Light mode builds depth the
    // other way up — raised things are white and the page is the grey one — so
    // the token used for a quiet button has to be the *recessed* grey, or the
    // button disappears into the card it sits on.
    surfaceContainerHighest = LightSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightHairline,
    error = WarpError,
    onError = Color.White,
    errorContainer = Color(0xFFFDE7E7),
    onErrorContainer = Color(0xFF6E1A1C),
)

private val WarpDarkColors = darkColorScheme(
    primary = WarpPrimary,
    onPrimary = Color.White,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = Color(0xFFCFE8FA),
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceContainer,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceHigh,
    // Mapped explicitly. Left unset, Material fills it from its own baseline
    // palette, which is an untinted grey — so the one token used for a quiet
    // circular button would have been the only surface in the app with no blue
    // in it, and the accent would have looked stranded beside it.
    surfaceContainerHighest = DarkSurfaceHigh,
    outline = DarkOutline,
    outlineVariant = DarkHairline,
    error = WarpError,
    onError = Color.White,
    errorContainer = Color(0xFF3A1416),
    onErrorContainer = Color(0xFFFFC9CB),
)

/** The hairline colour for the current theme — depth comes from this, not shadow. */
val LocalHairline = compositionLocalOf { DarkHairline }

/** Background for compiler output and other machine-written text. */
val LocalCodeSurface = compositionLocalOf { DarkCodeSurface }

/**
 * Whether the dark theme is showing.
 *
 * A handful of effects cannot simply be given a colour token because they are
 * *effects*: the bloom behind the mark has to be far weaker on white than on
 * black, or it reads as a smudge rather than as light.
 */
val LocalIsDark = compositionLocalOf { true }

/**
 * Warp's theme.
 *
 * No dynamic colour. Warp keeps its own palette on every device, the way a
 * desktop IDE does — the app should look like itself, not like the wallpaper.
 */
@Composable
fun WarpTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) WarpDarkColors else WarpLightColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view)
                .isAppearanceLightStatusBars = !darkTheme
        }
    }

    // Read once here so every animation can honour the system's
    // reduce-animations setting without each one having to check.
    val animationsEnabled = rememberAnimationsEnabled()

    CompositionLocalProvider(
        LocalAnimationsEnabled provides animationsEnabled,
        LocalHairline provides if (darkTheme) DarkHairline else LightHairline,
        LocalCodeSurface provides if (darkTheme) DarkCodeSurface else LightCodeSurface,
        LocalIsDark provides darkTheme,
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = WarpTypography,
            shapes = WarpShapes,
            content = content,
        )
    }
}
