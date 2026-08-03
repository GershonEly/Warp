package dev.ely.warp.work

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What Warp is doing, in one line, for anything outside the screen.
 *
 * The engine sets it; the foreground service reads it and stops itself when it
 * goes quiet. The engine still knows nothing about Android services, for the
 * same reason it knows nothing about Room — it moves messages, and what a
 * notification is is somebody else's business.
 *
 * A global rather than something passed down, because the two ends have no
 * relationship: one is a coroutine on a background thread, the other is a
 * service Android may start after the screen is gone. Inventing a dependency
 * between them purely to carry one string would be worse than this.
 */
object Working {

    private val _now = MutableStateFlow<String?>(null)

    /** A short line for the notification, or null when nothing is running. */
    val now: StateFlow<String?> = _now.asStateFlow()

    /** True while there is anything worth keeping the process alive for. */
    val busy: Boolean get() = _now.value != null

    fun started(what: String) { _now.value = what }

    /**
     * Change the line without ending the work.
     *
     * A turn that spends a minute compiling should say so rather than sitting on
     * "Thinking" — the notification is the only thing you can see once you have
     * left the app, so it is the only place progress can be reported at all.
     */
    fun update(what: String) {
        if (_now.value != null) _now.value = what
    }

    fun finished() { _now.value = null }

    /**
     * Run something that must not be interrupted, whoever asked for it.
     *
     * A build takes half a minute and is the most expensive thing to lose, but
     * only a turn was announcing itself — so a build started any other way ran
     * unprotected. It marks work as started only if nothing was running, and
     * puts back exactly what it found, so a build inside a turn does not end the
     * turn's protection when it finishes.
     */
    suspend fun <T> during(what: String, block: suspend () -> T): T {
        val before = _now.value
        _now.value = what
        try {
            return block()
        } finally {
            _now.value = before
        }
    }
}
