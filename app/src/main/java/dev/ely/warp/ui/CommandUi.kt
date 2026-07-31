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
import dev.ely.warp.ai.SlashCommand
import dev.ely.warp.data.Rules
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
