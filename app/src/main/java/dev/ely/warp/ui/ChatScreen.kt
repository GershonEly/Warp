package dev.ely.warp.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Stop
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ely.warp.ui.theme.HairlineWidth
import dev.ely.warp.ui.theme.LocalCodeSurface
import dev.ely.warp.ui.theme.LocalIsDark
import dev.ely.warp.ui.theme.WarpSpace
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
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun ChatScreen(engine: ChatEngine, modifier: Modifier = Modifier) {
    val messages by engine.messages.collectAsState()
    val busy by engine.busy.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val haptics = LocalHapticFeedback.current

    // Follow the newest text as it streams in.
    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    // The mark is one continuous object across both states. When the first
    // message is sent it does not vanish and get replaced — it travels from the
    // centre of the empty screen down into its place beside the reply, shrinking
    // as it goes. A shared element, so the movement is the real thing moving
    // rather than two things cross-fading.
    SharedTransitionLayout(modifier = modifier.fillMaxSize()) {
        // Read here, in composable scope: the transition block is not one, and
        // these carry the reduce-animations setting.
        val bodyIn = motionDuration(WarpMotion.SLOW)
        val bodyOut = motionDuration(WarpMotion.QUICK)

        Column(modifier = Modifier.fillMaxSize()) {
            if (!engine.provider.requiresKey) DemoChip()

            AnimatedContent(
                targetState = messages.isEmpty(),
                modifier = Modifier.weight(1f),
                transitionSpec = {
                    fadeIn(tween(bodyIn)) togetherWith fadeOut(tween(bodyOut))
                },
                label = "chatBody",
            ) { empty ->
                // The key ties the two marks together; Compose interpolates
                // position and size between them.
                val markModifier = Modifier.sharedElement(
                    rememberSharedContentState(MARK_KEY),
                    animatedVisibilityScope = this@AnimatedContent,
                )

                if (empty) {
                    EmptyState(markModifier = markModifier, onPick = { input = it })
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            horizontal = WarpSpace.screen,
                            vertical = WarpSpace.large,
                        ),
                        verticalArrangement = Arrangement.spacedBy(WarpSpace.message),
                    ) {
                        itemsIndexed(messages, key = { _, m -> m.id }) { index, message ->
                            // animateItem moves neighbours smoothly as the list
                            // grows; Appear handles each message's own entrance.
                            Appear(modifier = Modifier.animateItem()) {
                                MessageItem(
                                    message = message,
                                    // Only the first reply inherits the travelling
                                    // mark — later ones simply appear.
                                    markModifier = if (index == 1) markModifier else Modifier,
                                )
                            }
                        }
                    }
                }
            }

            Composer(
                value = input,
                onValueChange = { input = it },
                busy = busy,
                onSend = {
                    // A light tap on send. Haptics are for things that happen,
                    // never for navigation — a phone that buzzes at everything
                    // stops meaning anything.
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    engine.send(input)
                    input = ""
                },
                onStop = { engine.stop() },
            )
        }
    }
}

/** Ties the empty state's mark to the first reply's mark. */
private const val MARK_KEY = "warp-mark"

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

/**
 * A quiet note that answers are scripted.
 *
 * Previously a full-width amber band with an emoji, which read as a system
 * warning — the loudest thing on a screen whose subject is a conversation.
 * Demo mode is a fact worth stating once, not an alarm.
 */
@Composable
private fun DemoChip() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = WarpSpace.small),
        horizontalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = Color.Transparent,
            border = BorderStroke(HairlineWidth, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Text(
                "Demo mode · answers are scripted",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = WarpSpace.medium, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun EmptyState(
    markModifier: Modifier,
    modifier: Modifier = Modifier,
    onPick: (String) -> Unit,
) {
    // The group sits at the top and the empty space falls below it. Content
    // pushed to the bottom makes the screen read as though it has scrolled away
    // from something; starting at the top gives the conversation room to grow
    // downward into the space it will use.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = WarpSpace.screen)
            .padding(top = WarpSpace.section),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // One of only two places in the app allowed to glow. Restraint is what
        // makes this moment land; an app that glows everywhere glows nowhere.
        //
        // Far weaker on white. Light does not add on a light background the way
        // it does on a dark one — at dark-mode strength this bloom read as a
        // grey-blue smudge behind the mark rather than as a glow.
        val dark = LocalIsDark.current
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(if (dark) 200.dp else 150.dp)
                    .background(
                        Brush.radialGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary
                                    .copy(alpha = if (dark) 0.18f else 0.07f),
                                Color.Transparent,
                            )
                        )
                    )
            )
            WarpMark(size = 56.dp, modifier = markModifier)
        }

        Spacer(Modifier.size(WarpSpace.large))

        Text(
            "What would you like to build today?",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.size(WarpSpace.section))

        Row(horizontalArrangement = Arrangement.spacedBy(WarpSpace.small)) {
            SUGGESTIONS.forEach { suggestion -> SuggestionPill(suggestion) { onPick(suggestion) } }
        }
    }
}

