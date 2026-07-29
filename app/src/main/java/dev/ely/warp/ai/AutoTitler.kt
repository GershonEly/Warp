package dev.ely.warp.ai

import android.util.Log
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull

/**
 * Gives a conversation a name.
 *
 * Runs once, after the first reply lands, from the first question and the start
 * of the first answer. Three rules shape it, and all three come from the same
 * idea — a name is a small thing that must never get in the way of the work:
 *
 * - **It never overwrites a manual name.** The guard is in the SQL `WHERE`
 *   clause, not here, so it cannot lose a race against someone renaming the
 *   conversation while the model is still thinking.
 * - **It never blocks the chat.** It runs beside the conversation; until it
 *   lands the drawer shows the first question, trimmed. A title arriving late is
 *   fine. A chat that waits to be named is not.
 * - **It uses the cheap model**, not the one chosen for the conversation.
 *   Nobody should pay Opus rates to name a chat.
 */
object AutoTitler {

    private const val TAG = "WarpTitler"

    private const val PROMPT =
        "Name this conversation in five words or fewer. " +
            "Reply with the name only — no quotes, no punctuation at the end, " +
            "no preamble. Describe the subject, not the fact that it is a " +
            "conversation."

    /**
     * Ask [provider] for a title.
     *
     * Returns null on any failure, and that is deliberate: naming is a nicety,
     * and there is no version of "the network was down" that should surface to
     * someone who just wanted to ask a question. The fallback title already
     * reads fine.
     */
    suspend fun title(
        provider: AiProvider,
        model: String,
        question: String,
        answer: String,
    ): String? = runCatching {
        val request = AiRequest(
            model = model,
            messages = listOf(
                ChatMessage(
                    id = "title",
                    role = Role.USER,
                    text = buildString {
                        append(PROMPT)
                        append("\n\nQuestion: ")
                        append(question.take(500))
                        if (answer.isNotBlank()) {
                            append("\n\nAnswer so far: ")
                            append(answer.take(500))
                        }
                    },
                )
            ),
            systemPrompt = null,
            // Cheapest setting available. This is a labelling task.
            effort = Effort.LOW,
        )

        val text = buildString {
            provider.stream(request).collect { event ->
                if (event is AiEvent.TextDelta) append(event.text)
            }
        }

        text.trim().lines().firstOrNull()?.trim()?.trim('"', '\'', '.')?.takeIf {
            // A model that ignores "five words or fewer" and writes a sentence
            // has not given us a title, and a truncated sentence is worse than
            // the fallback.
            it.isNotEmpty() && it.length <= 60
        }
    }.onFailure { Log.w(TAG, "could not name conversation", it) }.getOrNull()

    /**
     * A name to use until — or instead of — the model's.
     *
     * The first question, cut at a word boundary. Almost always readable,
     * because the first thing someone types is what the conversation is about.
     */
    fun fallback(question: String): String {
        val clean = question.trim().replace('\n', ' ')
        if (clean.length <= 38) return clean.ifEmpty { "New chat" }
        val cut = clean.take(38)
        val lastSpace = cut.lastIndexOf(' ')
        return (if (lastSpace > 18) cut.take(lastSpace) else cut) + "…"
    }
}
