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
) {
    val label: String get() = if (effort == null) modelName else "$modelName (${effort.label})"

    val key: String get() = "$providerId|$modelId|${effort?.name ?: ""}"
}

data class AiModel(
    val id: String,
    val displayName: String,
    val contextTokens: Int? = null,
    val supportsTools: Boolean = true,
    val badge: ModelBadge? = null,
    /** Effort levels this model offers. Empty means effort is not a choice. */
    val effortLevels: List<Effort> = emptyList(),
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
    /** Tool calls the assistant asked for in this message. */
    val toolCalls: List<ToolCall> = emptyList(),
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

data class ToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
    val status: Status = Status.PENDING,
    val result: String? = null,
) {
    enum class Status { PENDING, RUNNING, DONE, FAILED, DENIED }
}

// ── what comes out ───────────────────────────────────────────────────────

sealed interface AiEvent {
    /** A chunk of assistant text. */
    data class TextDelta(val text: String) : AiEvent

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
    data class Completed(val stopReason: String = "end_turn") : AiEvent

    /** The turn ended badly. Not an exception — the UI renders this. */
    data class Failed(val error: AiError) : AiEvent
}

// ── failures worth telling the user apart ────────────────────────────────

sealed class AiError(val message: String, val canRetry: Boolean) {
    data object NoKey : AiError(
        "No API key yet. Add one in Settings, or keep using the mock AI.", false)

    data object BadKey : AiError(
        "That key was rejected. Check it in Settings.", false)

    data object RateLimited : AiError(
        "The provider is rate limiting us. Wait a moment and try again.", true)

    data object Offline : AiError(
        "No internet connection.", true)

    data class Server(val detail: String) : AiError(
        "The provider had a problem: $detail", true)

    data class Unknown(val detail: String) : AiError(detail, true)
}
