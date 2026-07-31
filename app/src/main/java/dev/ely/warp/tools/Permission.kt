package dev.ely.warp.tools

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Who is allowed to change your files.
 *
 * The answer is always **you**. Nothing in this file decides anything: the desk
 * carries a question to a screen and carries an answer back, and the one piece
 * of judgement it is trusted with — remembering that you already said Always —
 * is a decision you made, replayed.
 *
 * Kept apart from [ToolRunner] deliberately. A runner that could grant itself
 * permission is a runner that eventually does, and the failure would look
 * exactly like it working, which is the shape of every bad bug in this project.
 */

/** What a person can say when asked. */
enum class Decision {
    /** This once. The next call asks again. */
    ONCE,

    /** This tool, from now on, until revoked. */
    ALWAYS,

    /** No. The tool does not run and the model is told so. */
    DENY,
}

/** What the runner needs in order to ask. */
interface AsksPermission {
    /** Suspends until a person answers. */
    suspend fun ask(call: PermissionRequest): Decision
}

/**
 * One outstanding question.
 *
 * Carries the description rather than the raw arguments, because "src/Main.kt ·
 * 34 lines" is a thing you can judge and `{"path":"src/Main.kt","conte…` is not.
 */
data class PermissionRequest(
    /** Which tool call this belongs to, so the right card grows buttons. */
    val callId: String,
    val toolName: String,
    val summary: String,
    val risk: Risk,
)

/**
 * The desk between the model and you.
 *
 * Holds at most one question at a time. That is not a limitation to fix later —
 * a stack of permission prompts is how people learn to tap Allow without
 * reading, and this whole design exists so the expensive prompts stay visible.
 */
class PermissionDesk(context: Context) : AsksPermission {

    private val prefs = context.getSharedPreferences("warp_tool_grants", Context.MODE_PRIVATE)

    private val _pending = MutableStateFlow<PermissionRequest?>(null)

    /** The question on screen, or null when nothing is being asked. */
    val pending: StateFlow<PermissionRequest?> = _pending.asStateFlow()

    private var answer: CompletableDeferred<Decision>? = null

    /** Tools you have said Always to. */
    val granted: Set<String> get() = prefs.getStringSet(KEY, emptySet()).orEmpty()

    override suspend fun ask(call: PermissionRequest): Decision {
        // Anything you have already blessed goes straight through — that is the
        // entire point of Always, and asking again would teach you to stop
        // reading the ones that matter.
        if (call.toolName in granted) return Decision.ALWAYS

        val waiting = CompletableDeferred<Decision>()
        answer = waiting
        _pending.value = call

        return try {
            waiting.await()
        } finally {
            // Also on cancellation. Leaving a dead question on screen would give
            // you buttons that answer nobody, which is worse than no buttons.
            _pending.value = null
            answer = null
        }
    }

    /** Called by the screen when you tap. */
    fun answer(decision: Decision) {
        val request = _pending.value ?: return
        if (decision == Decision.ALWAYS) {
            // A new set, not the same one mutated. SharedPreferences keeps the
            // instance it was given, so editing it in place can write nothing
            // at all — silently, which is this project's least favourite word.
            prefs.edit().putStringSet(KEY, granted + request.toolName).apply()
        }
        answer?.complete(decision)
    }

    /** Take back every Always. Shown in Settings. */
    fun revokeAll() = prefs.edit().remove(KEY).apply()

    private companion object {
        const val KEY = "always"
    }
}
