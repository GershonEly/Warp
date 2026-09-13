package dev.ely.warp.ai

import kotlinx.coroutines.flow.Flow

/**
 * The socket every AI plugs into.
 *
 * The whole point of this interface is that the mock and a real model are
 * interchangeable. Every screen is built and tested against [MockProvider];
 * adding a key later swaps the implementation and nothing above this layer
 * changes.
 *
 * See §1 of IMPLEMENTATION_PLAN.md — "built now, key added tomorrow".
 */
interface AiProvider {

    /** Stable id used in settings and storage, e.g. "mock", "anthropic". */
    val id: String

    /** Shown in the provider picker. */
    val displayName: String

    /** False only for the mock, which is why demo mode needs no key. */
    val requiresKey: Boolean

    /**
     * True when the model picker should offer effort levels for this provider's
     * models — "Claude Opus 5 (High)" and "(Low)" as separate rows.
     */
    val supportsEffort: Boolean get() = false

    /**
     * Models this provider can offer right now.
     *
     * Real providers fetch this live and cache it; the mock returns a fixed
     * list. Failure is returned rather than thrown so the settings screen can
     * show a clear message.
     */
    suspend fun listModels(): Result<List<AiModel>>

    /**
     * Check the key and connection without spending a real request where
     * possible. Returns a short human-readable success line.
     */
    suspend fun testConnection(): Result<String>

    /**
     * Run one turn of the conversation.
     *
     * Emits [AiEvent]s as they arrive so text can appear word by word.
     * Cancelling collection must stop the request.
     *
     * The flow completes after [AiEvent.Completed] or [AiEvent.Failed]; it
     * does not throw for ordinary failures like a bad key, because those are
     * expected states the UI has to render, not crashes.
     */
    fun stream(request: AiRequest): Flow<AiEvent>
}

// ── what goes in ─────────────────────────────────────────────────────────

data class AiRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val systemPrompt: String? = null,
    val effort: Effort = Effort.LOW,
    /** Tools the model may call. Empty means plain chat. */
    val tools: List<ToolSpec> = emptyList(),
)

/**
 * How hard the model should think.
 *
 * Kept abstract on purpose: each provider maps these to its own parameter
 * (thinking budget, reasoning effort, and so on) rather than leaking one
 * vendor's vocabulary into the rest of the app.
 */
enum class Effort(val label: String) {
    LOW("Low"),
    MEDIUM("Medium"),
    HIGH("High"),
    MAX("Max"),
}

/** A short note on what a model is good for, shown beside it in the picker. */
enum class ModelBadge(val label: String) {
    FAST("Fast"),
    THINKING("Thinking"),
}

/**
 * One row in the model picker.
 *
 * A model that supports effort appears once per level — "Claude Opus 5 (High)"
 * and "(Low)" are separate choices — so picking a model and picking how hard it
 * thinks is a single decision rather than two.
 */
data class ModelChoice(
    val providerId: String,
    val modelId: String,
    val modelName: String,
    val effort: Effort?,
    val badge: ModelBadge?,
    /** The family this belongs to — "Claude", "Gemini", "GPT". One folder each. */
    val group: String = "Other",
    /** False when the provider has no key yet; shown greyed out. */
    val available: Boolean = true,
    /** Shown in the short default list. Everything else is behind "show all". */
    val recommended: Boolean = true,
    /** See [AiModel.canSee]. Null means the provider did not say. */
    val canSee: Boolean? = null,
) {
    val label: String get() = if (effort == null) modelName else "$modelName (${effort.label})"

    val key: String get() = "$providerId|$modelId|${effort?.name ?: ""}"
}

/**
 * Who is allowed to name a conversation.
 *
 * A closed set rather than a nullable model, because "off" and "nothing chosen
 * yet" are different answers and one nullable field cannot hold both — which
 * matters as soon as somebody picks a model, switches to Off, and comes back
 * expecting their pick to still be remembered.
 */
sealed interface Naming {
    /** Nobody. Conversations keep the name taken from the first question. */
    data object Off : Naming

    /** The cheapest model from whichever provider the conversation used. */
    data object Automatic : Naming

    /** Exactly this one, whatever the conversation is using. */
    data class Specific(
        val providerId: String,
        val modelId: String,
        val modelName: String,
    ) : Naming
}

