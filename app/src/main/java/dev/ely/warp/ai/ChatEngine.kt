package dev.ely.warp.ai

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.ely.warp.tools.TaskBoard
import dev.ely.warp.tools.WarpTask
import dev.ely.warp.work.Working
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
    /**
     * What to tell the model about itself, asked freshly every turn.
     *
     * A supplier rather than a string, because the rules in §5b Layer 0 are
     * always in context and can change between turns. A prompt captured once
     * would carry the rules as they were when the app started, which is exactly
     * how "it forgot my rule" happens.
     *
     * Given the conversation id, because some of what the model must be told
     * depends on **which app is open** rather than on the app's settings — an
     * XML project and a Compose project want opposite instructions, and §5o is
     * what happens when a model is told about a toolchain it does not have.
     * Null before the first message has created a conversation.
     */
    private val systemPrompt: (String?) -> String? = { DEFAULT_SYSTEM_PROMPT },
    /** Where messages are kept. Null keeps the engine purely in memory. */
    private val store: ConversationStore? = null,
    /** Who gives a conversation its name. Null leaves them unnamed. */
    private val titler: Titler? = null,
    /** What actually performs a tool call. Null shows the card and does nothing. */
    private val tools: ToolExecutor? = null,
    /**
     * Where the steps are kept — §5j. Null means no checklist anywhere.
     *
     * The same board the runner hands to the tools, so the engine can store what
     * they changed and the screen can watch it. Named `board` rather than `tasks`
     * only because [tools] already exists and two names one letter apart in the
     * same constructor is a mistake waiting to happen.
     */
    private val board: TaskBoard? = null,
) {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /**
     * The steps, for the screen — §5j.
     *
     * A permanently empty flow where there is no board, rather than a nullable
     * one. A screen asking "is there a checklist" would have to ask it in every
     * place it draws, and the answer "no steps" already renders as nothing.
     */
    val steps: StateFlow<List<WarpTask>> =
        board?.tasks ?: MutableStateFlow(emptyList<WarpTask>()).asStateFlow()

    init {
        // Kept whenever the model changes it, wherever that happened — §5j.
        //
        // This hung off the round loop first, so the list was only written when
        // a task tool ran inside a turn. The first run of the suite caught it:
        // the debug surface runs a tool without a turn around it, the board
        // changed, and nothing saved it. A promise about "whenever it changes"
        // has to be wired to the thing that changes.
        board?.onChanged = { scope.launch { persistTasks() } }
    }

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

    /**
     * The conversation a running turn is writing into.
     *
     * The engine shows one conversation, but a turn can outlive the moment you
     * are looking at it. Opening another chat used to call [stop], so switching
     * chats threw away a reply that was already being paid for — the same loss
     * the foreground service was built to prevent, arriving through a different
     * door.
     *
     * So the turn stops reading the screen. It keeps its own copy of its
     * conversation and its own id, writes to the database with that id, and
     * mirrors into the visible list only while that conversation is the one on
     * screen. Leaving is now a display change and nothing more.
     */
    private inner class Sheet(var id: String?, start: List<ChatMessage>) {

        /** Every message here, whether or not anyone is looking at them. */
        var messages: List<ChatMessage> = start
            private set

        /** True while the screen is showing this conversation. */
        var onScreen: Boolean = true

        private fun set(next: List<ChatMessage>) {
            messages = next
            if (onScreen) _messages.value = next
        }

        fun add(message: ChatMessage) = set(messages + message)

        fun update(id: String, change: (ChatMessage) -> ChatMessage) =
            set(messages.map { if (it.id == id) change(it) else it })

        fun find(id: String): ChatMessage? = messages.firstOrNull { it.id == id }
    }

    private var sheet: Sheet? = null

    /**
     * You said something while it was working — §5i item 7.
     *
     * Set by [steer] and read by the round loop. The round already in flight is
     * allowed to finish, because cancelling a write halfway is worse than one
     * more file, and then the turn stops: what you just said is answered next,
     * rather than after everything it was already planning to do.
     */
    /**
     * Turns that have failed back to back in this goal — §5o.
     *
     * Reset by any turn that works, so a goal that fails once an hour runs for
     * ever and a goal failing every two seconds stops.
     */
    private var consecutiveFailures = 0

    @Volatile
    private var steered = false

    /**
     * Held by [steer] and by a turn closing — §5i item 7.
     *
     * One lock over both, because the two things that must not interleave are
     * "is there still a turn to take this" and "there is not any more". Measured
     * before it existed: the mock finished four rounds in 1.5 s, a correction
     * arrived 0.7 s later while `turn.isActive` was still true, and the closing
     * turn then reset the state and discarded it. The message was stored, shown,
     * and never answered — the silent loss this project keeps paying for.
     */
    private val steerLock = Any()

    /** Take the flag, if it is set. Caller must hold [steerLock]. */
    private fun takeSteerLocked(): Boolean {
        val had = steered
        steered = false
        return had
    }

    private fun takeSteer(): Boolean = synchronized(steerLock) { takeSteerLocked() }

    private val _elsewhere = MutableStateFlow(false)

    /**
     * True when a turn is running in a conversation you are not looking at.
     *
     * Worth its own flag because the composer would otherwise sit there looking
     * ready while nothing could be sent — one turn at a time is a real limit,
     * and a limit you cannot see is indistinguishable from a bug.
     */
    val elsewhere: StateFlow<Boolean> = _elsewhere.asStateFlow()

    /** Last time the in-flight reply was written to disk. */
    private var lastPersist = 0L

    /**
     * The message currently being written into.
     *
     * Not the same as the id [send] created, once a turn can span several
     * rounds of tool calls. Stopping or failing has to close whichever message
     * is actually open — closing the first one would leave a later bubble
     * spinning for ever with no way to reach it.
     */
    @Volatile
    private var activeReplyId: String? = null

    /**
     * Which turn is the current one.
     *
     * A cancelled coroutine does not stop where it was cancelled — it stops at
     * its next suspension point, and its `finally` runs after that. So a new
     * turn started immediately after a cancel would have its own `busy` cleared
     * a few milliseconds later by the corpse of the old one, leaving the
     * composer offering Send in the middle of a reply.
     *
     * Every turn takes a number and only tidies up if it is still the one
     * holding it.
     */
    private var turnToken = 0L

    /**
     * Point the engine at a conversation and load it.
     *
     * The messages are replaced wholesale rather than merged: a conversation is
     * a transcript, and half of one loaded over the tail of another is the kind
     * of bug that only shows up as someone's words appearing in the wrong chat.
     */
    fun open(id: String, loaded: List<ChatMessage>, tasks: List<WarpTask> = emptyList()) {
        val running = sheet?.takeIf { turn?.isActive == true }
        _conversationId.value = id

        if (running != null && running.id == id) {
            // Back in a chat that is still working. Show what it has now, not
            // the snapshot the database happened to hold when you left.
            running.onScreen = true
            _messages.value = running.messages
            _busy.value = true
            _elsewhere.value = false
            // The board is deliberately left alone here, for the same reason the
            // messages are: a turn still running has ticked steps since the row
            // was written, and loading the stored list over it would put ticks
            // back that have already been earned.
        } else {
            running?.onScreen = false
            _messages.value = loaded
            _busy.value = false
            _elsewhere.value = running != null
            board?.load(tasks)
        }
    }

    /**
     * Put the conversation back to just before a message was sent — §9f.
     *
     * Not an edit in place. A conversation with a model is a chain: every
     * answer after your message was written knowing that message, so changing
     * one link and keeping the rest is a transcript that never happened and a
     * model answering a question nobody asked.
     *
     * Refuses while a turn is running. Rewinding underneath a reply that is
     * still being written would leave that reply arriving into a conversation
     * it no longer belongs to.
     *
     * @return true when it happened.
     */
    fun rewindTo(messageId: String): Boolean {
        if (_busy.value || turn?.isActive == true) return false

        val all = _messages.value
        val index = all.indexOfFirst { it.id == messageId }
        if (index < 0) return false

        _messages.value = all.take(index)
        sheet = null

        val conversation = _conversationId.value ?: return true
        scope.launch {
            runCatching { store?.deleteFrom(conversation, messageId) }
                .onFailure { Log.w(TAG, "could not rewind $conversation", it) }
        }
        return true
    }

    /**
     * How much the AI changed your files after a message — §9f's confirmation.
     *
     * What is at stake decides what the dialog says. Offering a choice about
     * code when no code was written is a dialog asking somebody to decide
     * something meaningless, and every one of those makes the next real
     * question easier to tap past.
     *
     * Counts calls that **finished**. A write that was refused or failed
     * changed nothing, and counting it would warn about work that does not
     * exist. And it counts [WROTE_FILES] rather than [CHANGED_SOMETHING] — a
     * build is work, but it is not a file you would want back.
     */
    fun changesAfter(messageId: String): Int {
        val all = _messages.value
        val index = all.indexOfFirst { it.id == messageId }
        if (index < 0) return 0
        return all.drop(index).sumOf { message ->
            message.toolCalls.count {
                it.status == ToolCall.Status.DONE && it.name in WROTE_FILES
            }
        }
    }

    /** Model id to use. Set from the model picker. */
    @Volatile
    var model: String = "mock-fast"

    @Volatile
    var effort: Effort = Effort.LOW

    /**
     * The most this model will let one reply be, or null when nobody said.
     *
     * Set from the picker beside [model], because it belongs to the model and
     * changes when the model does. Null keeps the provider's own conservative
     * default — see [AiModel.maxOutputTokens] for why the old fixed 16,000 was
     * the wrong number for anything that thinks before it answers.
     */
    @Volatile
    var maxOutputTokens: Int? = null

    /**
     * How this turn is allowed to behave.
     *
     * A property of the turn rather than of the engine, so it cannot leak into
     * the next message. `/plan` means *this* one plans; the one after it is a
     * normal turn again unless you say otherwise.
     */
    enum class Mode { NORMAL, PLAN, GRILL, GOAL }

    /**
     * A standing instruction to keep working until something is true.
     *
     * Held in memory and **deliberately not persisted**. A goal that survived
     * the app being killed would start working again on its own the next time
     * you opened Warp, without you asking — which is exactly the failure
     * §5d describes. Setting a goal is something you do on purpose, each time.
     *
     * @param turn which turn it is on, from 1. @param limit where it stops.
     */
    data class Goal(
        val condition: String,
        val turn: Int,
        val limit: Int,
        /** Tool calls made so far. What tells working from stuck. */
        val steps: Int = 0,
        /**
         * Out of turns, and waiting to be told to carry on.
         *
         * The first version threw the goal away at the limit, and the user did
         * by hand exactly what this now offers: said "continue", and the work
         * finished. Ending a goal because a counter ran out discards the one
         * thing worth keeping — what it was trying to do.
         */
        val paused: Boolean = false,
    ) {
        /**
         * What the bar says.
         *
         * The step count leads, because the turn number barely moves: a model
         * that reads three files, writes one, builds, fixes and builds again
         * does all of it inside **one** turn, using tool rounds. The first
         * version showed only "turn 1 of 10", which sat still through an entire
         * successful run and told you nothing — which is exactly the failure
         * §5d names, on the feature built to prevent it.
         *
         * The turn budget stays visible because it is the brake, and you should
         * be able to see how much rope is left.
         */
        val label: String get() = when {
            paused -> "paused after $turn turns · tap Continue"
            steps == 0 -> "turn $turn of $limit"
            else -> "$steps step${if (steps == 1) "" else "s"} · turn $turn of $limit"
        }
    }

    private val _goal = MutableStateFlow<Goal?>(null)

    /**
     * The goal, if one is running.
     *
     * Public and observed so the screen can keep it in front of you the whole
     * time. §5d's third failure is *frozen while claiming to work*, and
     * `/goal` is the feature that causes it — it removes the turn boundary,
     * which is the moment you would otherwise have noticed.
     */
    val goal: StateFlow<Goal?> = _goal.asStateFlow()

    private var mode = Mode.NORMAL

    /**
     * Stop working toward the goal, and leave nothing running.
     *
     * §5f is emphatic that this is absolute — the request, the process
     * and the loop. Cancelling the job without clearing the goal would end this
     * turn and start the next one; clearing the goal without cancelling would
     * leave a turn running with nothing on screen saying so. Both, in that
     * order, or it is not a stop.
     */
    fun clearGoal() {
        _goal.value = null
        stop()
    }

    /**
     * Carry on with a paused goal.
     *
     * The turn count restarts rather than continuing, because the budget is a
     * guard against unattended looping and you are, by pressing this, attending.
     */
    fun resumeGoal() {
        val paused = _goal.value?.takeIf { it.paused } ?: return
        _goal.value = paused.copy(turn = 1, paused = false)
        send(CONTINUE_MESSAGE, Mode.GOAL, condition = paused.condition)
    }

    /** Send a user message and stream the reply. */
    /**
     * @param condition the goal itself, when [mode] is GOAL.
     *
     * Separate from [text] because they are different things that happen to
     * arrive together: the message is what you typed, slash command and all, and
     * belongs in the transcript verbatim. The condition is what Warp is working
     * toward, and it goes on screen and into every prompt — with "/goal " on
     * the front of it, the status bar read "Working toward: /goal the file
     * exists", which is not a condition, it is a keystroke log.
     */
    fun send(
        text: String,
        mode: Mode = Mode.NORMAL,
        condition: String? = null,
        /**
         * What was attached to this message — §5h.
         *
         * Enough on its own: a message that is only a screenshot is exactly how
         * somebody shows you a bug, so the empty-text guard below has to let it
         * through.
         */
        attachments: List<Attachment> = emptyList(),
    ) {
        val trimmed = text.trim()
        // `turn` as well as `busy`: busy now describes the visible chat, and a
        // second turn started while another is still running would leave two
        // coroutines writing through one [sheet].
        if ((trimmed.isEmpty() && attachments.isEmpty()) ||
            _busy.value || turn?.isActive == true
        ) return
        this.mode = mode
        steered = false
        // A new turn you asked for is a fresh start, whatever the last one did.
        consecutiveFailures = 0
        // Said once, here, because this is the only place that knows a turn is
        // beginning. A refusal you gave in the last turn does not follow you
        // into this one: you have just said something new.
        tools?.startTurn()
        if (mode == Mode.GOAL) {
            _goal.value = Goal(
                condition = condition?.trim()?.ifBlank { null } ?: trimmed,
                turn = 1,
                limit = MAX_GOAL_TURNS,
            )
        }

        val userMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = Role.USER,
            text = trimmed,
            attachments = attachments,
        )
        val replyId = UUID.randomUUID().toString()
        val reply = ChatMessage(
            id = replyId,
            role = Role.ASSISTANT,
            text = "",
            streaming = true,
        )
        sheet = Sheet(_conversationId.value, _messages.value + userMessage + reply)
            .also { _messages.value = it.messages }
        _elsewhere.value = false
        _busy.value = true
        activeReplyId = replyId
        val token = ++turnToken

        // Announced before the work starts, so the service is up before the
        // phone has any reason to reclaim the process.
        Working.started(if (mode == Mode.GOAL) "Working toward your goal" else "Thinking")

        turn = scope.launch {
            try {
                // Before the request goes out, not after it comes back. The
                // question cost someone thought to type, and it must survive the
                // process being killed even if the answer does not — losing your
                // own words is far worse than losing a reply, which can always
                // be asked for again.
                ensureConversation(trimmed)
                persistNow(userMessage.id)

                runGoal(replyId)
                answerCorrections()
                refineTitle(replyId)
            } catch (e: CancellationException) {
                // Stopped by the user: keep whatever text already arrived
                // rather than discarding a half-finished answer.
                val open = activeReplyId ?: replyId
                finish(open) { it.copy(streaming = false) }
                // A cancelled coroutine throws at its next suspension point, and
                // a database write is one — so without this exemption, stopping
                // a reply would be the exact case where the partial text fails
                // to be saved.
                withContext(NonCancellable) { persistNow(open) }
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "turn failed", e)
                val open = activeReplyId ?: replyId
                finish(open) {
                    it.copy(
                        streaming = false,
                        error = AiError.Unknown("${e.javaClass.simpleName}: ${e.message}"),
                    )
                }
                persistNow(open)
            } finally {
                // Only if nothing has started since. See [turnToken].
                if (token == turnToken) {
                    // Closing and the last look at the letterbox happen together,
                    // under the lock [steer] takes — §5i item 7. Apart, a
                    // correction could land after the check and before the reset,
                    // and be thrown away with the state: stored, shown, and never
                    // answered. That window was real and measured, at about half
                    // a second on the mock.
                    val late = synchronized(steerLock) {
                        _busy.value = false
                        _elsewhere.value = false
                        sheet = null
                        activeReplyId = null
                        takeSteerLocked()
                    }
                    // Same guard: a turn that has been superseded must not tell
                    // the service the newer one has finished.
                    Working.finished()

                    // Outside the lock, and as its own turn: this one is over.
                    if (late) scope.launch { answerLate() }
                }
            }
        }
    }

    /**
     * Answer whatever you said while it was working — §5i item 7.
     *
     * A loop rather than one pass, because you can correct a correction, and a
     * reply that arrives after the last thing you said is answering the wrong
     * question. Bounded, like everything here that can repeat.
     */
    private suspend fun answerCorrections() {
        var corrections = 0
        while (takeSteer() && corrections++ < MAX_STEERS) {
            val answer = ChatMessage(
                id = UUID.randomUUID().toString(),
                role = Role.ASSISTANT,
                text = "",
                streaming = true,
            )
            sheet?.add(answer)
            activeReplyId = answer.id
            runGoal(answer.id)
        }
    }

    /**
     * A correction that arrived as the turn was closing, answered as its own.
     *
     * The turn it was meant for has already gone, so this starts a fresh one for
     * the message that is already in the transcript — no second copy of what you
     * typed, which is why this exists rather than a call to [send].
     */
    private fun answerLate() {
        // Deliberately **not** `turn?.isActive`. This is launched from inside
        // the closing turn's own `finally`, so that job is still running and
        // checking it would mean the turn refusing to answer on account of
        // itself — which is exactly what it did, and why the correction still
        // sat there unanswered after the first fix.
        //
        // The sheet is the right question: it was cleared under the lock a
        // moment ago, so a non-null one means a newer turn has already taken
        // over and this correction belongs to it.
        if (_busy.value || sheet != null) return
        val before = _messages.value
        if (before.lastOrNull()?.role != Role.USER) return

        val replyId = UUID.randomUUID().toString()
        sheet = Sheet(
            _conversationId.value,
            before + ChatMessage(
                id = replyId,
                role = Role.ASSISTANT,
                text = "",
                streaming = true,
            ),
        ).also { _messages.value = it.messages }
        _elsewhere.value = false
        _busy.value = true
        activeReplyId = replyId
        val token = ++turnToken
        Working.started("Thinking")

        turn = scope.launch {
            try {
                runGoal(replyId)
                answerCorrections()
            } catch (e: CancellationException) {
                val open = activeReplyId ?: replyId
                finish(open) { it.copy(streaming = false) }
                withContext(NonCancellable) { persistNow(open) }
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "late correction failed", e)
                val open = activeReplyId ?: replyId
                finish(open) {
                    it.copy(
                        streaming = false,
                        error = AiError.Unknown("${e.javaClass.simpleName}: ${e.message}"),
                    )
                }
                persistNow(open)
            } finally {
                if (token == turnToken) {
                    val late = synchronized(steerLock) {
                        _busy.value = false
                        _elsewhere.value = false
                        sheet = null
                        activeReplyId = null
                        takeSteerLocked()
                    }
                    Working.finished()
                    if (late) scope.launch { answerLate() }
                }
            }
        }
    }

    /**
     * One exchange, or many while a goal is running.
     *
     * The turn boundary is where you would normally look at what happened and
     * decide whether to carry on. A goal removes it on purpose, so everything
     * that boundary used to do has to be done explicitly instead: a hard limit,
     * a visible count, and a stated reason whichever way it ends.
     */
    private suspend fun runGoal(firstReplyId: String) {
        var replyId = firstReplyId

        while (true) {
            // The id it *ended* on, which is not the one it started on once a
            // turn has spent rounds looking things up. Checking the first
            // message for goal_done found nothing every time, so the loop ran on
            // after the goal was already met.
            val last = runTurn(replyId)

            val current = _goal.value ?: return

            // Finished, and it had to say how it knows. A goal that ends because
            // the model felt done is a goal that ends whenever the model gets
            // bored; `goal_done` demands the evidence.
            if (goalDoneIn(last)) {
                _goal.value = null
                return
            }
            replyId = last

            // A turn that failed is not a turn that was spent.
            //
            // Two of the user's ten turns were "No internet connection", and a
            // third ended on a permission he refused. The budget exists to stop
            // a model looping unattended; a dropped connection is not looping,
            // and neither is being told no.
            val failure = sheet?.find(last)?.error
            val failed = failure != null
            val spent = if (failed) current.turn else current.turn + 1

            // Free of the budget, but not free of a brake — §5o.
            //
            // The rule above is right and was nearly a disaster on its own. A
            // real run hit "this request would exceed your available credits"
            // and retried **forty times in two bursts, one every half second**,
            // because a failure costs no turn and nothing else slowed it down.
            // A credit limit is not a blip: it stays true until he adds credits,
            // so hammering it could never have worked and every attempt was
            // another row in his transcript.
            if (failed) {
                consecutiveFailures++
                if (consecutiveFailures >= MAX_FAILURES_IN_A_ROW) {
                    update(replyId) {
                        it.copy(
                            streaming = false,
                            // What the provider said, not a guess at what it
                            // meant. "Check your key" for a credit limit sends
                            // somebody to fix something that is not broken.
                            error = AiError.Unknown(
                                "Stopped after $consecutiveFailures failures in a row. " +
                                    "The last one was: ${failure?.message}"
                            ),
                        )
                    }
                    _goal.value = current.copy(paused = true)
                    persistNow(replyId)
                    return
                }
                // Longer each time. The first retry is for a blip; by the third
                // the answer is almost certainly not going to change on its own,
                // and a person is better placed to decide than a loop is.
                delay(RETRY_BACKOFF_MS * consecutiveFailures)
            } else {
                consecutiveFailures = 0
            }

            if (spent > current.limit) {
                // Paused, not ended. The goal survives with a Continue button,
                // because saying "continue" by hand is exactly what worked when
                // this stopped dead on him.
                update(replyId) {
                    it.copy(
                        streaming = false,
                        error = AiError.Unknown(
                            "Paused after ${current.limit} turns. It has not reached: " +
                                "${current.condition}. Tap Continue to carry on."
                        ),
                    )
                }
                _goal.value = current.copy(paused = true)
                persistNow(replyId)
                return
            }

            _goal.value = current.copy(turn = spent)
            // The only progress visible once you have left the app.
            Working.update("Goal \u00b7 turn $spent of ${current.limit}")
            replyId = UUID.randomUUID().toString()
            activeReplyId = replyId
            sheet?.add(
                ChatMessage(
                    id = replyId,
                    role = Role.ASSISTANT,
                    text = "",
                    streaming = true,
                )
            )
        }
    }

    /**
     * Keep going until the model has nothing left to ask for.
     *
     * A model that calls a tool has not finished its answer — it has paused
     * mid-sentence to go and look at something. Stopping there leaves "let me
     * check that file" as the whole reply, which is the shape this had until
     * now: the tool ran, the card filled in, and the model never heard a word
     * of what came back.
     *
     * Each round gets its own message rather than appending to one, because a
     * turn is *look, then say* and squashing several of those into one bubble
     * loses which answer followed which lookup.
     */
    private suspend fun runTurn(firstReplyId: String): String {
        var replyId = firstReplyId
        var round = 0
        var total = 0
        var repeats = 0
        var lastSignature: String? = null

        /** Every round's calls, so a second look at the same thing is visible. */
        val lookedAt = mutableSetOf<String>()

        while (true) {
            val ranTools = streamInto(replyId)
            if (!ranTools) return replyId

            // `goal_done` ends the turn, not just the round. Without this the
            // round loop treated it as any other tool and kept going to its cap,
            // calling goal_done again on every one of them: nine extra turns
            // after the goal was already reached, each one paid for.
            if (goalDoneIn(replyId)) return replyId

            // Your correction ends the rounds — §5i item 7. Steering that waited
            // for the model to finish its plan would not be steering; it would
            // be a message in a queue.
            if (steered) return replyId

            // A round that stopped and waited for a person does not count.
            //
            // The budget exists to stop a model looping while nobody is looking.
            // A question is the opposite of that: you are sitting there, you
            // answered it, and the next question only happened because you did.
            // Counting them meant `/grill-me` — where questions **are** the
            // work — hit the cap after eight and stopped with an error that
            // read like a fault. Observed on a real model: eight questions, then
            // "Stopped after 8 rounds of tool calls".
            val calls = sheet?.find(replyId)?.toolCalls.orEmpty()
            val onlyAsked = calls.isNotEmpty() && calls.all { it.name == "ask" }

            // The steps are not saved from here — the board says when it changed
            // and the save hangs off that, so it happens wherever a tool was run
            // from. Worth saying here, because this is where it used to live.
            //
            // They are also deliberately absent from CHANGED_SOMETHING below:
            // ticking a box is not work that has earned the turn more rounds, and
            // treating it as such would hand a model an endless budget for saying
            // it made progress.

            // And neither does a round that changed something.
            //
            // The same argument, carried where it should have gone the first
            // time. `/grill-me` was fixed and an ordinary chat was not — which
            // is where the work actually happens. Building a call recorder on
            // 2026-08-05 hit this **six times in one session**: 32 edits, 18
            // writes, 6 builds, and the word "continue" typed six times to get
            // an app finished. That is a session going well being charged for
            // the model's good behaviour.
            //
            // A budget cannot tell looping from working by counting, so it stops
            // counting and starts looking at what came back. A write that landed
            // or a build that ran is work; the two are not the same event and
            // only one of them needs a brake.
            val worked = calls.any {
                it.status == ToolCall.Status.DONE && it.name in CHANGED_SOMETHING
            }

            // Looping is the same call coming back again — which is a thing you
            // can see, rather than a thing a counter guesses at. Reading one
            // file eight times is a loop; reading eight files is a morning's
            // work, and the old cap could not tell them apart.
            val signature = calls.joinToString("|") { "${it.name} ${it.argumentsJson}" }
            if (signature.isNotEmpty() && signature == lastSignature) repeats++ else repeats = 0
            lastSignature = signature

            total++

            // Work is free, but not infinite. The ceiling is far above any real
            // turn and exists so a model that writes for ever is still bounded —
            // §5c asks for a hard budget on anything that can call itself, and
            // "unless it is being productive" is not a budget.
            val stop = when {
                repeats >= MAX_REPEATS ->
                    "Stopped: it called the same tool $MAX_REPEATS times in a row " +
                        "with the same arguments, which is a loop rather than progress."
                round >= MAX_TOOL_ROUNDS ->
                    "Stopped after $MAX_TOOL_ROUNDS rounds spent looking at things it had " +
                        "already looked at. Say “continue” to let it keep going."
                total >= MAX_ROUNDS_EVER ->
                    "Stopped after $MAX_ROUNDS_EVER rounds in one turn. " +
                        "Say “continue” to let it keep going."
                else -> null
            }

            if (stop != null) {
                // Inside a goal this is not an error, it is a turn boundary.
                //
                // It fired twice during a real run of eight `edit_file`s and
                // reported "Stopped after 8 rounds of tool calls", which reads
                // as a fault. A turn spending its rounds on edits is working;
                // the goal loop simply takes over and starts the next turn.
                if (_goal.value != null) return replyId

                // Outside a goal there is nothing to take over, so it has to be
                // said out loud. A budget that ends a turn silently is
                // indistinguishable from a model that decided it was done.
                update(replyId) {
                    it.copy(streaming = false, error = AiError.Unknown(stop))
                }
                persistNow(replyId)
                return replyId
            }

            // Looking somewhere new is research. Looking somewhere twice is
            // wandering — §5o.
            //
            // The old rule spent the budget on every round that did not change a
            // file, so eight honest reads ended a turn. It fired three times in
            // a real build where the model was reading and searching its way
            // into a game it had not written yet, and that reading *was* the
            // work.
            //
            // [repeats] above catches the same call twice in a row; this catches
            // the same call again later in the turn, which is the other half of
            // going in circles.
            val seenBefore = signature.isNotEmpty() && !lookedAt.add(signature)
            if (!onlyAsked && !worked && seenBefore) round++
            replyId = UUID.randomUUID().toString()
            activeReplyId = replyId
            sheet?.add(
                ChatMessage(
                    id = replyId,
                    role = Role.ASSISTANT,
                    text = "",
                    streaming = true,
                )
            )
        }
    }

    /** Did this message end with the goal declared reached? */
    private fun goalDoneIn(messageId: String): Boolean =
        sheet?.find(messageId)
            ?.toolCalls
            ?.any { it.name == "goal_done" && it.status == ToolCall.Status.DONE } == true

    /**
     * One request, streamed into one message.
     *
     * @return true when a tool actually ran, which is what tells [runTurn] the
     *   model is owed an answer and the turn is not over.
     */
    private suspend fun streamInto(replyId: String): Boolean {
        var ranTools = false

        val request = AiRequest(
            model = model,
            // The placeholder reply is excluded: sending an empty assistant
            // turn back to a provider is meaningless and some reject it.
            messages = (sheet?.messages ?: _messages.value).filter { it.id != replyId },
            systemPrompt = listOfNotNull(
                systemPrompt(_conversationId.value),
                when (mode) {
                    Mode.PLAN -> PLAN_DIRECTIVE.trim()
                    Mode.GRILL -> GRILL_DIRECTIVE.trim()
                    // Restated every turn, with the count. A model six turns in
                    // has the goal a long way up its context, and drifting off
                    // it is the ordinary way this fails.
                    Mode.GOAL -> _goal.value?.let {
                        goalDirective(it.condition, it.turn, it.limit)
                    }
                    Mode.NORMAL -> null
                },
            ).joinToString(separator = System.lineSeparator() + System.lineSeparator())
                .ifBlank { null },
            effort = effort,
            // Offered every turn. A model that is not told a tool exists will
            // describe reading the file instead of reading it, and be perfectly
            // convincing about it.
            //
            // In PLAN the writing tools are **not offered at all**. Telling a
            // model not to write and then handing it a write tool is a request;
            // taking the tool away is a boundary. A model asked to focus on
            // planning will still helpfully write the file.
            tools = tools?.specs(mode).orEmpty(),
            maxOutputTokens = maxOutputTokens,
        )

        provider.stream(request).collect { event ->
            when (event) {
                is AiEvent.TextDelta -> update(replyId) { it.copy(text = it.text + event.text) }

                is AiEvent.ReasoningDelta ->
                    update(replyId) { it.copy(thinking = it.thinking + event.text) }

                is AiEvent.ToolCallRequested -> {
                    update(replyId) { it.copy(toolCalls = it.toolCalls + event.call) }

                    // Run it here rather than waiting for the provider to say
                    // so. A provider that emits a call has already decided; the
                    // mock has been emitting cards nothing ever executed, and
                    // that gap is what made the tool cards a picture.
                    tools?.let { executor ->
                        val put: suspend (ToolCall) -> Unit = { changed ->
                            update(replyId) { message ->
                                message.copy(
                                    toolCalls = message.toolCalls.map {
                                        if (it.id == changed.id) changed else it
                                    },
                                )
                            }
                        }
                        put(executor.execute(event.call, put))
                        ranTools = true

                        // Every tool call is a visible step while a goal runs.
                        _goal.value?.let { _goal.value = it.copy(steps = it.steps + 1) }
                    }
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

                // The bill arrives with the last chunk — §5p. Added rather than
                // replaced, because one reply can span several requests: a turn
                // that calls three tools pays for four round trips, and showing
                // only the last one would under-report every interesting answer.
                is AiEvent.Completed -> update(replyId) {
                    it.copy(
                        streaming = false,
                        usage = event.usage?.let { u -> it.usage?.plus(u) ?: u } ?: it.usage,
                        // A turn that produced nothing says so, rather than
                        // leaving a blank bubble somebody paid for.
                        text = it.text.ifBlank {
                            emptyTurnNote(
                                event.stopReason, it.thinking, it.toolCalls,
                                interrupted = steered,
                            )
                        },
                    )
                }

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
        return ranTools
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
        // Detached, not stopped. Tapping New chat is saying what you want to
        // look at, not asking to throw away a reply you have already paid for.
        val running = sheet?.takeIf { turn?.isActive == true }
        running?.onScreen = false
        _elsewhere.value = running != null
        _busy.value = false
        _conversationId.value = null
        _messages.value = emptyList()
        // Ordered after the id is dropped on purpose. Nothing writes the board
        // without an id, so clearing it second cannot save an empty list over
        // the steps belonging to the chat just left.
        board?.clear()
    }

    /**
     * Say something while it is still working — §5i item 7.
     *
     * Until now the only way to correct a turn going the wrong way was to stop
     * it and explain the whole thing again from the start. The work already done
     * was thrown away with it, including files that were right.
     *
     * Your words go into the transcript **now**, so they are never lost to a
     * process being killed, and the running turn stops after its current round.
     * What it has already written stays written — see [steered] on why the round
     * in flight is allowed to finish.
     *
     * @return true when it was taken. False when nothing is running, in which
     *   case the caller should send it as an ordinary message instead.
     */
    fun steer(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false

        val message = synchronized(steerLock) {
            // The sheet, not `turn.isActive`, decides. A turn is briefly still
            // "active" while its own cleanup runs, and that gap is exactly where
            // a correction used to disappear. The sheet is cleared inside this
            // same lock, so seeing one means there is a turn that will still look
            // — and seeing none means the caller should simply send it.
            val current = sheet ?: return false
            ChatMessage(
                id = UUID.randomUUID().toString(),
                role = Role.USER,
                text = trimmed,
            ).also {
                current.add(it)
                steered = true
            }
        }

        // Written before anything answers it, for the same reason the first
        // question is: losing your own words is worse than losing a reply.
        scope.launch { runCatching { persistNow(message.id) } }
        return true
    }

    /**
     * What to say when a turn finishes having produced nothing at all.
     *
     * **Silence is the one answer a chat cannot show.** A reply with no text
     * and no tool call used to be saved as an empty bubble: nothing on screen,
     * no error, and the turn still billed. It happened three times in one
     * session, each after roughly fifty-five thousand characters of thinking —
     * a reasoning model spending its whole output budget before it wrote
     * anything, and being cut off mid-thought.
     *
     * The damage was not the wasted turn. It was that the model then reasoned
     * about *why* nothing was happening, decided its tools must be missing, and
     * spent the rest of the conversation telling the person so — having
     * already used those tools successfully earlier in the same chat.
     *
     * A turn that only called a tool is **not** empty. That is the ordinary
     * shape of working, and saying anything there would be noise on every
     * build.
     */
    private fun emptyTurnNote(
        stopReason: String,
        thinking: String,
        toolCalls: List<ToolCall>,
        interrupted: Boolean,
    ): String = when {
        toolCalls.isNotEmpty() -> ""

        // You sent something new while it was thinking, so it dropped what it
        // was doing. Checked **before** the length reasons below: an
        // interrupted turn often has a huge thinking block too, and blaming
        // the model's budget for something the person did would be a confident
        // wrong answer of exactly the kind this file keeps apologising for.
        interrupted -> "Stopped — you sent something new."

        // The provider said outright that it ran out of room.
        stopReason.contains("length", ignoreCase = true) ||
            stopReason.contains("max_tokens", ignoreCase = true) ->
            "The model ran out of room before it answered — it used the whole " +
                "reply on thinking. Ask for something smaller, or pick a model " +
                "with more room."

        // It did not say, but a turn that thought this hard and said nothing
        // ran out of room whatever it chose to call the reason.
        thinking.length > 4_000 ->
            "The model thought for a long time and then produced nothing. That " +
                "usually means it used the whole reply on thinking. Ask for " +
                "something smaller, or pick a model with more room."

        else ->
            "The model returned an empty reply. Nothing was changed. Try again, " +
                "or say it differently."
    }

    private fun update(id: String, change: (ChatMessage) -> ChatMessage) {
        // A running turn owns its messages; anything else edits what is shown.
        sheet?.let { return it.update(id, change) }
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
        val current = sheet
        (current?.id ?: _conversationId.value)?.let { return it }

        val id = target.create()
        current?.id = id
        // Only if you are still here. Naming the screen's conversation after a
        // question asked in a different one is how transcripts get crossed.
        if (current == null || current.onScreen) _conversationId.value = id
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
        val current = sheet
        val id = current?.id ?: _conversationId.value ?: return
        val target = store ?: return
        val message = current?.find(messageId)
            ?: _messages.value.firstOrNull { it.id == messageId }
            ?: return
        target.save(id, message)
    }

    /**
     * Write the steps as they now stand — §5j.
     *
     * Called when a task tool has run rather than when the turn ends, because a
     * turn on this phone is not guaranteed to end: MIUI kills the app, and steps
     * ticked an hour ago should not depend on the turn surviving to save them.
     */
    private suspend fun persistTasks() {
        val current = board ?: return
        val id = sheet?.id ?: _conversationId.value ?: return
        val target = store ?: return
        target.saveTasks(id, current.state())
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

        val id = sheet?.id ?: _conversationId.value ?: return
        val reply = sheet?.find(replyId)
            ?: _messages.value.firstOrNull { it.id == replyId }
            ?: return
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

        /**
         * Throw away a message and everything after it — §9f's rewind.
         *
         * By id rather than by timestamp: two messages written in the same
         * millisecond are ordinary during streaming, and a rewind that removed
         * one of them and kept the other would leave a question with no answer
         * or an answer with no question.
         */
        suspend fun deleteFrom(conversationId: String, messageId: String)

        /**
         * Keep the steps — §5j.
         *
         * Takes the whole list rather than a change, because the list is small
         * and the alternative is a second place that has to agree with the board
         * about what changed. Serialising it is the store's business, the same
         * way turning a [ChatMessage] into a row is.
         */
        suspend fun saveTasks(conversationId: String, tasks: List<WarpTask>)
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
    /**
     * What the engine needs from the tools.
     *
     * An interface for the same reason the store and the titler are: the engine
     * moves messages and must not learn what a file is. It also means the whole
     * chat still works with tools absent, which is what it did until today.
     */
    interface ToolExecutor {
        /**
         * What to offer the model, for the mode this turn is running in.
         *
         * The mode goes across rather than a list of flags, because which tools
         * a mode implies is a product decision and belongs with the tools. The
         * engine knows a turn is a plan or a grilling; it does not know, and
         * must not learn, that planning means no `write_file`.
         */
        fun specs(mode: Mode): List<ToolSpec>

        /**
         * A new turn is starting.
         *
         * The engine knows where a turn begins and nothing else does, so it says
         * so — without learning what anyone does with the fact. What sits behind
         * this today is that a tool you refused is not asked about again until
         * you say something new, and "until you say something new" is exactly
         * this boundary.
         *
         * Default empty, so an executor that does not care stays a one-method
         * interface.
         */
        fun startTurn() {}

        /**
         * Do it, and return the call with its outcome filled in.
         *
         * @param report progress, so a call that stops to ask a person can say
         *   so. The engine passes a lambda that updates the message; it still
         *   does not learn what a tool is, only that one changed state.
         */
        suspend fun execute(call: ToolCall, report: suspend (ToolCall) -> Unit): ToolCall
    }

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

        /**
         * How many times a turn may look at something it has **already seen**.
         *
         * Narrowed twice, each time by a real session. First it counted every
         * round, and cut a 108-message build into six pieces — §5g. Then it
         * counted every round that changed no file, and stopped three turns of a
         * real game build where reading and searching *was* the work — §5o.
         *
         * What is left is the thing it was always trying to catch: going back to
         * the same file, the same search, the same page. Reading eight different
         * files is a morning's work; reading one file eight times is a circle.
         */
        private const val MAX_TOOL_ROUNDS = 8

        /**
         * The same call, with the same arguments, this many times over.
         *
         * The thing the budget was always trying to catch, now caught by name.
         * Three rather than two: a model that reads a file, writes it, and reads
         * it back to check has a legitimate reason to repeat itself once.
         */
        private const val MAX_REPEATS = 3

        /**
         * Rounds in one turn, whatever they were doing.
         *
         * Work is free but not infinite. Far above any real turn — the longest
         * observed was well under twenty — and here only so that "unless it is
         * being productive" does not quietly become "for ever". §5c asks for a
         * hard budget on anything that can call itself, and this is it.
         */
        private const val MAX_ROUNDS_EVER = 60

        /**
         * Tool names that mean the turn is working rather than looking.
         *
         * Named here, in the file that pays for it, rather than asked of the
         * tool — the engine deliberately knows nothing about what a tool *is*,
         * and it already makes the same exception for `ask` and `goal_done` by
         * name. Adding a tool that changes something means adding it here; the
         * cost of forgetting is a turn cut short, not a broken feature.
         */
        private val CHANGED_SOMETHING = setOf(
            "write_file", "edit_file", "new_project", "build", "install", "launch",
        )

        /**
         * Tool names that put something on disk.
         *
         * Narrower than [CHANGED_SOMETHING], and the difference is the point.
         * That set answers "is this turn working", where building and
         * installing both count. This one answers "what would I lose", and a
         * build writes nothing you wrote — counting it would have the rewind
         * dialog warn about files nobody touched, which is the one thing §9f
         * says that dialog must never do.
         */
        private val WROTE_FILES = setOf("write_file", "edit_file", "new_project")

        /**
         * How many turns a goal gets before it must stop and say so.
         *
         * The point is not the number, it is that there is one. An agent that
         * decides for itself when to stop is an agent that spends until it is
         * satisfied — and Warp is BYOK, so that is your money.
         */
        /**
         * How many turns a goal gets before it pauses and asks.
         *
         * Twenty-five rather than ten, and it pauses rather than ending. Ten was
         * chosen from nothing; a real run building a game needed far more, and
         * the user finished the same work by hand in nine further exchanges
         * after the goal gave up. The number still exists because BYOK means
         * this is his money, but it should be reached rarely and never quietly.
         */
        private const val MAX_GOAL_TURNS = 25

        /**
         * How many corrections one turn will answer before it stops — §5i item 7.
         *
         * Far above any real exchange, and here for the same reason every other
         * budget is: correcting a correction is ordinary, and a turn that can
         * always be extended by one more message is a turn with no end.
         */
        private const val MAX_STEERS = 12

        /**
         * How many turns may fail in a row before a goal stops and says so.
         *
         * Three, because the first failure is a blip worth retrying and the
         * third is a condition. Measured before this existed: forty failures in
         * two bursts against a credit limit that was never going to clear on its
         * own — see §5o.
         */
        private const val MAX_FAILURES_IN_A_ROW = 3

        /**
         * The wait after a failed turn, multiplied by how many have failed.
         *
         * Two seconds, then four, then it stops. The old behaviour was a retry
         * every half second, which cannot help a rate limit and cannot help a
         * spent balance — the two things most likely to be failing.
         */
        private const val RETRY_BACKOFF_MS = 2_000L

        /** What a resumed goal says, so the model knows nothing else changed. */
        const val CONTINUE_MESSAGE = "Continue with the goal. Do not start again."

        /**
         * What Warp is, on every single message.
         *
         * The paragraph about scope is not padding. Asked for a 1v1 arena game,
         * the model repeatedly told the user the app needed bigger servers —
         * and then built the lobby, three fighters, abilities, rounds and cover
         * anyway. One true limit (online multiplayer needs a backend, and Warp
         * has none) had been generalised into the whole project being blocked.
         *
         * Guidance, not a switch: a prompt makes that much less likely and
         * cannot make it impossible. It is here rather than in the goal
         * directive because he saw it in ordinary conversation too.
         */
        val DEFAULT_SYSTEM_PROMPT = """
            You are Warp, an AI coding assistant that runs entirely on an Android phone.
            You can create and edit Android projects in Kotlin, and Warp compiles them
            on the device itself — no computer and no cloud build.
            Keep answers short and concrete. Prefer doing over explaining.

            About what you can build: everything runs on this one phone. There is no
            server, no backend and no account system, and there never will be for an
            app you build here. If part of a request needs one — online multiplayer,
            leaderboards, cloud saves, sign-in — build absolutely everything else and
            name only that one feature as out of scope, once. Never say the app needs
            bigger or better servers, never describe the whole project as blocked
            because one feature would need a backend, and never repeat the limitation
            after you have stated it. Offline versions of those features — a local
            two-player mode, an on-device high-score table, a bot opponent — are
            usually what the person actually wants, so offer one and carry on.

            What you can actually do here, so you never have to guess:

            You have tools for files — creating a project, writing, editing,
            reading, listing, searching. You have build, install, launch and
            logcat, so you can compile an app, put it on the phone, open it and
            read its crash log. You have git. You can generate an app icon.
            Web search is not a tool you call — results are added to your
            context automatically when it is switched on, so never say you
            cannot look something up because there is no search tool.

            3D works. OpenGL ES is part of the Android framework, so
            GLSurfaceView and GLES20 compile here with nothing extra. What does
            not exist is a 3D *engine* — no Unity, no Unreal, no scene graph
            library — and no way to download models, textures, audio files or
            fonts. Assets have to be generated in code. That is a real limit and
            it is worth saying once; "there is no 3D" is not true and should
            never be said.

            **If a tool is not in your list, say what you cannot do — but never
            announce that a tool is missing when you have not tried it.** A call
            that fails tells you something true; a guess about your own
            capabilities does not, and being wrong about it in either direction
            wastes the person's time.

            Decide, do not survey. The person is asking for an app, not for a
            series of technical questions. Make the ordinary choices yourself —
            which toolkit, which layout, which colours, how to store data, what
            to name a file — the way a good developer would, and get on with
            building. Never ask someone to choose between two things they have
            no way to compare; if you would have to explain both options before
            they could answer, that is a decision you should be making. Say what
            you chose in one short line and keep going.

            Ask only when it genuinely changes what gets built and you cannot
            reasonably guess: what the app is for, who uses it, a rule of a game,
            whether to delete something of theirs. Those are theirs. The rest is
            yours.

            Write whole files. When you are building something new, write the
            file you mean to write in one go rather than growing it through a
            long run of small edits — a screen assembled from twenty one-line
            edits costs twenty round trips, and it is how a file ends up
            inconsistent with itself. Save edit_file for fixing something inside
            a file that is already right.

            Say what is missing. If a request needs something this toolchain
            does not have — a library, Compose, a backend — name that one thing
            plainly, once, and build everything around it. Working around a hole
            in silence is what makes an assistant look like it does not know
            what it is doing.

        """.trimIndent()
        // The Brain's summary is deliberately *not* part of this constant. It
        // depends on which project is open — an XML one and a Compose one want
        // opposite instructions — so it is appended per turn by whoever builds
        // the prompt, from AndroidBrain.summaryFor(compose).
    }
}
