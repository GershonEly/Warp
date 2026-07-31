package dev.ely.warp.tools

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
 * Where standing permissions are kept.
 *
 * An interface so the desk does not learn what Room is, and so a desk with no
 * store behind it still works — it simply never remembers, which is the safe
 * way round.
 */
interface GrantStore {
    suspend fun isGranted(conversationId: String, toolName: String): Boolean
    suspend fun grant(conversationId: String, toolName: String)
}

/**
 * The desk between the model and you.
 *
 * Holds at most one question at a time. That is not a limitation to fix later —
 * a stack of permission prompts is how people learn to tap Allow without
 * reading, and this whole design exists so the expensive prompts stay visible.
 *
 * **Always is remembered per conversation**, never for the app. When you say
 * Always you mean *in this piece of work*; a grant that outlives the chat it was
 * given in would apply a decision made in a throwaway experiment to the work you
 * care about, and nothing on screen would say it had.
 *
 */
class PermissionDesk(
    private val store: GrantStore? = null,
) : AsksPermission {

    /**
     * Which chat is open. Asked freshly at every question.
     *
     * A property set afterwards rather than a constructor argument, because the
     * desk has to exist before the engine does — the runner needs it — and the
     * engine is what knows which conversation is open. Until it is set the
     * answer is null, which means nothing is remembered rather than something
     * being remembered against the wrong chat.
     */
    @Volatile
    var conversation: () -> String? = { null }

    private val _pending = MutableStateFlow<PermissionRequest?>(null)

    /** The question on screen, or null when nothing is being asked. */
    val pending: StateFlow<PermissionRequest?> = _pending.asStateFlow()

    private var answer: CompletableDeferred<Decision>? = null

    override suspend fun ask(call: PermissionRequest): Decision {
        val chat = conversation()

        // Anything this conversation has already blessed goes straight through —
        // that is the entire point of Always, and asking again would teach you
        // to stop reading the ones that matter.
        //
        // **Except RUNS, which is asked every single time.** Building,
        // installing and launching are one `/goal` away from happening in a
        // loop while nobody is watching, and a standing yes to that is not a
        // permission, it is a handover. Checked here rather than only in the
        // sheet, because a screen can be wrong and this is the path every
        // decision actually takes.
        if (call.risk != Risk.RUNS && chat != null &&
            store?.isGranted(chat, call.toolName) == true
        ) {
            return Decision.ALWAYS
        }

        val waiting = CompletableDeferred<Decision>()
        answer = waiting
        _pending.value = call

        val decision = try {
            waiting.await()
        } finally {
            // Also on cancellation. Leaving a dead question on screen would give
            // you buttons that answer nobody, which is worse than no buttons.
            _pending.value = null
            answer = null
        }

        // Written after the answer, not inside the tap handler, because storing
        // it is a suspending database write and a button press is not the place
        // to start one that nothing waits for.
        // Never remembered for RUNS either, so a tap on a stale button cannot
        // leave a grant behind that the check above then has to defend against.
        if (decision == Decision.ALWAYS && chat != null && call.risk != Risk.RUNS) {
            store?.grant(chat, call.toolName)
        }
        return decision
    }

    /** Called by the screen when you tap. */
    fun answer(decision: Decision) {
        answer?.complete(decision)
    }
}
