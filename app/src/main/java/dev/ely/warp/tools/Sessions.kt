package dev.ely.warp.tools

import dev.ely.warp.ai.ChatMessage
import dev.ely.warp.ai.Role
import dev.ely.warp.ai.ToolCall
import org.json.JSONObject

/**
 * Reading its own past — §5p.
 *
 * He asked whether Warp could work out *why a session went badly*, the way §5o
 * was written. It can, and §5o is the proof: the answer was not in anybody's
 * memory, it was in the transcript on the phone. 115 edits into one file, a
 * median of three lines each, nothing under `res/`, forty failures in two
 * bursts. None of that is visible by scrolling; all of it is one count away.
 *
 * **The shape, never the transcript.** Three hundred and eighty-three messages
 * is the most expensive thing in this app to hand to a model, and almost all of
 * it is prose the model would have to read to count things a loop can count.
 * What comes back is what a person cannot see: totals, failures and their
 * reasons, and which files were touched how often.
 */
interface ReadsSessions {
    /** Conversations, newest first, as (id, title). */
    suspend fun list(limit: Int): List<Pair<String, String>>

    /** Every message of one conversation, or empty if there is no such chat. */
    suspend fun messages(conversationId: String): List<ChatMessage>
}

/** Enough to see a pattern, few enough that the answer stays readable. */
private const val TOP_FILES = 12
private const val LISTED = 20

object PastSessions : Tool {
    override val name = "past_session"
    override val risk = Risk.FREE
    override val description =
        "Look at what happened in an earlier chat: how many messages, which " +
            "files were written and how often, which tool calls failed and " +
            "why, and what it cost. Call with no id to list recent chats. " +
            "Use it when asked why something went badly, or before repeating " +
            "work you may already have done."
    override val schemaJson = """
        {"type":"object","properties":{
          "id":{"type":"string",
                "description":"The conversation to look at. Omit to list them."}},
         "required":[]}
    """.trimIndent()

    override fun describe(args: JSONObject): String =
        args.optString("id").takeIf { it.isNotBlank() }?.let { "chat ${it.take(8)}" }
            ?: "recent chats"

    override suspend fun run(env: ToolEnv, args: JSONObject): ToolResult {
        val sessions = env.sessions
            ?: return ToolResult.Failed("there is no history to read here")

        val id = args.optString("id").takeIf { it.isNotBlank() }
            ?: return listChats(sessions)

        val messages = sessions.messages(id)
        if (messages.isEmpty()) {
            // Told apart on purpose: an id that does not exist is a different
            // problem from a chat nobody said anything in, and answering
            // "nothing happened" to both sends you looking in the wrong place.
            return ToolResult.Failed(
                "no chat with id $id — call this with no id to see the list"
            )
        }
        return ToolResult.Ok(shape(messages), detail(messages))
    }

    private suspend fun listChats(sessions: ReadsSessions): ToolResult {
        val rows = sessions.list(LISTED)
        if (rows.isEmpty()) return ToolResult.Ok("no earlier chats", null)
        return ToolResult.Ok(
            "${rows.size} recent chat${if (rows.size == 1) "" else "s"}",
            rows.joinToString("\n") { (id, title) -> "$id  $title" },
        )
    }

    private fun shape(messages: List<ChatMessage>): String {
        val calls = messages.flatMap { it.toolCalls }
        val failed = calls.count { it.status != ToolCall.Status.DONE }
        return "${messages.size} messages · ${calls.size} tool calls · $failed did not finish"
    }

    /**
     * The counts, which is the part scrolling cannot give you.
     *
     * Ordered by what answered the real question first: what was written, then
     * what failed and why, then the errors the turns themselves carried.
     */
    private fun detail(messages: List<ChatMessage>): String {
        val calls = messages.flatMap { it.toolCalls }
        val out = StringBuilder()

        val edits = calls.filter { it.name == "write_file" || it.name == "edit_file" }
            .mapNotNull { call ->
                runCatching {
                    JSONObject(call.argumentsJson.ifBlank { "{}" }).optString("path")
                }.getOrNull()?.takeIf { it.isNotBlank() }
            }
            .groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }

        if (edits.isNotEmpty()) {
            out.append("files written, most edited first\n")
            edits.take(TOP_FILES).forEach { (path, n) -> out.append("  $n  $path\n") }
            if (edits.size > TOP_FILES) out.append("  …and ${edits.size - TOP_FILES} more\n")
            out.append('\n')
        }

        val byTool = calls.groupingBy { it.name }.eachCount()
            .entries.sortedByDescending { it.value }
        out.append("tools used\n")
        byTool.forEach { (name, n) -> out.append("  $n  $name\n") }

        val failures = calls.filter { it.status != ToolCall.Status.DONE }
        if (failures.isNotEmpty()) {
            out.append("\nwhat failed, and what it said\n")
            failures.groupBy { "${it.name}: ${(it.result ?: "").take(70)}" }
                .entries.sortedByDescending { it.value.size }
                .take(TOP_FILES)
                .forEach { (what, list) -> out.append("  ${list.size}×  $what\n") }
        }

        // The turn-level failures, which are not tool failures and are the ones
        // that made a session feel broken rather than slow — §5o.
        val errors = messages.mapNotNull { it.error?.message }
            .groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }
        if (errors.isNotEmpty()) {
            out.append("\nturns that failed\n")
            errors.take(TOP_FILES).forEach { (msg, n) -> out.append("  $n×  ${msg.take(90)}\n") }
        }

        val spoke = messages.count { it.role == Role.USER && !it.byApp }
        out.append("\nyou sent $spoke message${if (spoke == 1) "" else "s"}")

        val cost = messages.mapNotNull { it.usage }.fold(null as Double?) { acc, u ->
            if (u.costUsd == null) acc else (acc ?: 0.0) + u.costUsd
        }
        if (cost != null) out.append(" · reported cost $${"%.4f".format(cost)}")

        return out.toString().trim()
    }
}
