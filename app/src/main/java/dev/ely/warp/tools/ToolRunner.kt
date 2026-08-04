package dev.ely.warp.tools

import android.content.Context
import android.util.Log
import dev.ely.warp.ai.ToolCall
import dev.ely.warp.build.Projects
import org.json.JSONObject
import java.io.File

/**
 * Runs what the model asked for, and says what happened.
 *
 * The chat engine knows a tool call arrived; it does not know what a tool *is*.
 * That boundary is the same one that keeps it ignorant of which AI is behind the
 * provider — the engine moves messages, and this decides what a request means.
 *
 * **Nothing here decides whether it is allowed.** It asks [permission] and obeys
 * the answer. A runner that could grant itself permission would be a runner that
 * eventually does, and the day it did, nothing on screen would look different.
 */
class ToolRunner(
    private val context: Context,
    private val permission: AsksPermission? = null,
    private val questions: AsksQuestions? = null,
    /**
     * Who can send a helper — see §5c and [Delegate].
     *
     * Set from outside like the two desks above, and for the same reason: a
     * helper is a model call, and nothing in this package is allowed to know
     * what a model is.
     */
    private val subagents: RunsSubagents? = null,
) {

    /**
     * Which chat is open. Set once the engine exists, like the permission desk.
     *
     * A property rather than a constructor argument because the runner has to
     * exist before the engine does, and the engine is what knows.
     */
    @Volatile
    var conversation: () -> String? = { null }

    /**
     * Where the model is allowed to work **for the chat it is working in**.
     *
     * Resolved per call, not once. A single shared folder meant asking for a
     * second app silently overwrote the first, and it meant a tool permission
     * granted in one chat protected nothing in another — same files. Every
     * tool still resolves against this and refuses to leave it, so the boundary
     * is a property of the path rather than a rule each tool must remember.
     */
    val project: File get() = Projects.forConversation(context, conversation())

    /**
     * Run a call and turn it into the card the chat will show.
     *
     * @param report called as the call changes state, so the card can say
     *   *waiting for you* while a person is being asked rather than *running*
     *   while nothing runs. Without it the one moment the card exists for — the
     *   pause where you decide — is the moment it describes wrongly.
     */
    suspend fun run(call: ToolCall, report: suspend (ToolCall) -> Unit = {}): ToolCall {
        val tool = ALL_TOOLS[call.name]
            ?: return call.copy(
                status = ToolCall.Status.FAILED,
                // Names the tool it does not have. "Unknown tool" tells you
                // nothing you can act on; the name tells you the model invented
                // one, which is a different problem with a different fix.
                result = "Warp has no tool called ${call.name}",
            )

        val args = runCatching { JSONObject(call.argumentsJson.ifBlank { "{}" }) }
            .getOrElse {
                return call.copy(
                    status = ToolCall.Status.FAILED,
                    result = "could not read the arguments: ${it.message}",
                )
            }

        // A question is not run, it is put to somebody. Handled before the
        // permission gate because there is nothing here to permit: it changes
        // nothing, and the answer *is* the result.
        if (tool.risk == Risk.ASKS) {
            val asker = questions
                ?: return call.copy(
                    status = ToolCall.Status.FAILED,
                    result = "nobody here to answer",
                )

            val options = args.optJSONArray("options")
            val question = Question(
                callId = call.id,
                text = args.optString("question").ifBlank {
                    return call.copy(
                        status = ToolCall.Status.FAILED,
                        result = "asked nothing",
                    )
                },
                options = (0 until (options?.length() ?: 0))
                    .mapNotNull { options?.optString(it)?.takeIf(String::isNotBlank) },
                // Out-of-range is treated as no recommendation rather than
                // clamped. Highlighting the wrong option would be worse than
                // highlighting none, and quietly picking option 0 is how a
                // recommendation becomes a lie.
                recommended = args.optInt("recommended", -1)
                    .takeIf { it >= 0 && it < (options?.length() ?: 0) },
                because = args.optString("because").takeIf { it.isNotBlank() },
            )

            report(call.copy(status = ToolCall.Status.ASKING))
            val answer = asker.ask(question)
            return call.copy(
                status = ToolCall.Status.DONE,
                // Your words, not "answered". The card is a record of what you
                // decided, and it has to still say so a week later.
                result = answer,
                body = answer,
            )
        }

        if (tool.risk != Risk.FREE) {
            val asker = permission
                // No desk means nobody can be asked, and the honest answer to
                // "may I change this file" with nobody to ask is no.
                ?: return call.copy(
                    status = ToolCall.Status.DENIED,
                    result = "no way to ask you — refused",
                )

            report(call.copy(status = ToolCall.Status.ASKING))
            val decision = asker.ask(
                PermissionRequest(
                    callId = call.id,
                    toolName = tool.name,
                    summary = runCatching { tool.describe(args) }.getOrNull().orEmpty(),
                    risk = tool.risk,
                )
            )
            if (decision == Decision.DENY) {
                return call.copy(
                    status = ToolCall.Status.DENIED,
                    // The model is told plainly, so it stops and asks rather
                    // than trying the same thing a different way.
                    result = "you said no",
                )
            }
        }

        report(call.copy(status = ToolCall.Status.RUNNING))

        return when (val result = runCatching {
            tool.run(ToolEnv(project, context, subagents), args)
        }.getOrElse {
            Log.w(TAG, "${call.name} threw", it)
            ToolResult.Failed(it.message ?: it.javaClass.simpleName)
        }) {
            is ToolResult.Ok -> call.copy(
                status = ToolCall.Status.DONE,
                result = result.summary,
                body = result.body,
            )

            is ToolResult.Failed -> call.copy(
                status = ToolCall.Status.FAILED,
                result = result.reason,
            )
        }
    }

    /** What the card says before it runs — from the arguments alone. */
    fun describe(call: ToolCall): String =
        ALL_TOOLS[call.name]
            ?.let { tool -> runCatching { tool.describe(JSONObject(call.argumentsJson)) }.getOrNull() }
            ?.takeIf { it.isNotBlank() }
            ?: call.argumentsJson.take(80)

    private companion object {
        const val TAG = "WarpTools"
    }
}
