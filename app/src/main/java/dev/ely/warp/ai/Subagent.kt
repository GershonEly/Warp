package dev.ely.warp.ai

import android.content.Context
import android.util.Log
import dev.ely.warp.tools.AsksQuestions
import dev.ely.warp.tools.Question
import dev.ely.warp.tools.READ_TOOLS
import dev.ely.warp.tools.RunsSubagents
import dev.ely.warp.tools.SubResult
import dev.ely.warp.tools.SubTask
import dev.ely.warp.tools.ToolEnv
import dev.ely.warp.tools.ToolResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.util.UUID

private const val TAG = "WarpSubagent"

/**
 * The first line of a helper's directive, and how the mock recognises one.
 *
 * Shared rather than written twice, because the mock has to be able to read
 * files for the window and the budget to be testable at all — and a marker
 * copied into two files is a marker that drifts, leaving a mock that quietly
 * stops recognising helpers and boundary checks that pass by never firing.
 */
internal const val HELPER_MARK = "You are a helper working for another agent"

/**
 * How long the "which model?" question waits before answering itself.
 *
 * Long enough to read and tap, short enough that an unattended `/goal` loses a
 * minute and a half once per chat rather than stopping for ever.
 */
private const val ASK_MS = 90_000L

/**
 * The thing behind [dev.ely.warp.tools.Delegate] — §5c.
 *
 * A helper is a whole conversation that lives and dies inside one tool call. It
 * starts from nothing, is handed one job and a named handful of files, reads,
 * answers, and is thrown away. Rule 5 is not enforced anywhere because there is
 * nothing to enforce: no state outlives this function.
 *
 * The fence is structural rather than written down. The helper is offered
 * exactly one tool — `read_file` — so there is no writing tool to be talked out
 * of using, and every path it asks for is checked against the window it was
 * given before anything is read. Telling a model not to write and then handing
 * it a write tool is a request; not handing it one is a boundary. That is the
 * same distinction `/plan` already turns on.
 */
