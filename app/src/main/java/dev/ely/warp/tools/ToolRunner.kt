package dev.ely.warp.tools

import android.content.Context
import android.util.Log
import dev.ely.warp.ai.ToolCall
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
    context: Context,
    private val permission: AsksPermission? = null,
) {

    /**
     * Where the model is allowed to work.
     *
     * One folder inside the app's own storage, for now. Every tool resolves
     * against it and refuses to leave, so the boundary is a property of this
     * path rather than a rule each tool has to remember.
     */
    val project: File = File(context.filesDir, "project").apply { mkdirs() }

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

        return when (val result = runCatching { tool.run(project, args) }.getOrElse {
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
