package dev.ely.warp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ely.warp.ai.ChatEngine
import androidx.compose.material3.TextButton
import dev.ely.warp.ai.SlashCommand
import dev.ely.warp.data.Rules
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import dev.ely.warp.tools.Question
import dev.ely.warp.ui.theme.WarpMono
import dev.ely.warp.ui.theme.WarpSpace

/**
 * `/rules add …`, `/rules remove 2`, `/rules clear`.
 *
 * Returns the line to show. Every branch says what happened **to the list**
 * rather than "done" — a rule you believe you added and did not is the first
 * failure §5d names, and "done" cannot tell the two apart.
 */
fun applyRuleCommand(rules: Rules, argument: String): String {
    val verb = argument.takeWhile { !it.isWhitespace() }.lowercase()
    val rest = argument.drop(verb.length).trim()

    return when (verb) {
        "add" -> when {
            rest.isBlank() -> "Say what the rule is: /rules add never touch the manifest"
            rules.add(rest) -> "Rule ${rules.rules.value.size} added."
            else -> "Not added — either it is already there, or the list is full."
        }

        "remove", "delete" -> {
            val position = rest.toIntOrNull() ?: return "Which one? /rules remove 2"
            rules.removeAt(position)
                ?.let { "Removed: $it" }
                ?: "There is no rule $position."
        }

        "clear" -> {
            val had = rules.rules.value.size
            rules.clear()
            if (had == 0) "There were no rules." else "Cleared $had rules."
        }

        // Bare text after /rules is almost always the rule itself. Treating it
        // as an unknown verb would be technically right and practically useless.
        else -> if (rules.add(argument)) {
            "Rule ${rules.rules.value.size} added."
        } else {
            "Not added — either it is already there, or the list is full."
        }
    }
}

/**
 * The rules in force, and a way to drop one.
 *
 * Adding happens in the composer, where you were already typing. This is for
 * seeing what is in force — the thing that goes wrong quietly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesSheet(rules: Rules, onDismiss: () -> Unit) {
    val current by rules.rules.collectAsState()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = WarpSpace.large),
        ) {
            Text(
                "Rules",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = WarpSpace.screen),
            )
            Spacer(Modifier.size(4.dp))
            Text(
                "Sent with every message, in every chat. Warp is told these override " +
                    "its own judgement about what would be helpful.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = WarpSpace.screen),
            )
            Spacer(Modifier.size(WarpSpace.medium))

            if (current.isEmpty()) {
                Text(
                    "None yet. Type /rules add never touch the manifest",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        horizontal = WarpSpace.screen,
                        vertical = WarpSpace.medium,
                    ),
                )
            }

            current.forEachIndexed { index, rule ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = WarpSpace.screen, vertical = WarpSpace.small),
                    verticalAlignment = Alignment.Top,
                ) {
                    // Numbered, and the number is what `/rules remove` takes. A
                    // list you can read but not address is a list you cannot edit.
                    Text(
                        "${index + 1}.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.widthIn(min = 22.dp),
                    )
                    Text(
                        rule,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = "Remove rule ${index + 1}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(18.dp)
                            .clickable { rules.removeAt(index + 1) },
                    )
                }
            }
        }
    }
}

/**
 * The menu shown while a command is being typed.
 *
 * Sits above the composer rather than over the transcript, so it grows out of
 * what is being typed instead of covering what was said.
 *
 * Commands that are not built yet appear greyed rather than missing. A menu that
 * quietly omits them teaches you they do not exist, which is not what is true —
 * and the day one of them lands, nothing tells you it has.
 */