class SubagentRunner(
    private val context: Context,
    private val provider: () -> AiProvider,
    private val model: () -> String,
    /** Where the "which model?" question goes. Null means don't ask. */
    private val questions: AsksQuestions? = null,
    /** What there is to choose from. Suspends — it may go and look. */
    private val choices: suspend () -> List<ModelChoice> = { emptyList() },
) : RunsSubagents {

    /** Which chat this is, set from outside exactly like the runner's. */
    @Volatile
    var conversation: () -> String? = { null }

    /**
     * The model picked for helpers, per chat.
     *
     * Asked once and then remembered, the same shape as Always on a write
     * permission — and for the same reason. A question on every errand is a
     * question people learn to dismiss without reading, which is how a prompt
     * stops being a decision.
     */
    private val picked = mutableMapOf<String, String>()

    override suspend fun run(task: SubTask, project: File): SubResult {
        val modelId = chooseModel()
        val reader = READ_TOOLS["read_file"]
            ?: return SubResult("read_file is missing", 0, false, modelId)

        val messages = mutableListOf(
            ChatMessage(id = UUID.randomUUID().toString(), role = Role.USER, text = task.job)
        )

        val spec = ToolSpec(reader.name, reader.description, reader.schemaJson)

        var steps = 0
        var report = ""
        var stoppedAtBudget = false

        // Bounded whatever happens. maxSteps caps the reads; this caps the
        // rounds, so a model that answers with neither text nor a tool call
        // cannot keep the loop alive for free.
        repeat(task.maxSteps + 2) {
            currentCoroutineContext().ensureActive()

            // Rule 4 says it stops and *reports what it has*, so the turn after
            // the budget runs out is offered no tools at all. Breaking here
            // instead would throw away everything it had read.
            val mayRead = steps < task.maxSteps

            val request = AiRequest(
                model = modelId,
                messages = messages.toList(),
                systemPrompt = directive(task, mayRead),
                tools = if (mayRead) listOf(spec) else emptyList(),
            )

            val text = StringBuilder()
            val asked = mutableListOf<ToolCall>()
            var failure: AiError? = null

            provider().stream(request).collect { event ->
                when (event) {
                    is AiEvent.TextDelta -> text.append(event.text)
                    is AiEvent.ToolCallRequested -> asked += event.call
                    is AiEvent.Failed -> failure = event.error
                    else -> Unit
                }
            }

            failure?.let { throw IllegalStateException(describe(it)) }

            if (!mayRead || asked.isEmpty()) {
                report = text.toString().trim()
                stoppedAtBudget = !mayRead
                return SubResult(report, steps, stoppedAtBudget, modelId)
            }

            val done = asked.map { call ->
                steps++
                execute(reader, call, task, project)
            }

            messages += ChatMessage(
                id = UUID.randomUUID().toString(),
                role = Role.ASSISTANT,
                text = text.toString(),
                toolCalls = done,
            )
        }

        // Fell out of the round cap without answering. Reported as a budget
        // stop rather than as success with an empty report, because the two
        // are different facts and only one of them is worth acting on.
        return SubResult(report, steps, true, modelId)
    }

    /**
     * Which model answers this errand — asked once per chat, then remembered.
     *
     * A helper mostly reads and summarises, so it is often the one place a
     * smaller model is plainly enough, and on BYOK that difference is the
     * person's own money. It is theirs to decide, so it is put to them rather
     * than guessed.
     *
     * **It never blocks.** `/goal` exists to keep working while nobody is
     * watching, and a question with nobody there would freeze exactly the run
     * the foreground service was built to protect. So the wait is bounded, and
     * running out of it is not a failure — it is the answer "whatever the chat
     * is using", remembered like any other, so it is asked once and never again.
     */
    private suspend fun chooseModel(): String {
        val mine = model()
        val here = conversation() ?: return mine
        picked[here]?.let { return it }

        val desk = questions ?: return keep(here, mine)

        val available = runCatching { choices() }.getOrNull().orEmpty()
            .filter { it.available }
            .distinctBy { it.modelId }

        val current = available.firstOrNull { it.modelId == mine }
        val others = available.filter { it.modelId != mine && it.recommended }.take(3)

        // Nothing to choose between is not a question. Asking anyway would
        // teach people that the prompt means nothing, which is what makes the
        // prompts that matter invisible.
        if (others.isEmpty()) return keep(here, mine)

        val labels = listOf(current?.label ?: mine) + others.map { it.label }

        val answer = withTimeoutOrNull(ASK_MS) {
            desk.ask(
                Question(
                    callId = "helper-model-$here",
                    text = "Which model should the helper use?",
                    options = labels,
                    // A recommendation, not a default — nothing is chosen until
                    // it is chosen. The same one as the chat is the safe answer
                    // for somebody with no opinion.
                    recommended = 0,
                    because = "A helper only reads a few files and reports back, " +
                        "so a smaller model is often enough. It is charged to you either way.",
                )
            )
        } ?: run {
            Log.i(TAG, "nobody answered which model; using $mine for $here")
            return keep(here, mine)
        }

        val chosen = others.firstOrNull { it.label == answer }?.modelId ?: mine
        return keep(here, chosen)
    }

    private fun keep(conversation: String, modelId: String): String {
        picked[conversation] = modelId
        return modelId
    }

    /**
     * One read, refused unless the path is in the window it was given.
     *
     * Checked here rather than trusted to the tool, because the tool's own
     * boundary is the project folder — right for the main agent, far too wide
     * for a helper. Rule 1 says three files, not the project.
     */
    private suspend fun execute(
        reader: dev.ely.warp.tools.Tool,
        call: ToolCall,
        task: SubTask,
        project: File,
    ): ToolCall {
        val args = runCatching { JSONObject(call.argumentsJson.ifBlank { "{}" }) }
            .getOrElse {
                return call.copy(
                    status = ToolCall.Status.FAILED,
                    result = "could not read the arguments",
                )
            }

        val path = args.optString("path")
        if (path.isBlank() || task.window.none { it == path }) {
            Log.i(TAG, "helper asked for $path, outside its window ${task.window}")
            // Told plainly what it may see and what to do instead. §5c: if it
            // needs a fourth file it comes back and asks — so the refusal has
            // to name coming back, or it just tries a fifth path.
            return call.copy(
                status = ToolCall.Status.FAILED,
                result = "not in your window. You may read only: " +
                    "${task.window.joinToString(", ")}. " +
                    "If you need something else, say so in your report.",
            )
        }

        return when (val r = runCatching { reader.run(ToolEnv(project, context), args) }
            .getOrElse { ToolResult.Failed(it.message ?: "read failed") }) {
            is ToolResult.Ok -> call.copy(
                status = ToolCall.Status.DONE,
                result = r.summary,
                body = r.body,
            )

            is ToolResult.Failed -> call.copy(
                status = ToolCall.Status.FAILED,
                result = r.reason,
            )
        }
    }

    /**
     * What the helper is told it is.
     *
     * Short on purpose. Every sentence it does not need is billed on every
     * round, and the boundaries that matter are already enforced by what it
     * holds — this only has to stop it *asking* for what it cannot have.
     */
    private fun directive(task: SubTask, mayRead: Boolean) = buildString {
        appendLine("$HELPER_MARK, not for a person.")
        appendLine("You have exactly one job:")
        appendLine(task.job)
        appendLine()
        appendLine("Return exactly this and nothing more: ${task.returns}")
        appendLine()
        if (mayRead) {
            appendLine("You may read only these files, by exact path:")
            task.window.forEach { appendLine("- $it") }
            appendLine("Nothing else exists for you. If you need another file, do not")
            appendLine("guess at paths — say so in your answer and stop.")
        } else {
            appendLine("Your reading budget is used up. Answer now from what you have read.")
            appendLine("If it was not enough, say what is still missing, in one line.")
        }
        appendLine()
        appendLine("You cannot write, edit, build, install, or ask the person anything.")
        appendLine("You do not decide what happens next. You report, and the agent decides.")
        appendLine("Be brief. No preamble, no offers of further help.")
    }

    private fun describe(error: AiError) = when (error) {
        is AiError.Server -> "the model failed: ${error.detail}"
        else -> "the model failed: $error"
    }
}
