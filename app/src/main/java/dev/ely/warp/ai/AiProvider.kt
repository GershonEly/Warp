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
    LOW("Fast"),
    HIGH("Thorough"),
    MAX("Maximum"),
}

data class AiModel(
    val id: String,
    val displayName: String,
    val contextTokens: Int? = null,
    val supportsTools: Boolean = true,
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
