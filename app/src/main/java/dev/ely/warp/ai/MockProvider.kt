package dev.ely.warp.ai

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
                    // The full lifecycle, not just the request. The card is
                    // supposed to show a tool being called and finishing, and it
                    // can only do that if something says it finished.
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
                    delay(400)
                    emit(AiEvent.ToolCallUpdated(id, ToolCall.Status.RUNNING))
                    delay(700)
                    emit(
                        AiEvent.ToolCallUpdated(
                            id = id,
                            status = ToolCall.Status.DONE,
                            result = segment.result,
                        )
                    )
                    delay(200)
                }
            }
        }

        emit(AiEvent.Completed())
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
        data class Tool(
            val name: String,
            val argumentsJson: String,
            val result: String,
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

            // Anything that sounds like "make me an app" shows the tool flow,
            // because that is the interaction the real product is built around.
            "app" in p || "create" in p || "make" in p || "write" in p ->
                Script(
                    listOf(
                        Segment.Text(CREATE_INTRO),
                        Segment.Tool(
                            name = "write_file",
                            argumentsJson =
                                """{"path":"src/MainActivity.kt","summary":"a one-screen counter app"}""",
                            result = "wrote 34 lines",
                        ),
                        Segment.Text(CREATE_OUTRO),
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
            "Sure. I'll write a small counter app — one screen, a number, and " +
                "a button that increases it. Creating the file now."

        const val CREATE_OUTRO =
            "Done. That's the file written. Tap Build to compile it, and Warp " +
                "will produce an installable APK without leaving the phone."

        const val FALLBACK =
            "I'm the mock AI, so my answers are scripted rather than thought " +
                "through. Try asking me to make an app, or about how building " +
                "works. You can also type \"pretend bad key\" or \"pretend " +
                "offline\" to see how Warp handles those failures."
    }
}