@Composable
fun CommandMenu(
    matches: List<SlashCommand>,
    onPick: (SlashCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (matches.isEmpty()) return

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(vertical = WarpSpace.small)) {
            matches.forEach { command ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = command.ready) { onPick(command) }
                        .padding(horizontal = WarpSpace.medium, vertical = 10.dp)
                        .alpha(if (command.ready) 1f else 0.4f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(command.typed, style = WarpMono, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.size(WarpSpace.medium))
                    Text(
                        if (command.ready) command.hint else "${command.hint} · not built yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * A question Warp is asking you, mid-turn.
 *
 * Deliberately **not** a tool card. It has no wrench, no monospace, no JSON —
 * because it is not a report of machinery, it is somebody asking you something,
 * and it is the one card in the transcript that is waiting on you rather than
 * telling you what already happened.
 *
 * The recommended option is marked and sits first among equals, but it is not
 * pre-selected and there is no default. A recommendation that answers for you is
 * not a recommendation.
 */
@Composable
fun QuestionCard(
    question: Question,
    onAnswer: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var typed by remember(question.callId) { mutableStateOf("") }
    var typing by remember(question.callId) { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(question.text, style = MaterialTheme.typography.bodyLarge)

            question.because?.let {
                Spacer(Modifier.size(4.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.size(12.dp))

            question.options.forEachIndexed { index, option ->
                val recommended = index == question.recommended
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (recommended) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .clickable { onAnswer(option) },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            option,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                            color = if (recommended)
                                MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurface,
                        )
                        if (recommended) {
                            // Says the word. A coloured background alone is a
                            // convention you have to already know.
                            Text(
                                "recommended",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.size(8.dp))

            // Always reachable. Every real answer to a question worth asking is
            // eventually "none of those", and a card that can only be answered
            // with its own options is a card that collects wrong answers.
            if (typing) {
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    placeholder = { Text("Your answer") },
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(6.dp))
                Button(
                    onClick = { typed.trim().takeIf { it.isNotEmpty() }?.let(onAnswer) },
                    enabled = typed.isNotBlank(),
                ) { Text("Answer") }
            } else {
                Text(
                    "Something else…",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { typing = true },
                )
            }
        }
    }
}

/**
 * A question that has already been answered.
 *
 * Kept in the transcript rather than collapsed away, because the answers *are*
 * the outcome of a grilling — a summary that says "we decided X" with no record
 * of what was asked is exactly the thing you cannot check later.
 *
 * Quiet on purpose. It is history, not something to act on.
 */
@Composable
fun AnsweredQuestion(call: dev.ely.warp.ai.ToolCall, modifier: Modifier = Modifier) {
    val asked = runCatching {
        org.json.JSONObject(call.argumentsJson).optString("question")
    }.getOrNull().orEmpty().ifBlank { "Question" }

    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            "✓",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.widthIn(min = 18.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                asked,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                call.result.orEmpty().ifBlank { "—" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * The goal, kept in front of you the whole time it is running.
 *
 * This bar is the answer to §5d's third failure — *frozen while claiming
 * to work*. `/goal` removes the turn boundary, which is the moment you would
 * otherwise glance at what happened and decide whether to carry on. So the two
 * things that boundary gave you are put on screen permanently instead: what it
 * is trying to do, and how much rope it has left.
 *
 * Stop is a word, not an icon. This is the control someone reaches for when
 * they have decided it is going wrong, and that is the wrong moment to make
 * them work out what a square means.
 */
@Composable
fun GoalBar(
    goal: ChatEngine.Goal,
    onStop: () -> Unit,
    onContinue: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    goal.condition,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    // The count is not decoration. A number that climbs is how
                    // you tell working from stuck without reading every reply.
                    "Working · ${goal.label}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            // Continue rather than Stop when it has run out of turns. Ending
            // a goal at the limit threw away the only valuable thing about it —
            // what it was trying to do — and saying "continue" by hand is
            // exactly what rescued the run this was rebuilt after.
            if (goal.paused) {
                TextButton(onClick = onContinue) {
                    Text("Continue", style = MaterialTheme.typography.labelLarge)
                }
            }
            TextButton(onClick = onStop) {
                Text(if (goal.paused) "Drop" else "Stop",
                    style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
