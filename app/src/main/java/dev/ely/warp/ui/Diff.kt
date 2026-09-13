package dev.ely.warp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.ely.warp.ai.ToolCall
import dev.ely.warp.ui.theme.WarpMono
import dev.ely.warp.ui.theme.WarpSuccess
import org.json.JSONObject

/**
 * What changed, rather than the JSON that asked for it — §5k.
 *
 * §1 calls this the one screen between the model and your files. Before it, an
 * edit reported "replaced 1 match · -3 +5 lines" and printed its own arguments
 * underneath: a number, and the machinery. The thing that changed was the one
 * thing missing.
 *
 * **The before and after are already here.** `edit_file` carries `old` and `new`
 * in its own arguments, and those are stored with the message, so a real diff
 * costs no new column, no migration, and no capture step that could fail. That
 * fact decided the design rather than the other way round.
 */

/** One line of a unified diff. */
data class DiffLine(val kind: Kind, val text: String) {
    enum class Kind { SAME, ADDED, REMOVED }
}

/**
 * Above this, the table below would cost more memory than the diff is worth.
 *
 * The comparison is O(old × new) in ints, so a thousand lines against a thousand
 * is four megabytes on a phone. Past the cap it degrades to "all of it went, all
 * of this arrived" — honest, and still readable, rather than a stall.
 */
private const val MAX_LINES = 600

/**
 * Match the two texts line by line.
 *
 * A longest-common-subsequence walk, so a changed line inside a block reads as
 * one line removed and one added with its neighbours intact. The cheap version —
 * every old line red, every new line green — makes a three-line change in a
 * twelve-line block look like twelve, and colour that always means "changed"
 * means nothing.
 */
fun diffLines(old: String, new: String): List<DiffLine> {
    // Empty is no lines, not one empty line. `"".split("\n")` gives a list with
    // one blank in it, which would draw a phantom removed line on every new file.
    val a = if (old.isEmpty()) emptyList() else old.split("\n")
    val b = if (new.isEmpty()) emptyList() else new.split("\n")

    if (a.isEmpty()) return b.map { DiffLine(DiffLine.Kind.ADDED, it) }
    if (b.isEmpty()) return a.map { DiffLine(DiffLine.Kind.REMOVED, it) }

    if (a.size > MAX_LINES || b.size > MAX_LINES) {
        return a.map { DiffLine(DiffLine.Kind.REMOVED, it) } +
            b.map { DiffLine(DiffLine.Kind.ADDED, it) }
    }

    // lcs[i][j] = length of the longest common run from a[i..] and b[j..]
    val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
    for (i in a.indices.reversed()) {
        for (j in b.indices.reversed()) {
            lcs[i][j] = if (a[i] == b[j]) {
                lcs[i + 1][j + 1] + 1
            } else {
                maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
        }
    }

    val out = ArrayList<DiffLine>(a.size + b.size)
    var i = 0
    var j = 0
    while (i < a.size && j < b.size) {
        when {
            a[i] == b[j] -> { out += DiffLine(DiffLine.Kind.SAME, a[i]); i++; j++ }
            // Removal before addition when the two are equally good, so a
            // replaced line reads "- old" then "+ new", the order it happened in.
            lcs[i + 1][j] >= lcs[i][j + 1] -> { out += DiffLine(DiffLine.Kind.REMOVED, a[i]); i++ }
            else -> { out += DiffLine(DiffLine.Kind.ADDED, b[j]); j++ }
        }
    }
    while (i < a.size) { out += DiffLine(DiffLine.Kind.REMOVED, a[i]); i++ }
    while (j < b.size) { out += DiffLine(DiffLine.Kind.ADDED, b[j]); j++ }
    return out
}

/**
 * The change a tool call made, or null where it did not make one.
 *
 * `write_file` is shown as all-added rather than as a diff, and that is not a
 * euphemism — replacing a whole file is what that tool does, and the previous
 * text was never kept. §5k leaves that gap open on purpose: Git arrives with
 * before-and-after for everything, and a second copy of every file written would
 * be work thrown away the day it lands.
 */
fun fileChange(call: ToolCall, before: ((String) -> String?)? = null): List<DiffLine>? {
    val args = runCatching { JSONObject(call.argumentsJson.ifBlank { "{}" }) }.getOrNull()
        ?: return null
    return when (call.name) {
        "edit_file" -> diffLines(args.optString("old"), args.optString("new"))
        "write_file" -> {
            // The old text, if anything kept it — §5k's gap, closed by §5n.
            //
            // `write_file` replaces a whole file and never carried what was
            // there before, so this used to be drawn as all-added even when it
            // changed two lines of an existing file. Git has the previous
            // version, and [before] is how the card asks for it without this
            // file learning what a repository is.
            val path = args.optString("path")
            val old = if (path.isNotBlank()) before?.invoke(path).orEmpty() else ""
            diffLines(old, args.optString("content"))
        }
        else -> null
    }
}

/** Enough to see the shape of a change without becoming the whole screen. */
private const val COLLAPSED_LINES = 14

/**
 * A unified diff, one column.
 *
 * Split view needs width a phone does not have. Monospace and scrolling
 * sideways, the same treatment code blocks get — a wrapped line of code is a
 * line you cannot read, and a diff of wrapped lines is worse, because the sign
 * in the margin stops lining up with anything.
 */
@Composable
fun DiffView(
    lines: List<DiffLine>,
    expanded: Boolean,
    /**
     * Whether this change actually reached the file.
     *
     * False drains the colour out of it, and that is not a nicety. A refused
     * write drew its lines in green — the colour that means *this was added* —
     * under a card that said "you said no". The card was honest and the body
     * contradicted it, which is the worse of the two to believe.
     *
     * A change still waiting on you keeps its colour: there, green is what you
     * are being asked to allow, not a claim about what happened.
     */
    applied: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val shown = if (expanded) lines else lines.take(COLLAPSED_LINES)
    val hidden = lines.size - shown.size

    Column(modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        if (!applied) {
            Text(
                "not applied",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
        shown.forEach { line ->
            val ground = when {
                !applied -> Color.Transparent
                line.kind == DiffLine.Kind.ADDED -> WarpSuccess.copy(alpha = 0.14f)
                line.kind == DiffLine.Kind.REMOVED ->
                    MaterialTheme.colorScheme.error.copy(alpha = 0.14f)
                else -> Color.Transparent
            }
            val ink = when {
                !applied -> MaterialTheme.colorScheme.onSurfaceVariant
                line.kind == DiffLine.Kind.ADDED -> WarpSuccess
                line.kind == DiffLine.Kind.REMOVED -> MaterialTheme.colorScheme.error
                // Context is dimmed rather than absent. A change with nothing
                // around it is a change you cannot place in the file.
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            val sign = when (line.kind) {
                DiffLine.Kind.ADDED -> "+ "
                DiffLine.Kind.REMOVED -> "- "
                DiffLine.Kind.SAME -> "  "
            }
            Text(
                sign + line.text,
                style = WarpMono,
                color = ink,
                maxLines = 1,
                modifier = Modifier
                    .background(ground)
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
        if (hidden > 0) {
            Text(
                "… $hidden more line${if (hidden == 1) "" else "s"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}
