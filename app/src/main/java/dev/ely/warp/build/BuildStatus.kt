package dev.ely.warp.build

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the phone is doing, so the room can say it.
 *
 * §9h asks for the light behind the composer to carry the build — moving while
 * compiling, settling when it lands, red when it fails — and states the
 * consequence plainly: **no progress bar anywhere.** The room is the progress.
 *
 * A single global rather than something threaded through the engine, because
 * the thing that compiles (a tool, on a background thread) and the thing that
 * shows it (a composable, several layers up) have no relationship at all and
 * should not be given one just for this. There is one phone and one compiler; a
 * second concurrent build is not a thing that can happen.
 */
object BuildStatus {

    enum class State {
        IDLE,
        RUNNING,

        /** Just landed. The room settles once, then goes back to idle. */
        SUCCEEDED,

        /**
         * Failed, and **stays** failed.
         *
         * §9h: *pulls red, and stays until it has been looked at.* A failure
         * that fades on a timer is a failure you can miss by looking away, and
         * a compile error you did not see is the one that wastes the next ten
         * minutes.
         */
        FAILED,
    }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    fun started() { _state.value = State.RUNNING }

    fun finished(ok: Boolean) {
        _state.value = if (ok) State.SUCCEEDED else State.FAILED
    }

    /**
     * Back to still.
     *
     * Called by the room after a success has been shown, and by the next build
     * for a failure — which is what "until it has been looked at" means in
     * practice: the red goes when you do something about it, not when a timer
     * decides you have had long enough.
     */
    fun settle() {
        if (_state.value == State.SUCCEEDED) _state.value = State.IDLE
    }
}
