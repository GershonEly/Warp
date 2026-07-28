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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.ely.warp.ai.ModelBadge
import dev.ely.warp.ai.ModelChoice
import dev.ely.warp.ai.ProviderRegistry

/**
 * The model list, opened from the chat header.
 *
 * Two levels. The first is a folder per model family — Claude, Gemini, GPT —
 * because a flat list runs to several hundred rows once OpenRouter's catalogue
 * is included, and a list that long is not a choice, it is a scroll. Opening a
 * folder shows that family's models.
 *
 * Grouped by family rather than by provider: OpenRouter resells other
 * companies' models, so grouping by provider would scatter Claude across two
 * folders and leave one folder holding most of the industry.
 *
 * Effort is folded into each row — "Claude Opus 5 (High)" is one choice, not a
 * model plus a separate setting.
 */
@Composable
fun ModelPickerDialog(
    registry: ProviderRegistry,
    current: ModelChoice,
    onPick: (ModelChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    var choices by remember { mutableStateOf<List<ModelChoice>?>(null) }
    var query by remember { mutableStateOf("") }
    var openFolder by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { choices = registry.modelChoices() }

    val all = choices.orEmpty()
    val searching = query.isNotBlank()

    // Only the mock and the "needs a key" notices sit outside the folders.
    // Availability must NOT decide this: with no keys yet, every model from a
    // provider with a public catalogue is unavailable, and treating those as
    // top-level put hundreds of rows back in a flat list.
    val topLevel = all.filter { it.group == ProviderRegistry.TOP_LEVEL }
    val grouped = all
        .filter { it.group != ProviderRegistry.TOP_LEVEL }
        .groupBy { it.group }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceContainerHigh,
                    RoundedCornerShape(16.dp),
                )
                .padding(vertical = 12.dp),
        ) {
            Header(
                folder = openFolder,
                onBack = { openFolder = null },
            )

            if (choices != null) {
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    placeholder = { Text("Search all models") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                )
            }

            Spacer(Modifier.size(6.dp))

            if (choices == null) {
                Loading()
                return@Column
            }

            val pick: (ModelChoice) -> Unit = { choice ->
                if (choice.available) {
                    onPick(choice)
                    onDismiss()
                }
            }

            when {
                // Searching cuts across folders: asking for a model by name
                // should find it wherever it lives.
                searching -> {
                    val hits = all.filter { it.label.contains(query, ignoreCase = true) }
                    ModelList(hits, current, pick, emptyMessage = "No model matches \"$query\".")
                }

                openFolder != null -> {
                    // Short list first inside a folder, so the models worth
                    // picking are not below twenty dated variants.
                    val inFolder = grouped[openFolder].orEmpty()
                        .sortedWith(compareByDescending<ModelChoice> { it.recommended }
                            .thenBy { it.modelName.lowercase() })
                    ModelList(inFolder, current, pick, emptyMessage = "This folder is empty.")
                }

                else -> LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    items(topLevel, key = { it.key }) { choice ->
                        ModelRow(choice, choice.key == current.key) { pick(choice) }
                    }
                    items(grouped.keys.sorted(), key = { it }) { folder ->
                        FolderRow(
                            name = folder,
                            // Effort variants would triple the count and make
                            // "Claude (18)" mean three real models.
                            count = grouped[folder].orEmpty().distinctBy { it.modelId }.size,
                            containsCurrent = grouped[folder].orEmpty()
                                .any { it.key == current.key },
                            onClick = { openFolder = folder },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(folder: String?, onBack: () -> Unit) {
    if (folder == null) {
        Text(
            "Model",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onBack)
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("‹", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.size(10.dp))
            Text(
                folder,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun Loading() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.size(12.dp))
        Text("Loading models…", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ModelList(
    choices: List<ModelChoice>,
    current: ModelChoice,
    onPick: (ModelChoice) -> Unit,
    emptyMessage: String,
) {
    LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
        items(choices, key = { it.key }) { choice ->
            ModelRow(choice, choice.key == current.key) { onPick(choice) }
        }
        if (choices.isEmpty()) {
            item {
                Text(
                    emptyMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun FolderRow(
    name: String,
    count: Int,
    containsCurrent: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("📁", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.size(10.dp))
        Text(
            name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (containsCurrent) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        Text(
            "$count",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(10.dp))
        Text("›", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ModelRow(choice: ModelChoice, selected: Boolean, onClick: () -> Unit) {
    // Unavailable rows stay visible but read as inactive: dimmed, no highlight,
    // and a tap does nothing.
    val textColor =
        if (choice.available) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent
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
