package dev.ely.warp.tools

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where the model goes to ask *you* something.
 *
 * The same shape as [PermissionDesk], and for the same reason: something has to
 * stop and wait for a person, and the thing that stops must not be the thing
 * that decides. A desk carries the question out and the answer back.
 *
 * One question at a time, and that is `/grill-me`'s whole point. A form with
 * eight questions on it gets skimmed and half-answered; a single question with a
 * recommended answer gets read. Warp asks, you answer, it asks the next one —
 * which is also why it survives being interrupted: every answered question is
 * already in the transcript.
 */

/**
 * One question, with a way out of it.
 *
 * @param recommended which option Warp would pick, by index, or null if it
 *   genuinely has no preference. **A recommendation is not a default** — nothing
 *   is chosen until you choose it. The point is to make answering cheap for
 *   someone who does not have an opinion, not to answer for them.
 */
data class Question(
    val callId: String,
    val text: String,
    val options: List<String>,
    val recommended: Int?,
    /** Why it is asking. One line, shown under the question. */
    val because: String?,
)

interface AsksQuestions {
    /** Suspends until a person answers. Returns their answer as written. */
    suspend fun ask(question: Question): String
}

class QuestionDesk : AsksQuestions {

    private val _pending = MutableStateFlow<Question?>(null)

    /** What is being asked, or null. */
    val pending: StateFlow<Question?> = _pending.asStateFlow()

    private var answer: CompletableDeferred<String>? = null

    override suspend fun ask(question: Question): String {
        val waiting = CompletableDeferred<String>()
        answer = waiting
        _pending.value = question

        return try {
            waiting.await()
        } finally {
            // Also on cancellation, so stopping a grilling does not leave a
            // question on screen with nothing behind it.
            _pending.value = null
            answer = null
        }
    }

    /** Called by the screen. Free text, whether tapped or typed. */
    fun answer(text: String) {
        answer?.complete(text)
    }
}
