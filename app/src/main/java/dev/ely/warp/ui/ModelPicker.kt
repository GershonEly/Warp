package dev.ely.warp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.ely.warp.ai.ModelBadge
import dev.ely.warp.ai.ModelChoice
import dev.ely.warp.ai.ProviderRegistry

/**
 * The model list, opened from the chat header.
 *
 * Every model from every provider appears in one list, so choosing is a single
 * decision rather than "pick a company, then pick a model". Effort is folded
 * into the row for the same reason — "Claude Opus 5 (High)" is one choice, not
 * a model plus a separate setting.
 *
 * Providers with no key still appear, greyed out and labelled, rather than
 * being hidden: a picker that shows only what you have already set up gives no
 * way to discover what Warp supports.
 */
@Composable
fun ModelPickerDialog(
    registry: ProviderRegistry,
    current: ModelChoice,
    onPick: (ModelChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    var choices by remember { mutableStateOf<List<ModelChoice>?>(null) }

    LaunchedEffect(Unit) {
        choices = registry.modelChoices()
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(16.dp))
                .padding(vertical = 12.dp),
        ) {
            Text(
                "Model",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Spacer(Modifier.size(6.dp))

            when (val list = choices) {
                null -> Row(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(12.dp))
                    Text("Loading models…", style = MaterialTheme.typography.bodyMedium)
                }

                else -> LazyColumn(modifier = Modifier.heightIn(max = 440.dp)) {
                    items(list, key = { it.key }) { choice ->
                        ModelRow(
                            choice = choice,
                            selected = choice.key == current.key,
                            onClick = {
                                if (choice.available) {
                                    onPick(choice)
                                    onDismiss()
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelRow(choice: ModelChoice, selected: Boolean, onClick: () -> Unit) {
    // Unavailable rows stay visible but read as inactive: dimmed text, no
    // highlight, and a tap does nothing.
    val textColor = when {
        !choice.available -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        else -> MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.surfaceVariant
                else androidx.compose.ui.graphics.Color.Transparent
            )
            .clickable(enabled = choice.available, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            choice.label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = textColor,
            modifier = Modifier.weight(1f),
        )

        choice.badge?.let { badge ->
            Spacer(Modifier.size(8.dp))
            Badge(badge, dimmed = !choice.available)
        }
    }
}

@Composable
private fun Badge(badge: ModelBadge, dimmed: Boolean) {
    Box(
        modifier = Modifier
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (dimmed) 0.4f else 1f),
                RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(
            badge.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
                .copy(alpha = if (dimmed) 0.5f else 1f),
        )
    }
}
