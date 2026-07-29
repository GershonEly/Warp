package dev.ely.warp.ai

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    /** Where messages are kept. Null keeps the engine purely in memory. */
    private val store: ConversationStore? = null,
    /** Who gives a conversation its name. Null leaves them unnamed. */
    private val titler: Titler? = null,
) {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private var turn: Job? = null

    /**
     * Which conversation is being written to, or null before the first message.
     *
     * A flow rather than a plain field because the drawer highlights the open
     * conversation, and a conversation can start without anyone tapping a row —
     * see [ensureConversation].
     */
    private val _conversationId = MutableStateFlow<String?>(null)
    val conversationId: StateFlow<String?> = _conversationId.asStateFlow()

    /** Last time the in-flight reply was written to disk. */
    private var lastPersist = 0L

    /**
     * Point the engine at a conversation and load it.
     *
     * The messages are replaced wholesale rather than merged: a conversation is
     * a transcript, and half of one loaded over the tail of another is the kind
     * of bug that only shows up as someone's words appearing in the wrong chat.
     */
    fun open(id: String, loaded: List<ChatMessage>) {
        stop()
        _conversationId.value = id
        _messages.value = loaded
    }

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
        val reply = ChatMessage(
            id = replyId,
            role = Role.ASSISTANT,
            text = "",
            streaming = true,
        )
        _messages.value = _messages.value + userMessage + reply
        _busy.value = true

        turn = scope.launch {
            try {
                // Before the request goes out, not after it comes back. The
                // question cost someone thought to type, and it must survive the
                // process being killed even if the answer does not — losing your
                // own words is far worse than losing a reply, which can always
                // be asked for again.
                ensureConversation(trimmed)
                persistNow(userMessage.id)

                runTurn(replyId)
                refineTitle(replyId)
            } catch (e: CancellationException) {
                // Stopped by the user: keep whatever text already arrived
                // rather than discarding a half-finished answer.
                finish(replyId) { it.copy(streaming = false) }
                // A cancelled coroutine throws at its next suspension point, and
                // a database write is one — so without this exemption, stopping
                // a reply would be the exact case where the partial text fails
                // to be saved.
                withContext(NonCancellable) { persistNow(replyId) }
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "turn failed", e)
                finish(replyId) {
                    it.copy(
                        streaming = false,
                        error = AiError.Unknown("${e.javaClass.simpleName}: ${e.message}"),
                    )
                }
                persistNow(replyId)
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

                is AiEvent.ToolCallUpdated -> update(replyId) { message ->
                    message.copy(
                        toolCalls = message.toolCalls.map { call ->
                            if (call.id == event.id) {
                                call.copy(
                                    status = event.status,
                                    // A later event carrying no result must not
                                    // erase one an earlier event already set.
                                    result = event.result ?: call.result,
                                )
                            } else {
                                call
                            }
                        },
                    )
                }

                is AiEvent.Completed -> update(replyId) { it.copy(streaming = false) }

                is AiEvent.Failed -> update(replyId) {
                    it.copy(streaming = false, error = event.error)
                }
            }

            // Written as it streams, not only at the end. A long reply that is
            // lost because the phone killed the process at minute three is a
            // real loss; writing every token would be sixty small transactions
            // a second, which is not. Once a second is the compromise.
            val now = System.currentTimeMillis()
            if (now - lastPersist > PERSIST_EVERY_MS) {
                lastPersist = now
                persistNow(replyId)
            }
        }

        // Whatever the throttle skipped.
        persistNow(replyId)
    }

    /** Stop the current reply where it is. */
    fun stop() {
        turn?.cancel()
        turn = null
        _busy.value = false
    }

    /**
     * Start a fresh chat.
     *
     * Drops the conversation id as well as the messages, so the next thing said
     * opens a new conversation rather than appending to the one just left.
     */
    fun clear() {
        stop()
        _conversationId.value = null
        _messages.value = emptyList()
    }

    private fun update(id: String, change: (ChatMessage) -> ChatMessage) {
        _messages.value = _messages.value.map { if (it.id == id) change(it) else it }
    }

    // ── persistence ──────────────────────────────────────────────────────

    /**
     * Make sure there is a conversation to write into, and return its id.
     *
     * Created on the first message rather than when someone taps New chat, so
     * opening a chat and changing your mind leaves nothing behind. An empty row
     * in the drawer is a promise the app did not keep.
     */
    private suspend fun ensureConversation(question: String): String? {
        val target = store ?: return null
        _conversationId.value?.let { return it }

        val id = target.create()
        _conversationId.value = id
        // Named here, from the question alone, before a single token of the
        // answer exists. The drawer must never show a row with no name on it,
        // and waiting for a model to supply one would mean exactly that for as
        // long as the model took — including for ever, when naming is off.
        titler?.nameNow(id, question)
        awaitingTitle = question
        return id
    }

    /**
     * The first question in a conversation, until its reply has landed.
     *
     * Held rather than recomputed because "the first exchange" stops being
     * identifiable the moment there is a second one, and the refinement runs
     * after the reply — by which time the transcript no longer says which
     * exchange was the first.
     */
    private var awaitingTitle: String? = null

    /**
     * Write one message as it currently stands.
     *
     * Reads the message out of state rather than taking it as an argument, so a
     * reply that is still streaming is always written at its latest length
     * instead of at whatever length it was when the write was scheduled.
     */
    private suspend fun persistNow(messageId: String) {
        val id = _conversationId.value ?: return
        val target = store ?: return
        val message = _messages.value.firstOrNull { it.id == messageId } ?: return
        target.save(id, message)
    }

    /**
     * Ask for a better name, once, after the first reply has landed.
     *
     * Skipped when the reply failed. A name derived from an answer that never
     * arrived would be a name derived from nothing, and the one taken from the
     * question is already the better of the two.
     */
    private suspend fun refineTitle(replyId: String) {
        val question = awaitingTitle ?: return
        awaitingTitle = null

        val id = _conversationId.value ?: return
        val reply = _messages.value.firstOrNull { it.id == replyId } ?: return
        if (reply.error != null) return

        titler?.refine(id, question, reply.text)
    }

    /**
     * What the engine needs from storage.
     *
     * An interface rather than the repository itself, so the engine keeps
     * knowing nothing about Room — the same reason it knows nothing about which
     * AI is behind [provider]. Note there is no clock in it: when something
     * happened is the store's business, not the engine's.
     */
    interface ConversationStore {
        /** Make an empty conversation and return its id. */
        suspend fun create(): String

        suspend fun save(conversationId: String, message: ChatMessage)
    }

    /**
     * Who names a conversation.
     *
     * Two calls, not one, because they answer different questions. [nameNow]
     * must be instant and cannot fail — it is what stops an unnamed row ever
     * appearing. [refine] is allowed to be slow, to cost money, and to decline
     * entirely; whether it does any of those is a setting, and the engine is
     * deliberately not told which.
     */
    interface Titler {
        /** Name it from the question alone. Called before the answer exists. */
        suspend fun nameNow(conversationId: String, question: String)

        /** Improve on that name, if the setting allows it. May do nothing. */
        suspend fun refine(conversationId: String, question: String, answer: String)
    }

    private fun finish(id: String, change: (ChatMessage) -> ChatMessage) = update(id, change)

    companion object {
        private const val TAG = "WarpChat"

        /** How often a streaming reply is written to disk. */
        private const val PERSIST_EVERY_MS = 1_000L

        val DEFAULT_SYSTEM_PROMPT = """
            You are Warp, an AI coding assistant that runs entirely on an Android phone.
            You can create and edit Android projects in Kotlin, and Warp compiles them
            on the device itself — no computer and no cloud build.
            Keep answers short and concrete. Prefer doing over explaining.
        """.trimIndent()
    }
}
