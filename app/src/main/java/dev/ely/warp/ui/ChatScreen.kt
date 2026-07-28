package dev.ely.warp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ely.warp.ai.AiError
import dev.ely.warp.ai.ChatEngine
import dev.ely.warp.ai.ChatMessage
import dev.ely.warp.ai.Effort
import dev.ely.warp.ai.ProviderRegistry
import dev.ely.warp.ai.Role
import dev.ely.warp.ai.ToolCall
import dev.ely.warp.ui.theme.WarpMono
import dev.ely.warp.ui.theme.WarpSuccess
import dev.ely.warp.ui.theme.WarpWarning

/**
 * The chat.
 *
 * Runs against [MockProvider] until a real key is added, which is the point of
 * the harness: this screen never learns which AI is behind it.
 */
@Composable
fun ChatScreen(
    engine: ChatEngine,
    registry: ProviderRegistry,
    modifier: Modifier = Modifier,
) {
    val messages by engine.messages.collectAsState()
    val busy by engine.busy.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    var choice by remember { mutableStateOf(registry.choice) }
    var showPicker by remember { mutableStateOf(false) }

    if (showPicker) {
        ModelPickerDialog(
            registry = registry,
            current = choice,
            onPick = { picked ->
                choice = picked
                registry.choice = picked
                // Point the engine at the new model straight away, so the next
                // message uses it without a trip through Settings.
                engine.provider = registry.providerFor(picked.providerId)
                engine.model = picked.modelId
                engine.effort = picked.effort ?: Effort.LOW
            },
            onDismiss = { showPicker = false },
        )
    }

    // Follow the newest text as it streams in.
    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    // No imePadding here — MainActivity applies it once for the whole app.
    // Having it in both places made this column taller than the window and
    // pushed the chat up over the status bar whenever the keyboard opened.
    Column(modifier = modifier.fillMaxSize()) {

        ModelBar(label = choice.label, onClick = { showPicker = true })

        if (!engine.provider.requiresKey) {
            DemoBanner(providerName = engine.provider.displayName)
        }

        if (messages.isEmpty()) {
            EmptyState(modifier = Modifier.weight(1f), onPick = { input = it })
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(messages, key = { it.id }) { message -> MessageBubble(message) }
            }
        }

        Composer(
            value = input,
            onValueChange = { input = it },
            busy = busy,
            onSend = {
                engine.send(input)
                input = ""
            },
            onStop = { engine.stop() },
        )
    }
}

/** The current model, tappable to change it. */
@Composable
private fun ModelBar(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.size(6.dp))
        Text(
            "▾",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DemoBanner(providerName: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(WarpWarning.copy(alpha = 0.12f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("🎭", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.size(8.dp))
        Text(
            "Demo mode · $providerName. Answers are scripted, not thought through.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier, onPick: (String) -> Unit) {
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("⚡", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.size(12.dp))
        Text(
            "Ask Warp to build something",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.size(6.dp))
        Text(
            "The mock AI replies with scripted answers, so every part of the " +
                "app can be used before a real key is added.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(20.dp))

        SUGGESTIONS.forEach { suggestion ->
            TextButton(onClick = { onPick(suggestion) }, modifier = Modifier.fillMaxWidth()) {
                Text(suggestion)
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == Role.USER

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(
                    if (isUser) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainer,
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isUser) 16.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 16.dp,
                    ),
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Column {
                if (message.text.isNotEmpty()) {
                    Text(
                        message.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurface,
                    )
                }

                // Nothing has arrived yet — show that something is happening,
                // otherwise the app looks frozen while the model thinks.
                if (message.streaming && message.text.isEmpty() && message.toolCalls.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.size(10.dp))
                        Text(
                            "Thinking…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        message.toolCalls.forEach { call ->
            Spacer(Modifier.size(6.dp))
            ToolCard(call)
        }

        message.error?.let { error ->
            Spacer(Modifier.size(6.dp))
            ErrorCard(error)
        }
    }
}

@Composable
private fun ToolCard(call: ToolCall) {
    Column(
        modifier = Modifier
            .widthIn(max = 320.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🔧", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.size(8.dp))
            Text(
                call.name,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                when (call.status) {
                    ToolCall.Status.PENDING -> "waiting"
                    ToolCall.Status.RUNNING -> "running"
                    ToolCall.Status.DONE -> "done"
                    ToolCall.Status.FAILED -> "failed"
                    ToolCall.Status.DENIED -> "denied"
                },
                style = MaterialTheme.typography.labelSmall,
                color = when (call.status) {
                    ToolCall.Status.DONE -> WarpSuccess
                    ToolCall.Status.FAILED -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Spacer(Modifier.size(6.dp))
        // Arguments are JSON, so they must read left to right even on a
        // right-to-left phone.
        Ltr {
            Text(
                call.argumentsJson,
                style = WarpMono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ErrorCard(error: AiError) {
    Column(
        modifier = Modifier
            .widthIn(max = 320.dp)
            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Text(
            error.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    busy: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Ask Warp to build something…") },
            maxLines = 5,
            shape = RoundedCornerShape(20.dp),
        )
        Spacer(Modifier.size(8.dp))
        FilledIconButton(
            onClick = if (busy) onStop else onSend,
            enabled = busy || value.isNotBlank(),
        ) {
            Text(if (busy) "■" else "↑")
        }
    }
}

private val SUGGESTIONS = listOf(
    "Make me a counter app",
    "How does building on the phone work?",
    "pretend bad key",
)
