package dev.ely.warp.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ely.warp.ai.AiError
import dev.ely.warp.ai.ChatEngine
import dev.ely.warp.ai.ChatMessage
import dev.ely.warp.ai.Role
import dev.ely.warp.ai.ToolCall
import dev.ely.warp.ui.theme.WarpMono
import dev.ely.warp.ui.theme.WarpMotion
import dev.ely.warp.ui.theme.WarpSuccess
import dev.ely.warp.ui.theme.WarpWarning
import dev.ely.warp.ui.theme.motionDuration
import dev.ely.warp.ui.theme.warpTween

/**
 * The chat.
 *
 * Laid out as in the plan: the assistant speaks in plain text behind a ✦ rather
 * than in a bubble, so long answers read like prose instead of a chat log; only
 * the user's own messages are bubbled. Cards — tools, plans, errors — sit
 * outside the text as their own objects.
 *
 * Which AI is behind it is never visible here. That is the harness working.
 */
@Composable
fun ChatScreen(engine: ChatEngine, modifier: Modifier = Modifier) {
    val messages by engine.messages.collectAsState()
    val busy by engine.busy.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Follow the newest text as it streams in.
    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (!engine.provider.requiresKey) {
            DemoBanner(providerName = engine.provider.displayName)
        }

        if (messages.isEmpty()) {
            EmptyState(modifier = Modifier.weight(1f), onPick = { input = it })
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(messages, key = { it.id }) { message ->
                    // animateItem moves neighbours smoothly when the list grows;
                    // Appear handles the message's own entrance.
                    Appear(modifier = Modifier.animateItem()) {
                        MessageItem(message)
                    }
                }
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

/**
 * Fades content in while it rises into place.
 *
 * Nothing in Warp should pop into existence. Both values collapse to instant
 * when the system's reduce-animations setting is on.
 */
@Composable
private fun Appear(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }

    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = warpTween(WarpMotion.NORMAL, WarpMotion.Enter),
        label = "appear",
    )

    Box(
        modifier = modifier
            .alpha(progress)
            .padding(top = ((1f - progress) * WarpMotion.RISE_DP).dp),
    ) { content() }
}

@Composable
private fun DemoBanner(providerName: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(WarpWarning.copy(alpha = 0.10f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("🎭", style = MaterialTheme.typography.bodySmall)
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
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        WarpMark(size = 40.dp)
        Spacer(Modifier.size(20.dp))
        Text(
            "What would you like to build today?",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(Modifier.size(24.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SUGGESTIONS.forEach { suggestion ->
                SuggestionChip(
                    onClick = { onPick(suggestion) },
                    label = { Text(suggestion, style = MaterialTheme.typography.labelLarge) },
                    shape = CircleShape,
                )
            }
        }
    }
}

/**
 * The mark that stands in front of everything the AI says.
 *
 * It turns while the model is working, so "Warp is thinking" is shown by Warp's
 * own symbol rather than by a generic spinner beside it.
 */
@Composable
private fun MessageMark(thinking: Boolean) {
    if (thinking) {
        ThinkingMark(size = 18.dp)
    } else {
        WarpMark(size = 18.dp)
    }
}

@Composable
private fun MessageItem(message: ChatMessage) {
    if (message.role == Role.USER) UserMessage(message) else AssistantMessage(message)
}

@Composable
private fun UserMessage(message: ChatMessage) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .background(
                    MaterialTheme.colorScheme.primaryContainer,
                    RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp),
                )
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(
                message.text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun AssistantMessage(message: ChatMessage) {
    val working = message.streaming && message.text.isEmpty() && message.toolCalls.isEmpty()

    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        MessageMark(thinking = working)
        Spacer(Modifier.size(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            // Before the first token there is nothing to read, so name what is
            // happening rather than leaving an empty space.
            if (working) ThinkingLine()

            if (message.text.isNotEmpty()) {
                Text(
                    message.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            message.toolCalls.forEach { call ->
                Spacer(Modifier.size(10.dp))
                ToolCard(call)
            }

            message.error?.let { error ->
                Spacer(Modifier.size(10.dp))
                ErrorCard(error)
            }
        }
    }
}

@Composable
private fun ThinkingLine() {
    // No spinner beside it: the mark itself is already turning, and two things
    // spinning at once reads as clutter.
    Text(
        "Thinking…",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ToolCard(call: ToolCall) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
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
            // Arguments are JSON, so they read left to right whatever the
            // phone's language is.
            Ltr {
                Text(
                    call.argumentsJson,
                    style = WarpMono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Allow / Always belong here, and arrive with Step 5 — when tools
            // actually run, and a decision means something.
        }
    }
}

@Composable
private fun ErrorCard(error: AiError) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            error.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(12.dp),
        )
    }
}

/**
 * The floating pill input.
 *
 * Attachments and voice belong here too, per the plan — they are left out until
 * they do something, because a button that does nothing is worse than no
 * button.
 */
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
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            placeholder = {
                Text("Message Warp", style = MaterialTheme.typography.bodyLarge)
            },
            maxLines = 5,
            shape = CircleShape,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
            ),
        )

        Spacer(Modifier.size(8.dp))

        SendButton(busy = busy, enabled = busy || value.isNotBlank()) {
            if (busy) onStop() else onSend()
        }
    }
}

@Composable
private fun SendButton(busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    // The colour eases between states rather than switching, so enabling the
    // button as you type does not flash.
    val target = if (enabled) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.surfaceContainer
    val background by androidx.compose.animation.animateColorAsState(
        targetValue = target,
        animationSpec = warpTween(WarpMotion.QUICK),
        label = "sendColour",
    )

    Surface(
        shape = CircleShape,
        color = background,
        modifier = Modifier.size(48.dp),
        onClick = onClick,
        enabled = enabled,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (busy) Icons.Filled.Stop else Icons.AutoMirrored.Filled.Send,
                contentDescription = if (busy) "Stop" else "Send",
                tint = if (enabled) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

private val SUGGESTIONS = listOf("Weather app", "Todo app")
