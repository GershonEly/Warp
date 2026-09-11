package dev.ely.warp.tools

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * The steps the model says it is going to take — §5j.
 *
 * A `/goal` run used to show the turn it was on, which says how long it has been
 * going and nothing about what it is doing. This is the other question.
 *
 * **The list is data, declared by a tool.** The cheaper design was to let the
 * model write `- [ ]` lines in its prose and have the renderer recognise them,
 * and it was rejected twice over: a list made of text is a list the app has to
 * guess at, so the day the model writes `1. [DONE]` instead the card silently
 * stops appearing — which is how every serious bug in this project has failed so
 * far. And a parsed list cannot be *checked against*, which is the whole reason
 * §6 wants one.
 */

/**
 * One step.
 *
 * @param note one line of evidence, set when it is ticked. Optional, and the
 *   same argument as `goal_done`'s `how_you_know`: a tick with a reason under it
 *   is a claim you can judge, and a bare tick is one you have to trust.
 */
data class WarpTask(
    val text: String,
    val done: Boolean = false,
    val note: String? = null,
)

/**
 * Where the list lives while a conversation is open.
 *
 * The same shape as [QuestionDesk] and [PermissionDesk] — something outside the
 * tools package owns it, the tools reach it through [ToolEnv], and the screen
 * watches it. Tools stay ignorant of storage and the engine stays ignorant of
 * what a step means.
 */
class TaskBoard {

    private val _tasks = MutableStateFlow<List<WarpTask>>(emptyList())

    /** What the screen draws. */
    val tasks: StateFlow<List<WarpTask>> = _tasks.asStateFlow()

    /**
     * Called when **the model** changes the list, so it can be kept.
     *
     * On the board rather than at the place a tool happens to be run from, and
     * that is the whole point. Saving was first wired into the engine's round
     * loop, which meant the promise "the list is saved whenever it changes" was
     * really "saved when it changes *there*" — and the first run of the suite
     * caught it, because the debug surface runs a tool without a turn around it.
     *
     * [load] and [clear] deliberately do not fire it. Neither is a change worth
     * recording, and firing on `clear` is precisely how an empty list would be
     * written over the steps of the chat you just left.
     */
    @Volatile
    var onChanged: ((List<WarpTask>) -> Unit)? = null

    /** The list right now, for a caller that is not composing. */
    fun state(): List<WarpTask> = _tasks.value

    /** The steps still owed. What `goal_done` gets measured against. */
    fun open(): List<WarpTask> = _tasks.value.filterNot { it.done }

    /**
     * Replace the list, **keeping what has already been ticked**.
     *
     * Replace rather than append, because re-stating the plan is how a model
     * corrects itself, and merging two lists needs identity matching that the
     * model would have to get right every single time.
     *
     * Carrying the ticks over is the part that is not obvious and is not
     * optional. Without it, a model adding a fourth step at the end wipes three
     * ticks it had earned, and the screen then lies about work that really
     * happened — which is worse than showing no list at all.
     *
     * Matched on the step's text, each old entry consumed once, so a list that
     * repeats a step does not have one tick counted twice.
     */
    fun set(texts: List<String>): List<WarpTask> {
        val before = _tasks.value.toMutableList()
        val after = texts.map { text ->
            val found = before.indexOfFirst { it.text == text }
            if (found >= 0) before.removeAt(found) else WarpTask(text)
        }
        _tasks.value = after
        onChanged?.invoke(after)
        return after
    }

    /**
     * Tick one step, **exactly as asked**.
     *
     * If step 5 is marked done while step 2 is still open, that is what the list
     * says afterwards. It does not helpfully tick 2 through 4.
     *
     * It may look wrong, and that is the point: it is a true picture of a model
     * that skipped ahead, which is a thing worth being able to see. A checklist
     * that tidies itself up cannot tell you the work was done out of order — or
     * not done at all.
     *
     * @return the step as it now stands, or null if there is no such step.
     */
    fun done(index: Int, note: String? = null): WarpTask? {
        val list = _tasks.value
        val task = list.getOrNull(index) ?: return null
        // An existing note survives a tick that brings none, so a second call on
        // the same step cannot quietly erase the evidence the first one gave.
        val ticked = task.copy(done = true, note = note ?: task.note)
        _tasks.value = list.toMutableList().also { it[index] = ticked }
        onChanged?.invoke(_tasks.value)
        return ticked
    }

    /** Put back what was stored. Not a model action — see §5j on persistence. */
    fun load(tasks: List<WarpTask>) {
        _tasks.value = tasks
    }

    /** A new chat has no steps in it. */
    fun clear() {
        _tasks.value = emptyList()
    }
}

// ── storage ──────────────────────────────────────────────────────────────
//
// JSON in one column rather than a table of its own, following the same
// reasoning already written down for attachments and tool calls: the list is
// only ever read back with its conversation and never queried across, and a
// join for something never queried is a table maintained for nothing.
//
// The `tool_grants` table argues the opposite way for permissions, and the
// difference is exactly that — grants are asked about by name, one at a time.

/** The list, as one string to keep. */
fun List<WarpTask>.toJson(): String = JSONArray().also { array ->
    forEach { task ->
        array.put(
            JSONObject()
                .put("text", task.text)
                .put("done", task.done)
                .also { if (task.note != null) it.put("note", task.note) }
        )
    }
}.toString()