data class AiModel(
    val id: String,
    val displayName: String,
    val contextTokens: Int? = null,
    val supportsTools: Boolean = true,
    val badge: ModelBadge? = null,
    /** Effort levels this model offers. Empty means effort is not a choice. */
    val effortLevels: List<Effort> = emptyList(),
    /**
     * Whether this model can be shown a picture — §5o.
     *
     * **Three-valued on purpose.** `true` and `false` are things a provider
     * told us; `null` is "nobody said", and the three must not be flattened.
     * Warp refuses an image only on a definite `false`, because refusing on a
     * guess would block a model that can see, and this project has been bitten
     * by confident wrong answers more than by missing ones.
     *
     * OpenRouter reports it per model. Most others do not, so they stay null
     * and the provider's own error is what surfaces — which is at least honest
     * about where the refusal came from.
     */
    val canSee: Boolean? = null,
)

data class ToolSpec(
    val name: String,
    val description: String,
    /** JSON Schema for the arguments. */
    val parametersJson: String,
)

// ── the conversation ─────────────────────────────────────────────────────

enum class Role { USER, ASSISTANT, SYSTEM }

data class ChatMessage(
    val id: String,
    val role: Role,
    val text: String,
    /**
     * What the model thought on the way to the answer, if it said.
     *
     * Kept apart from [text] because it is not the answer and must never be
     * read as one. It is also the only part of a reply you are already paying
     * for and could not see: reasoning tokens are billed whether or not
     * anything displays them.
     *
     * Empty for models that do not expose reasoning, which is most of the cheap
     * ones — and empty is why the UI shows no control rather than a control
     * that opens onto nothing.
     */
    val thinking: String = "",
    /** Tool calls the assistant asked for in this message. */
    val toolCalls: List<ToolCall> = emptyList(),
    /**
     * What you showed it — §5h. Yours, never the model's.
     *
     * On the message rather than on the request, because it is part of what was
     * said and has to still be there when the conversation is read back a week
     * later. The bytes are on disk; this holds the path.
     */
    val attachments: List<Attachment> = emptyList(),
    /**
     * Sent in your turn, but not written by you — §5l.
     *
     * A screenshot Warp took is an image, and a provider only accepts an image
     * in the user's turn, so that is the turn it goes in. It was still not typed
     * by a person, and a transcript showing Warp's own capture as something you
     * said is a transcript that lies about who did what.
     *
     * So it is marked, drawn as the app's, and **cannot be rewound to**: §9f
     * gives Edit to your own messages because they are moments you were in, and
     * this is not one of them.
     */
    val byApp: Boolean = false,
    /**
     * What this reply cost, once the provider has said — §5p.
     *
     * On the message rather than only on a running total, because a total tells
     * you the conversation is expensive and this tells you **which answer** was.
     * Null until the reply finishes, and null on providers that never report.
     */
    val usage: Usage? = null,
    /** True while text is still streaming in. */
    val streaming: Boolean = false,
    val error: AiError? = null,
    /**
     * When the message was created.
     *
     * Stamped once, by whoever makes the message, and never touched again — it
     * is what orders a transcript when it is read back from disk. Sorting by
     * insertion order would work until the first time a reply is edited in
     * place, which is every single streamed token.
     */
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * Tools whose output is their own input read back.
 *
 * Named here rather than asked of the tool, because this is the one place that
 * builds a request and the providers cannot see the tools package. Adding a
 * writer means adding it here; the cost of forgetting is a bigger bill, not a
 * broken feature, so it is worth a name in the file that pays it.
 */
private val ECHOES_ITS_INPUT = setOf("write_file", "edit_file")

data class ToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
    val status: Status = Status.PENDING,
    /** One line for the card — what it did, never "ok". */
    val result: String? = null,
    /**
     * The full output, kept apart from the summary.
     *
     * Separate because they are read by different readers: the summary is for
     * the person glancing at a card, and the body is what goes back to the model
     * and what a person opens when the summary is not enough. Merging them means
     * either a card with four hundred lines in it or a model that only ever
     * learns "12 matches".
     */
    val body: String? = null,
) {
    /**
     * What the model is told this call produced.
     *
     * Usually the body — but not when the body is the model's own input handed
     * straight back to it. `write_file` returned the whole file it had just been
     * given, and `edit_file` returned a diff built from the two strings in its
     * arguments. The arguments are already in the transcript, so every one of
     * those was a second copy, re-sent on every later request for the rest of
     * the conversation.
     *
     * Measured in a real 90-message session: 104,552 characters, of which
     * 51,271 were tool output, and `write_file` alone carried 11,332 of them
     * twice over. Dropping both echoes removes 37,059 of that 51,271 — 72% of
     * everything the tools said. `edit_file` is the larger half, not because
     * any one diff is big but because there were forty of them against two
     * writes; the cost is the habit, not the size.
     *
     * The body itself is untouched, because the card still shows it and the
     * archive still keeps it. This is only about what goes on the wire — which
     * also makes conversations that already exist cheaper to carry on with,
     * rather than only new ones.
     */
    val forModel: String?
        get() = when {
            name in ECHOES_ITS_INPUT -> result
            !body.isNullOrBlank() -> body
            else -> result
        }

    enum class Status {
        PENDING,

        /**
         * Waiting for a person to say yes.
         *
         * Its own state rather than RUNNING, because those need opposite things
         * from you: one is a spinner you ignore, the other is a question that
         * stops until you answer it. Showing "running" while nothing runs is
         * the same class of lie as the card that showed a call nobody made.
         */
        ASKING,

        RUNNING,
        DONE,
        FAILED,
        DENIED,
    }
}

