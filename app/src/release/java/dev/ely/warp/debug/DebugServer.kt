package dev.ely.warp.debug

import android.content.Context

/**
 * The release build's debug server: nothing.
 *
 * The real one is in `src/debug` and is **not compiled into this build at all**.
 * That is the point of the arrangement — a flag can be flipped, an `if` can be
 * inverted by a bad merge, and a `BuildConfig.DEBUG` check can be defeated by
 * someone shipping a debuggable APK by accident. A class that is not in the
 * build cannot answer a socket under any of those circumstances.
 *
 * This stub exists so the rest of the app compiles against one name. Every
 * method is empty and [IS_SUPPORTED] is false, which is also what hides the
 * Settings section — nobody is offered a control that does nothing.
 */
object DebugServer {

    const val IS_SUPPORTED = false
    const val PORT = 0

    @Suppress("UNUSED_PARAMETER")
    fun start(context: Context) = Unit

    fun stop() = Unit
}