/**
 * Read a stored list back.
 *
 * Anything unreadable becomes an empty list rather than an exception. A row that
 * cannot be parsed should cost you a checklist, never the conversation it is
 * attached to.
 */
fun tasksFromJson(json: String?): List<WarpTask> {
    if (json.isNullOrBlank()) return emptyList()
    val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
    return (0 until array.length()).mapNotNull { i ->
        val row = array.optJSONObject(i) ?: return@mapNotNull null
        val text = row.optString("text").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        WarpTask(
            text = text,
            done = row.optBoolean("done"),
            note = row.optString("note").takeIf { it.isNotBlank() },
        )
    }
}

// ── the tools ────────────────────────────────────────────────────────────

/**
 * Enough steps to be a plan, few enough to be a list.
 *
 * A cap rather than trust, for the same reason every other budget here exists.
 * Forty is far above any real job — the longest by hand was nine — and it is
 * here so that a model looping on `set_tasks` cannot fill the conversation's row
 * with a thousand steps nobody will read.
 */
private const val MAX_TASKS = 40

/**
 * Say what the steps are.
 *
 * `FREE`, like `goal_done`: it touches nothing on disk and cannot cost anything.
 * A permission prompt guarding a list of intentions is a prompt people learn to
 * tap past without reading, which is what makes the expensive ones invisible.
 */
object SetTasks : Tool {
    override val name = "set_tasks"
    override val risk = Risk.FREE
    override val description =
        "State the steps you are going to take, in order, before you start. " +
            "Call this again to correct the plan — it replaces the list and keeps " +
            "the steps already marked done. Use it whenever the job has more than " +
            "one step, so the person can see what is left."
    override val schemaJson = """
        {"type":"object","properties":{
          "tasks":{"type":"array","items":{"type":"string"},
                   "description":"The steps, in order. One short line each."}},
         "required":["tasks"]}
    """.trimIndent()

    override fun describe(args: JSONObject): String {
        val count = args.optJSONArray("tasks")?.length() ?: 0
        return if (count == 1) "1 step" else "$count steps"
    }

    override suspend fun run(env: ToolEnv, args: JSONObject): ToolResult {
        val board = env.tasks
            ?: return ToolResult.Failed("there is no task list here")

        val array = args.optJSONArray("tasks")
            ?: return ToolResult.Failed("expected tasks: a list of steps")

        val texts = (0 until array.length())
            .mapNotNull { array.optString(it).trim().takeIf(String::isNotBlank) }

        if (texts.isEmpty()) return ToolResult.Failed("a list with no steps in it")
        if (texts.size > MAX_TASKS) {
            return ToolResult.Failed(
                "${texts.size} steps is too many — $MAX_TASKS at most. Group them."
            )
        }

        val after = board.set(texts)
        val kept = after.count { it.done }

        // Says what it did, not that it was called - and the body is the list
        // itself, so the model is holding the same numbering the person sees.
        return ToolResult.Ok(
            summary = when {
                kept > 0 -> "${after.size} steps, $kept already done"
                after.size == 1 -> "1 step"
                else -> "${after.size} steps"
            },
            body = after.mapIndexed { i, task ->
                "$i. ${if (task.done) "[x]" else "[ ]"} ${task.text}"
            }.joinToString("\n"),
        )
    }
}

/**
 * Tick one step off.
 *
 * Numbered from zero, matching the numbering `set_tasks` hands back, because two
 * numbering schemes for one list is a mistake waiting to be made every call.
 */
object TaskDone : Tool {
    override val name = "task_done"
    override val risk = Risk.FREE
    override val description =
        "Mark one step from set_tasks as finished, the moment it actually is. " +
            "Give the step's number, counting from 0. Say in one line how you " +
            "know it is done."
    override val schemaJson = """
        {"type":"object","properties":{
          "task":{"type":"integer","description":"The step's number, from 0."},
          "note":{"type":"string",
                  "description":"One line: what you checked, and what it said."}},
         "required":["task"]}
    """.trimIndent()

    override fun describe(args: JSONObject): String =
        args.optString("note").takeIf { it.isNotBlank() }
            ?: "step ${args.optInt("task", 0)}"

    override suspend fun run(env: ToolEnv, args: JSONObject): ToolResult {
        val board = env.tasks
            ?: return ToolResult.Failed("there is no task list here")

        val list = board.state()
        if (list.isEmpty()) {
            return ToolResult.Failed("there is no list yet — call set_tasks first")
        }

        // `has` rather than a sentinel, because 0 is a real step number and
        // optInt's default would silently tick the first one.
        if (!args.has("task")) {
            return ToolResult.Failed("expected task: the step's number, from 0")
        }
        val index = args.optInt("task", -1)

        val ticked = board.done(index, args.optString("note").takeIf { it.isNotBlank() })
            ?: return ToolResult.Failed(
                "there is no step $index — the list has ${list.size}, numbered from 0"
            )

        val left = board.open().size
        return ToolResult.Ok(
            summary = when {
                left == 0 -> "${ticked.text} — every step done"
                left == 1 -> "${ticked.text} — 1 step left"
                else -> "${ticked.text} — $left steps left"
            },
            body = ticked.note,
        )
    }
}