/** Hairline, no fill — a suggestion should invite, not compete with the answer. */
@Composable
private fun SuggestionPill(text: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.Transparent,
        border = BorderStroke(HairlineWidth, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = WarpSpace.large, vertical = 10.dp),
        )
    }
}


/**
 * The mark that stands in front of everything the AI says.
 *
 * It turns while the model is working, so "Warp is thinking" is shown by Warp's
 * own symbol rather than by a generic spinner beside it.
 */
@Composable
private fun MessageMark(thinking: Boolean, modifier: Modifier = Modifier) {
    if (thinking) {
        ThinkingMark(size = 20.dp, modifier = modifier)
    } else {
        WarpMark(size = 20.dp, modifier = modifier)
    }
}

@Composable
private fun MessageItem(message: ChatMessage, markModifier: Modifier = Modifier) {
    if (message.role == Role.USER) {
        UserMessage(message)
    } else {
        AssistantMessage(message, markModifier)
    }
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
private fun AssistantMessage(message: ChatMessage, markModifier: Modifier = Modifier) {
    val working = message.streaming && message.text.isEmpty() && message.toolCalls.isEmpty()

    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        MessageMark(thinking = working, modifier = markModifier)
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
                // A drawn icon rather than an emoji: emoji are another vendor's
                // artwork, they change shape per phone, and they sit at a
                // different weight to everything around them.
                Icon(
                    Icons.Outlined.Build,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(WarpSpace.small))
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
    var focused by remember { mutableStateOf(false) }

    // The border eases to the accent on focus rather than switching, so the
    // field wakes up instead of blinking.
    val border by animateColorAsState(
        targetValue = if (focused) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outlineVariant,
        animationSpec = warpTween(WarpMotion.NORMAL),
        label = "composerBorder",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = WarpSpace.large, vertical = WarpSpace.medium),
        verticalAlignment = Alignment.Bottom,
    ) {
        Surface(
            modifier = Modifier.weight(1f),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(HairlineWidth, border),
        ) {
            Box(
                modifier = Modifier
                    .heightIn(min = 52.dp)
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                // BasicTextField rather than OutlinedTextField: the stock field
                // brings a label, a container and an outline that cannot be
                // fully removed, and its shape is one of the things that reads
                // as a generic Android app.
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focused = it.isFocused },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    maxLines = 6,
                    decorationBox = { field ->
                        if (value.isEmpty()) {
                            Text(
                                "Message Warp",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        field()
                    },
                )
            }
        }

        Spacer(Modifier.size(WarpSpace.small))

        SendButton(busy = busy, enabled = busy || value.isNotBlank()) {
            if (busy) onStop() else onSend()
        }
    }
}

@Composable
private fun SendButton(busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    // Both colours ease rather than switch, so arming the button as you type is
    // a wake-up rather than a flash.
    val background by animateColorAsState(
        targetValue = if (enabled) MaterialTheme.colorScheme.primary else Color.Transparent,
        animationSpec = warpTween(WarpMotion.NORMAL),
        label = "sendFill",
    )
    val content by animateColorAsState(
        targetValue = if (enabled) Color.White
        else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = warpTween(WarpMotion.NORMAL),
        label = "sendInk",
    )

    Surface(
        shape = CircleShape,
        color = background,
        // A hairline when idle rather than a filled grey disc. A grey circle
        // reads as broken; an outline reads as waiting.
        border = if (enabled) null
        else BorderStroke(HairlineWidth, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.size(52.dp),
        onClick = onClick,
        enabled = enabled,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (busy) Icons.Outlined.Stop else Icons.AutoMirrored.Outlined.Send,
                contentDescription = if (busy) "Stop" else "Send",
                tint = content,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

private val SUGGESTIONS = listOf("Weather app", "Todo app")
