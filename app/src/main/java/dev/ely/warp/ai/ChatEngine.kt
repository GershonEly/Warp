package dev.ely.warp.ai

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Holds one conversation and drives the provider.
 *
 * Deliberately knows nothing about which AI is behind [provider] — that is the
 * whole point of the harness. It is also the only place that mutates the
 * message list, so the UI can just render state.
 */
class ChatEngine(
    private val scope: CoroutineScope,
    @Volatile var provider: AiProvider,
    private val systemPrompt: String? = DEFAULT_SYSTEM_PROMPT,
) {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private var turn: Job? = null

    /** Model id to use. Set from the model picker. */
    @Volatile
    var model: String = "mock-fast"

    @Volatile
    var effort: Effort = Effort.LOW

    /** Send a user message and stream the reply. */
    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _busy.value) return

        val userMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = Role.USER,
            text = trimmed,
        )
        val replyId = UUID.randomUUID().toString()
        _messages.value = _messages.value + userMessage + ChatMessage(
            id = replyId,
            role = Role.ASSISTANT,
            text = "",
            streaming = true,
        )
        _busy.value = true

        turn = scope.launch {
            try {
                runTurn(replyId)
            } catch (e: CancellationException) {
                // Stopped by the user: keep whatever text already arrived
                // rather than discarding a half-finished answer.
                finish(replyId) { it.copy(streaming = false) }
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "turn failed", e)
                finish(replyId) {
                    it.copy(
                        streaming = false,
                        error = AiError.Unknown("${e.javaClass.simpleName}: ${e.message}"),
                    )
                }
            } finally {
                _busy.value = false
            }
        }
    }

    private suspend fun runTurn(replyId: String) {
        val request = AiRequest(
            model = model,
            // The placeholder reply is excluded: sending an empty assistant
            // turn back to a provider is meaningless and some reject it.
            messages = _messages.value.filter { it.id != replyId },
            systemPrompt = systemPrompt,
            effort = effort,
        )

        provider.stream(request).collect { event ->
            when (event) {
                is AiEvent.TextDelta -> update(replyId) { it.copy(text = it.text + event.text) }

                is AiEvent.ToolCallRequested -> update(replyId) {
                    it.copy(toolCalls = it.toolCalls + event.call)
                }

                is AiEvent.Completed -> update(replyId) { it.copy(streaming = false) }

                is AiEvent.Failed -> update(replyId) {
                    it.copy(streaming = false, error = event.error)
                }
            }
        }
    }

    /** Stop the current reply where it is. */
    fun stop() {
        turn?.cancel()
        turn = null
        _busy.value = false
    }

    fun clear() {
        stop()
        _messages.value = emptyList()
    }

    private fun update(id: String, change: (ChatMessage) -> ChatMessage) {
        _messages.value = _messages.value.map { if (it.id == id) change(it) else it }
    }

    private fun finish(id: String, change: (ChatMessage) -> ChatMessage) = update(id, change)

    companion object {
        private const val TAG = "WarpChat"

        val DEFAULT_SYSTEM_PROMPT = """
            You are Warp, an AI coding assistant that runs entirely on an Android phone.
            You can create and edit Android projects in Kotlin, and Warp compiles them
            on the device itself — no computer and no cloud build.
            Keep answers short and concrete. Prefer doing over explaining.
        """.trimIndent()
    }
}