// ── what comes out ───────────────────────────────────────────────────────

sealed interface AiEvent {
    /** A chunk of assistant text. */
    data class TextDelta(val text: String) : AiEvent

    /**
     * A chunk of the model's reasoning.
     *
     * Its own event rather than text with a flag, because everything downstream
     * treats the two differently: one is shown, one is folded away; one is sent
     * back to the provider next turn, one is not.
     */
    data class ReasoningDelta(val text: String) : AiEvent

    /** The model wants to call a tool. */
    data class ToolCallRequested(val call: ToolCall) : AiEvent

    /**
     * A tool call changed state — started, finished, failed, was refused.
     *
     * Without this the card that announces a tool has no way to ever stop
     * saying "waiting", which is how it shipped: the reply said the file was
     * written while the card beside it still claimed to be waiting to write it.
     *
     * It belongs on the interface rather than in the mock, because every
     * provider needs it the moment tools actually run in Step 5.
     */
    data class ToolCallUpdated(
        val id: String,
        val status: ToolCall.Status,
        val result: String? = null,
    ) : AiEvent

    /** The turn ended normally. */
    data class Completed(
        val stopReason: String = "end_turn",
        /**
         * What this request cost and used, when the provider says — §5p.
         *
         * Arrives **at the end**, not during, and that is the honest shape of
         * it: providers report usage with the final chunk. Null where nobody
         * reported anything, which is most providers — and null must read as
         * "unknown", never as free.
         */
        val usage: Usage? = null,
    ) : AiEvent

    /** The turn ended badly. Not an exception — the UI renders this. */
    data class Failed(val error: AiError) : AiEvent
}

/**
 * What one request spent — §5p.
 *
 * @param costUsd what the provider charged, when it says. OpenRouter does;
 *   most do not, and a token count without a price is still worth showing.
 */
data class Usage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val costUsd: Double? = null,
) {
    val totalTokens: Int get() = promptTokens + completionTokens

    operator fun plus(other: Usage) = Usage(
        promptTokens = promptTokens + other.promptTokens,
        completionTokens = completionTokens + other.completionTokens,
        // Two unknowns are still unknown; one known and one unknown is the
        // known part, which is the most that can honestly be claimed.
        costUsd = when {
            costUsd == null && other.costUsd == null -> null
            else -> (costUsd ?: 0.0) + (other.costUsd ?: 0.0)
        },
    )
}

// ── failures worth telling the user apart ────────────────────────────────

sealed class AiError(val message: String, val canRetry: Boolean) {
    data object NoKey : AiError(
        "No API key yet. Add one in Settings, or keep using the mock AI.", false)

    data object BadKey : AiError(
        "That key was rejected. Check it in Settings.", false)

    data object RateLimited : AiError(
        "The provider is rate limiting us. Wait a moment and try again.", true)

    /**
     * The key is fine. The balance is not — §5o.
     *
     * Its own case because it was being reported as [BadKey], and a message
     * telling somebody to re-check a working key sends them to fix something
     * that is not broken. He saw that three times in one session while the key
     * worked before and after.
     *
     * `canRetry` is false on purpose: a spent balance does not clear by asking
     * again, which is exactly what forty retries in two bursts proved.
     */
    data class OutOfCredits(val detail: String) : AiError(
        if (detail.isBlank()) "The provider says there are no credits left for this key."
        else "No credits left: $detail",
        false,
    )

    /**
     * Refused, for a reason that is the provider's to explain.
     *
     * 403 was folded into [BadKey] because both are "not allowed". They are not
     * the same thing to the person reading it: a rejected key is fixed in
     * Settings, and a refused request is fixed by changing the request, the
     * model, or nothing at all.
     */
    data class Refused(val detail: String) : AiError(
        if (detail.isBlank()) "The provider refused that request."
        else "The provider refused that: $detail",
        false,
    )

    data object Offline : AiError(
        "No internet connection.", true)

    data class Server(val detail: String) : AiError(
        "The provider had a problem: $detail", true)

    data class Unknown(val detail: String) : AiError(detail, true)
}
