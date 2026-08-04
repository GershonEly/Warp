package dev.ely.warp.ai

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONObject
import java.util.UUID
import kotlin.random.Random

/**
 * 🎭 A fake AI that behaves like a real one.
 *
 * This is what makes "build the app now, add a key later" work. It implements
 * the same [AiProvider] interface as a real model, streams word by word with
 * believable pauses, and can fake tool calls and errors on request.
 *
 * Everything above this layer — chat UI, streaming, tool cards, error states —
 * is built and tested against this. Swapping in a real provider changes one
 * setting, not any of that code.
 *
 * Its replies are scripted and chosen by keyword. It is not pretending to be
 * clever; it is pretending to be *present*, so the plumbing can be exercised.
 */
class MockProvider(
    /** Fixed seed keeps demo runs reproducible; null for varied timing. */
    seed: Long? = null,
) : AiProvider {

    override val id = "mock"
    override val displayName = "Mock AI"
    override val requiresKey = false

    private val random = seed?.let { Random(it) } ?: Random.Default

    override suspend fun listModels(): Result<List<AiModel>> {
        delay(300)  // pretend to reach the network
        return Result.success(
            listOf(
                AiModel("mock-fast", "Mock · fast", contextTokens = 200_000),
                AiModel("mock-thorough", "Mock · thorough", contextTokens = 200_000),
            )
        )
    }

    override suspend fun testConnection(): Result<String> {
        delay(500)
        return Result.success("Connected to the mock AI · 2 models")
    }

    override fun stream(request: AiRequest): Flow<AiEvent> = flow {
        val prompt = request.messages.lastOrNull { it.role == Role.USER }?.text.orEmpty()

        // Deliberate escape hatches so error states can be tested on purpose,
        // without unplugging the network or breaking a key.
        forcedError(prompt)?.let {
            delay(400)
            emit(AiEvent.Failed(it))
            return@flow
        }

        // Being asked to name a conversation is a different question, and
        // answering it with the standard paragraph made the whole naming path
        // untestable without a paid key: the paragraph is correctly rejected as
        // too long to be a title, so nothing changed — and "nothing changed" is
        // indistinguishable from the feature being broken.
        titleRequest(prompt)?.let { question ->
            delay(300)
            emit(AiEvent.TextDelta(mockTitle(question)))
            emit(AiEvent.Completed())
            return@flow
        }

        // Models take a moment before the first token. Without this the UI
        // never shows its "thinking" state, so it never gets tested.
        delay(if (request.effort == Effort.LOW) 350 else 900)

        // A goal, scripted to take three turns.
        //
        // Three rather than one because the bug worth catching is the loop, not
        // the first turn: whether turn two starts, whether the count climbs, and
        // whether `goal_done` actually ends it. A one-turn goal proves none of
        // those and looks identical to a working one.
        //
        // "never finishes" is a deliberate escape hatch, like "pretend offline"
        // is the only way to reach the turn budget on purpose.
        Regex("""This is turn (\d+) of""").find(request.systemPrompt.orEmpty())?.let { m ->
            val turn = m.groupValues[1].toIntOrNull() ?: 1
            val endless = request.messages.any { "never finishes" in it.text.lowercase() }

            if (turn >= 3 && !endless) {
                emitWords("Checked, and it holds.")
                emit(
                    AiEvent.ToolCallRequested(
                        ToolCall(
                            id = UUID.randomUUID().toString(),
                            name = "goal_done",
                            argumentsJson = JSONObject()
                                .put("how_you_know", "Read it back after writing; it matched.")
                                .toString(),
                        )
                    )
                )
                delay(200)
            } else {
                // Real work happens through tools, so the mock's goal turns do
                // too. Without this the step counter only ticked once, right at
                // the end, and no test could see it move — the same blindness
                // that let the bar sit at "turn 1 of 10" through a whole
                // successful run.
                //
                // Only on the first round of a turn: once a tool has run the
                // provider is called again with the same turn number, and asking
                // for the tool again would spend the round budget in a circle.
                val alreadyWorked = request.messages.lastOrNull { it.role == Role.ASSISTANT }
                    ?.toolCalls?.isNotEmpty() == true

                if (!alreadyWorked) {
                    emitWords("Turn $turn: looking at what is there.")
                    emit(
                        AiEvent.ToolCallRequested(
                            ToolCall(
                                id = UUID.randomUUID().toString(),
                                name = "list_dir",
                                argumentsJson = JSONObject().put("path", ".").toString(),
                            )
                        )
                    )
                    delay(150)
                } else {
                    emitWords("Turn $turn: did the next piece. More to do.")
                    emit(AiEvent.Completed())
                }
            }
            return@flow
        }

        // A grilling, scripted: ask, hear the answer, ask a sharper one, then
        // summarise. Two questions rather than one, because the bug worth
        // catching is the second question never arriving — a single question
        // proves the tool works and proves nothing about the loop.
        if (request.systemPrompt?.contains("INTERROGATE THE PLAN") == true) {
            val answered = request.messages
                .flatMap { it.toolCalls }
                .filter { it.name == "ask" && it.result != null }

            // "grill me hard" keeps asking, so the round budget can be proven.
            // A real model asked eight questions and was cut off with "Stopped
            // after 8 rounds of tool calls"; the mock could only ever ask two,
            // so nothing here could have caught it. A deliberate escape hatch,
            // like "pretend offline".
            val relentless = request.messages.any { "grill me hard" in it.text.lowercase() }
            if (relentless && answered.size < 12) {
                emitWords("Question ${answered.size + 1}.")
                askAbout(
                    question = "Decision number ${answered.size + 1}?",
                    options = listOf("This one", "The other one"),
                    recommended = 0,
                    because = "Asked to keep going until told to stop.",
                )
                return@flow
            }

            when (answered.size) {
                0 -> {
                    emitWords("Before anything gets built, one thing at a time.")
                    askAbout(
                        question = "Where should the app keep its data?",
                        options = listOf("On the phone only", "Sync to a server", "Both, later"),
                        recommended = 0,
                        because = "Nothing here needs an account yet, and on-device is reversible.",
                    )
                }

                1 -> {
                    emitWords("Good. That settles the next one.")
                    askAbout(
                        question = "What happens to data if the app is uninstalled?",
                        options = listOf("It goes with it", "Export first, always"),
                        recommended = 1,
                        because = "You chose on-device, so uninstalling is the only way to lose it.",
                    )
                }

                else -> {
                    emitWords("Decided:")
                    emit(AiEvent.TextDelta(BREAK + answered.joinToString(BREAK) {
                        "- " + it.result
                    }))
                    emit(AiEvent.Completed())
                }
            }
            return@flow
        }

        // A tool has just run: say what it found and stop.
        //
        // **After the grilling branch**, and that ordering is load-bearing: this
        // fires on any finished tool call, so above it, it swallowed every answer
        // and the second question never came. The grilling looked like it worked
        // and stopped after one question.
        //
        // Not politeness — this is the only way to prove the result got back to
        // the provider at all. Without it the mock re-runs its script every
        // round, calls the same tool eight times, and hits the round budget; the
        // loop looked broken when what was broken was a mock that never listened.
        toolResults(request)?.let { found ->
            emitWords("Here is what came back:")
            emit(AiEvent.TextDelta("\n\n```\n$found\n```"))
            emit(AiEvent.Completed())
            return@flow
        }

        // Answers from what it was actually given, not from the prompt text.
        // It is the only way to prove /plan reached the provider at all — and
        // it names the tools it can see, which is how "the write tools were
        // taken away" becomes something a test can read rather than believe.
        if (request.systemPrompt?.contains("PLAN only") == true) {
            emitWords("Planning only. I have not been given anything that writes.")
            emit(AiEvent.TextDelta(
                BREAK + "Tools I can use: " + request.tools.joinToString(", ") { it.name }
            ))
            emit(AiEvent.Completed())
            return@flow
        }

        // Same idea for rules, but **only when asked** — a deliberate probe, like
        // "pretend bad key" above.
        //
        // It used to fire whenever any rule existed, which quietly replaced every
        // other scripted answer: the moment a rule was added, the tool scripts
        // stopped running and seventeen unrelated checks failed. A mock that
        // changes behaviour as a side effect of unrelated state is a mock that
        // tests the wrong thing.
        if ("what were you told" in prompt.lowercase()) {
            RULE_MARK.find(request.systemPrompt.orEmpty())?.let {
                emitWords("I have been told:")
                emit(AiEvent.TextDelta(BREAK + it.value))
                emit(AiEvent.Completed())
                return@flow
            }
        }

        val script = scriptFor(prompt)

        for ((index, segment) in script.segments.withIndex()) {
            when (segment) {
                is Segment.Text -> {
                    // A blank line between text that sits either side of a tool
                    // call. emitWords drops the trailing space of its last word,
                    // so without this the two ran together as "the file
                    // now.Done." — and even with the space they would read as
                    // one paragraph rather than as before and after.
                    if (index > 0) emit(AiEvent.TextDelta("\n\n"))
                    emitWords(segment.text)
                }

                is Segment.Tool -> {
                    // The request only. What happens next is not the mock's to
                    // say — the runner executes it and reports back, the same as
                    // it would for a real model.
                    val id = UUID.randomUUID().toString()
                    emit(
                        AiEvent.ToolCallRequested(
                            ToolCall(
                                id = id,
                                name = segment.name,
                                argumentsJson = segment.argumentsJson,
                            )
                        )
                    )
                    delay(200)
                }
            }
        }

        emit(AiEvent.Completed())
    }

    /** Put one question, and leave it open. The desk answers it, not the mock. */
    private suspend fun kotlinx.coroutines.flow.FlowCollector<AiEvent>.askAbout(
        question: String,
        options: List<String>,
        recommended: Int,
        because: String,
    ) {
        emit(
            AiEvent.ToolCallRequested(
                ToolCall(
                    id = UUID.randomUUID().toString(),
                    name = "ask",
                    argumentsJson = JSONObject()
                        .put("question", question)
                        .put("options", org.json.JSONArray(options))
                        .put("recommended", recommended)
                        .put("because", because)
                        .toString(),
                )
            )
        )
    }

    /** Emit text a word at a time, with pauses that feel like typing. */
    private suspend fun kotlinx.coroutines.flow.FlowCollector<AiEvent>.emitWords(text: String) {
        val words = text.split(" ")
        for ((index, word) in words.withIndex()) {
            val chunk = if (index == words.lastIndex) word else "$word "
            emit(AiEvent.TextDelta(chunk))
            // Longer pause after sentence ends reads more naturally than a
            // constant tick.
            val pause = when {
                word.endsWith(".") || word.endsWith(":") -> random.nextInt(90, 180)
                word.endsWith(",") -> random.nextInt(50, 100)
                else -> random.nextInt(18, 55)
            }
            delay(pause.toLong())
        }
    }

    private fun forcedError(prompt: String): AiError? {
        val p = prompt.lowercase()
        return when {
            "pretend bad key" in p || "fake bad key" in p -> AiError.BadKey
            "pretend offline" in p -> AiError.Offline
            "pretend rate limit" in p -> AiError.RateLimited
            "pretend error" in p -> AiError.Server("mock failure, on request")
            else -> null
        }
    }

    /**
     * A scripted answer containing real markdown.
     *
     * Without this the renderer could only be checked by hand against a paid
     * model, which is the kind of test nobody runs twice. Anything that can only
     * be verified by spending money gets verified once.
     */
    private fun markdownScript() = """
        Here is a counter, in **Compose**.

        ## The composable

        ```kotlin
        @Composable
        fun Counter() {
            var count by remember { mutableStateOf(0) }
            Button(onClick = { count++ }) {
                Text("Tapped ${'$'}count times")
            }
        }
        ```

        Three things to notice:

        - `remember` keeps the value across recompositions
        - `by` unwraps the state, so you read `count` and not `count.value`
        - the lambda is the whole button, not just its label

        ---

        Call it from `setContent`, and it is a working app.
    """.trimIndent()

    /**
     * What the tools found last turn, or null if the last turn used none.
     *
     * Only the final assistant message is examined. Anything earlier has already
     * been answered, and treating an old result as new is how a mock ends up
     * replying to a question from four turns ago.
     */
    private fun toolResults(request: AiRequest): String? {
        val last = request.messages.lastOrNull { it.role == Role.ASSISTANT } ?: return null
        if (last.toolCalls.isEmpty()) return null
        return last.toolCalls.joinToString("\n\n") { call ->
            val outcome = call.forModel?.takeIf { it.isNotBlank() } ?: "no output"
            "${call.name} → $outcome"
        }
    }

    // ── naming a conversation ────────────────────────────────────────────

    /**
     * The question inside a naming request, or null if this is not one.
     *
     * Detected from the prompt rather than from a flag on the request, because a
     * flag would be a field every provider carried in order to describe
     * something only this one needs to notice. The prompt is what a real model
     * gets; the mock reading the same thing keeps them honest.
     */
    private fun titleRequest(prompt: String): String? {
        if ("name this conversation" !in prompt.lowercase()) return null
        return prompt.substringAfter("Question:", "").substringBefore("Answer so far:").trim()
    }

    /**
     * A short, plausible name.
     *
     * Rules, not intelligence — this is a mock, and pretending otherwise would
     * make it a worse test rather than a better one. Four words of the question,
     * with the leading politeness stripped, is close enough in shape to what a
     * real model returns that the code path either works for both or neither.
     */
    private fun mockTitle(question: String): String {
        val cleaned = question
            .replace(Regex("^(please|can you|could you|how do i|how to|i want to|make me)\\s+", RegexOption.IGNORE_CASE), "")
            .trim()
            .trimEnd('?', '.', '!')

        val words = cleaned.split(Regex("\\s+")).filter { it.isNotBlank() }.take(4)
        if (words.isEmpty()) return "New conversation"

        return words.joinToString(" ").replaceFirstChar { it.uppercase() }
    }

    // ── the scripts ──────────────────────────────────────────────────────

    private sealed interface Segment {
        data class Text(val text: String) : Segment
        /**
         * A tool the mock asks for. It carries no result, and cannot.
         *
         * Deliberately has nowhere to put one: the executor fills the card in
         * from what actually happened. A field for a scripted outcome is a field
         * that will eventually be used to make a broken tool look fine, which is
         * exactly what "wrote 34 lines" was doing while nothing was written.
         */
        data class Tool(
            val name: String,
            val argumentsJson: String,
        ) : Segment
    }

    private class Script(val segments: List<Segment>)

    private fun scriptFor(prompt: String): Script {
        val p = prompt.lowercase()

        return when {
            p.isBlank() -> Script(listOf(Segment.Text(GREETING)))

            "hello" in p || "hi" in p || "היי" in p || "שלום" in p ->
                Script(listOf(Segment.Text(GREETING)))

            // Ahead of the "app" branch, which would otherwise swallow it —
            // "show me code" contains neither, but "counter" and "compose" both
            // sit near words that do.
            "code" in p || "counter" in p || "compose" in p || "markdown" in p ->
                Script(listOf(Segment.Text(markdownScript())))

            "build" in p || "compile" in p || "apk" in p ->
                Script(listOf(Segment.Text(BUILD_ANSWER)))

            // Asks for a tool that now really runs, against the real project
            // folder. It used to claim "wrote 34 lines" while nothing happened —
            // a scripted result is indistinguishable from a working one, which
            // is precisely why the tool cards were a picture for so long.
            "read" in p || "look" in p || "files" in p || "project" in p ->
                Script(
                    listOf(
                        Segment.Text("Let me look at what is here."),
                        Segment.Tool(
                            name = "list_dir",
                            argumentsJson = """{"path":"."}""",
                        ),
                        Segment.Text("That is the project as it stands."),
                    )
                )

            // Asks for a real write, and really has to ask you first.
            //
            // This branch used to report "wrote 34 lines" while no file was
            // touched. It carries no result now — it cannot — so what the card
            // says is whatever actually happened on disk, including you saying
            // no to it.
            "app" in p || "create" in p || "make" in p || "write" in p ->
                Script(
                    listOf(
                        Segment.Text(CREATE_INTRO),
                        Segment.Tool(
                            name = "write_file",
                            argumentsJson = JSONObject()
                                .put("path", "src/Counter.kt")
                                .put("content", COUNTER_SOURCE)
                                .toString(),
                        ),
                    )
                )

            "who are you" in p || "what are you" in p ->
                Script(listOf(Segment.Text(IDENTITY)))

            else -> Script(listOf(Segment.Text(FALLBACK)))
        }
    }

    private companion object {
        const val GREETING =
            "Hello. I'm the mock AI standing in until a real key is added. " +
                "I don't actually think — I replay scripted answers — but every " +
                "part of the app around me is real: streaming, tool calls, and " +
                "error handling all work exactly as they will with a live model."

        const val IDENTITY =
            "I'm a placeholder. Warp's AI layer is built against an interface, " +
                "and I'm the implementation that needs no API key. Swap the " +
                "provider in Settings and the same chat talks to a real model."

        const val BUILD_ANSWER =
            "Warp compiles on the phone itself. The pipeline is aapt2 for " +
                "resources, javac for the generated R class, kotlinc for your " +
                "Kotlin, d8 to make dex, then zipalign and signing. On a Redmi " +
                "Note 13 Pro+ a small app takes about 18 seconds once the " +
                "Kotlin runtime is cached."

        const val CREATE_INTRO =
            "I'll write a small counter — one screen, a number, and a button " +
                "that increases it. It is your project, so I have to ask before " +
                "I touch it."

        /** Real Kotlin, because a real file gets written and then read back. */
        val COUNTER_SOURCE = """
            package app

            fun main() {
                var count = 0
                repeat(3) { count++ }
                println("Tapped ${'$'}count times")
            }
        """.trimIndent()

        /** Whatever the rules block turned into, so a test can read it back. */
        val RULE_MARK = Regex("""\d+\. .+""")

        /** A blank line, written once rather than escaped at each use. */
        val BREAK = System.lineSeparator() + System.lineSeparator()

        const val FALLBACK =
            "I'm the mock AI, so my answers are scripted rather than thought " +
                "through. Try asking me to make an app, or about how building " +
                "works. You can also type \"pretend bad key\" or \"pretend " +
                "offline\" to see how Warp handles those failures."
    }
}
