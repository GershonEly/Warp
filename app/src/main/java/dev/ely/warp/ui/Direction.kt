package dev.ely.warp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
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
