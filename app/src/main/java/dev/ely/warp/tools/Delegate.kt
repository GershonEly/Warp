package dev.ely.warp.tools

import org.json.JSONObject
import java.io.File

/**
 * Sending a helper, and the fence it works inside — §5c.
 *
 * §5 said only that Warp spawns subagents for parallel work, and that is not a
 * specification: *an agent given a task and no boundary is the thing that does
 * what you told it not to.* The danger is not that a helper is weak. It is that
 * it never saw your rules — it was handed a few files, not the conversation.
 *
 * So the fence is built out of what the helper is *given*, never out of what it
 * is told. It holds one job, a named handful of files, and a step budget, and
 * the only tool in its hand reads. There is no writing tool to talk it out of
 * using. As §5c puts it: it cannot wander because there is nowhere to wander to.
 *
 * That is also where the saving comes from. A helper that reads three files and
 * returns two sentences costs three files and two sentences — on a phone, where
 * tokens are the user's own money, that is the whole point.
 */

/**
 * One errand, fully described before it starts.
 *
 * Every field is required by a rule in §5c, in order: the window (1), the job
 * and the shape of its answer (2), and the budget (4). Rule 3 — no authority to
 * act — is not a field because it is not a setting: nothing here can grant it.
 * Rule 5 — no memory — is why this is a value handed in rather than a session
 * held open.
 */
data class SubTask(
    /** The one question, not a heading. "Why does the build fail?" */
    val job: String,
    /** Project-relative paths. The entire world the helper can see. */
    val window: List<String>,
    /** What to come back with. "The cause and the line number." */
    val returns: String,
    /** Reads allowed before it must stop and report what it has. */
    val maxSteps: Int,
)

/** What came back, and how much it cost to get it. */
data class SubResult(
    val report: String,
    val steps: Int,
    /** True when it ran out of budget rather than finishing. Never hidden. */
    val stoppedAtBudget: Boolean,
    /** Which model answered, so the card can say. */
    val model: String,
)

/**
 * Who can actually run one.
 *
 * An interface here rather than a provider call, because this package does not
 * know what an AI is and should not learn — the same boundary that keeps
 * [ToolRunner] ignorant of which model is behind a request.
 */
interface RunsSubagents {
    suspend fun run(task: SubTask, project: File): SubResult
}

/**
 * Three files, not thirty.
 *
 * A cap rather than a guideline, because "narrow window" enforced by asking
 * nicely is a window that widens the first time a model is in a hurry. §5c says
 * *the three files it needs*; five leaves room to be wrong once.
 */
private const val MAX_WINDOW = 5

/** Reads before it must report. Small on purpose — see §5c rule 4. */
private const val DEFAULT_STEPS = 4
private const val MAX_STEPS = 8

object Delegate : Tool {
    override val name = "delegate"

    /**
     * RUNS, because it leaves the device and is charged to the person using it.
     *
     * Not FREE despite changing nothing: FREE means "never asks", and a tool
     * that quietly spends money every time a model feels like a second opinion
     * is exactly the thing that should stop and ask the first time.
     */
    override val risk = Risk.RUNS

    override val description =
        "Send a helper to read a few named files and report one thing back. " +
            "Give it one question and say what to return. The helper can only " +
            "read the files you list — it cannot write, edit, build, install, " +
            "or see anything else — and it answers to you, not to the person. " +
            "Use it to find something out; decide and act yourself."

    override val schemaJson = """
        {"type":"object","properties":{
          "job":{"type":"string","description":"The one question to answer. Not a heading."},
          "files":{"type":"array","items":{"type":"string"},
                   "description":"Up to $MAX_WINDOW project-relative paths. All the helper will see."},
          "returns":{"type":"string","description":"The shape of the answer, e.g. 'the cause and the line number'."},
          "max_steps":{"type":"integer","description":"Reads allowed before it must report. Default $DEFAULT_STEPS, most $MAX_STEPS."}},
         "required":["job","files","returns"]}
    """.trimIndent()

    // Says the job and the window, because those are the two things worth
    // knowing before you allow it. The file count alone would hide "all of
    // them", and the job alone would hide what it is about to read.
    override fun describe(args: JSONObject): String {
        val job = args.optString("job").take(60)
        val files = args.optJSONArray("files")
        val n = files?.length() ?: 0
        return "$job · $n file${if (n == 1) "" else "s"}"
    }

    override suspend fun run(env: ToolEnv, args: JSONObject): ToolResult {
        // The window and the shape are checked first, before asking whether
        // there is a helper at all. They are the cheap structural faults, and
        // they are the same faults whether or not anything is available to run
        // — checking availability first made every boundary here unreachable,
        // and a fence nothing can reach is a fence that passes by never firing.
        val job = args.optString("job").takeIf { it.isNotBlank() }
            ?: return ToolResult.Failed("no job given")

        val returns = args.optString("returns").takeIf { it.isNotBlank() }
            // Rule 2 is refused rather than defaulted. A helper with no declared
            // output shape is one asked to "sort out the build", and the answer
            // that comes back is a paragraph nobody can act on.
            ?: return ToolResult.Failed("say what it should return")

        val raw = args.optJSONArray("files")
        val asked = (0 until (raw?.length() ?: 0))
            .mapNotNull { raw?.optString(it)?.takeIf(String::isNotBlank) }
            .distinct()

        if (asked.isEmpty()) return ToolResult.Failed("no files given — a helper with no window sees nothing")
        if (asked.size > MAX_WINDOW) {
            return ToolResult.Failed(
                "$MAX_WINDOW files at most, ${asked.size} asked for — narrow the job instead"
            )
        }

        // Checked here, before anything is spent. A window naming a file that
        // does not exist is a mistake worth catching for free rather than
        // paying a model to discover.
        for (path in asked) {
            val file = resolve(env.project, path)
                ?: return ToolResult.Failed("$path is outside the project")
            if (!file.isFile) return ToolResult.Failed("$path is not a file")
        }

        val steps = args.optInt("max_steps", DEFAULT_STEPS).coerceIn(1, MAX_STEPS)

        val helper = env.subagents
            // Same honesty as "nobody here to answer": the tool exists, the
            // thing behind it does not, and saying so names the real fault
            // rather than blaming the errand.
            ?: return ToolResult.Failed("no helper available")

        val result = runCatching {
            helper.run(SubTask(job, asked, returns, steps), env.project)
        }.getOrElse { return ToolResult.Failed(it.message ?: "the helper failed") }

        if (result.report.isBlank()) {
            return ToolResult.Failed("the helper came back with nothing after ${result.steps} step(s)")
        }

        // The budget being hit is said in the summary, not buried in the body.
        // A partial answer read as a whole one is worse than no answer, and the
        // model reading this card is the one that has to know the difference.
        val budget = if (result.stoppedAtBudget) " · stopped at its budget" else ""
        return ToolResult.Ok(
            "${result.steps} step${if (result.steps == 1) "" else "s"} · " +
                "${asked.size} file${if (asked.size == 1) "" else "s"} · " +
                "${result.model}$budget",
            result.report,
        )
    }
}
