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
 * **Nothing here decides whether it is allowed.** Permission is the caller's
 * question, because the answer needs a screen and a person, and a runner that
 * could grant itself permission would be a runner that eventually does.
 */
class ToolRunner(context: Context) {

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
     * Returns the updated [ToolCall] rather than a raw result, because what the
     * chat needs is a status and a line of text — and deciding what that line
     * says belongs next to the code that knows what the tool did.
     */
    suspend fun run(call: ToolCall): ToolCall {
        val tool = READ_TOOLS[call.name]
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
        READ_TOOLS[call.name]
            ?.let { tool -> runCatching { tool.describe(JSONObject(call.argumentsJson)) }.getOrNull() }
            ?.takeIf { it.isNotBlank() }
            ?: call.argumentsJson.take(80)

    private companion object {
        const val TAG = "WarpTools"
    }
}
