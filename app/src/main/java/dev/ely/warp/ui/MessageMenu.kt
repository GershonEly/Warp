package dev.ely.warp.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.ely.warp.ai.ChatMessage
import dev.ely.warp.ai.Role
import dev.ely.warp.ui.theme.WarpMotion
import dev.ely.warp.ui.theme.WarpSpace
import dev.ely.warp.ui.theme.warpTween
import kotlin.math.roundToInt

/**
 * A message, held — §9f's Copy and Edit, in the shape people already know.
 *
 * Press and hold a message: the conversation behind it goes dark, the message
 * lifts and grows slightly, and the things you can do to it appear beside it.
 * It is the gesture every phone messaging app has already taught, which is the
 * whole argument for it — a menu nobody has to learn.
 *
 * **The message stays on screen, and that is the load-bearing part.** A menu
 * that replaces what you pressed makes you remember which one you meant; a menu
 * that grows out of it does not. Same reason the actions sit against the bubble
 * rather than in the middle of the screen: they belong to that message and have
 * to look like they do.
 *
 * @param bounds where the message sat at the moment of the press, in root
 *   coordinates. Captured rather than tracked, because the list can still
 *   scroll underneath and the lifted copy must not go with it.
 */
data class HeldMessage(val message: ChatMessage, val bounds: Rect)

@Composable
fun MessageMenu(
    held: HeldMessage,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    /** Null on the AI's messages: you cannot rewind to before it spoke. */
    onEdit: (() -> Unit)?,
) {
    val density = LocalDensity.current
    val config = LocalConfiguration.current

    // Starts at zero and is flipped on after the first frame.
    //
    // `animateFloatAsState(1f)` alone does nothing at all: its initial value is
    // its target, so there is no distance to travel and the menu simply appears.
    // The first version did exactly that and looked like a screenshot.
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }

    val grow by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = warpTween(WarpMotion.NORMAL),
        label = "held",
    )

    val screenHeight = with(density) { config.screenHeightDp.dp.toPx() }
    val groupHeight = with(density) { (MENU_HEIGHT + LIFT_MAX).toPx() }
    val mine = held.message.role == Role.USER

    // Where the overlay itself sits in the window.
    //
    // The bounds arrive in *root* coordinates and this Box does not start at the
    // root — it lives under a top bar. Offsetting by the raw value put the
    // lifted copy a bar's height too low, over the message below the one being
    // held, which is exactly what the first version did.
    var origin by remember { mutableStateOf(Offset.Zero) }

    // Kept on screen rather than pinned to the bubble. Anchoring to the message
    // alone let the group run off the bottom, and the row that runs off is the
    // one that destroys work.
    val top = (held.bounds.top - origin.y)
        .coerceIn(0f, (screenHeight - groupHeight).coerceAtLeast(0f))

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.boundsInRoot().topLeft }
            // Tap anywhere else to put it away. No ripple: the whole screen is
            // not a button, it is the way out.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            )
            // Heavier than it looks it needs to be. Over an already dark theme a
            // light scrim is indistinguishable from no scrim, which is what the
            // first version produced — the screen simply had a menu on it.
            .background(Color.Black.copy(alpha = 0.78f * grow))
    ) {
        // Laid out by side rather than by coordinate. A user's bubble is
        // right-aligned inside a full-width row, so the row's left edge is the
        // screen's left edge — offsetting to it put the lifted copy on the
        // wrong side of the screen. Compose already knows how to align these.
        Column(
            horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
            modifier = Modifier
                .offset { IntOffset(0, top.roundToInt()) }
                .fillMaxWidth()
                .padding(horizontal = WarpSpace.screen),
        ) {
            Box(
                modifier = Modifier.graphicsLayer {
                    transformOrigin = TransformOrigin(if (mine) 1f else 0f, 0.5f)
                    val scale = 0.96f + 0.075f * grow
                    scaleX = scale
                    scaleY = scale
                }
            ) {
                LiftedBubble(held.message)
            }

            Spacer(Modifier.size(WarpSpace.small))

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 3.dp,
                modifier = Modifier
                    .graphicsLayer {
                        alpha = grow
                        // Grows out of the corner nearest the bubble, so it
                        // reads as coming *from* the message rather than
                        // arriving on top of it.
                        transformOrigin = TransformOrigin(if (mine) 1f else 0f, 0f)
                        val scale = 0.8f + 0.2f * grow
                        scaleX = scale
                        scaleY = scale
                        translationY = (1f - grow) * -14f
                    }
                    .widthIn(min = 168.dp),
            ) {
                Column {
                    MenuRow(Icons.Outlined.ContentCopy, "Copy", onCopy)
                    if (onEdit != null) {
                        // "Edit" is what §9f calls it and what the gesture means
                        // to a person: change what I said. What it *does* is
                        // rewind, and the confirmation is where that gets
                        // explained — a menu row is the wrong place for that.
                        MenuRow(Icons.AutoMirrored.Outlined.Undo, "Edit", onEdit)
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = WarpSpace.medium, vertical = WarpSpace.small),
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = WarpSpace.medium),
        )
    }
}

/**
 * The message as it looks while held.
 *
 * Its text only, and capped. A held answer can be four hundred lines with tool
 * cards in it, and lifting all of that would cover the menu it is meant to be
 * opening — so this shows enough to know *which* message you have, which is the
 * only question the lift has to answer.
 */
@Composable
private fun LiftedBubble(message: ChatMessage) {
    val mine = message.role == Role.USER
    Surface(
        shape = if (mine) RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp)
        else RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp),
        color = if (mine) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.widthIn(max = 300.dp),
    ) {
        Text(
            message.text.trim().lines().take(LIFTED_LINES).joinToString("\n")
                .ifBlank { "(no text)" },
            style = MaterialTheme.typography.bodyLarge,
            color = if (mine) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

private val MENU_HEIGHT = 104.dp

/** Roughly the tallest a lifted bubble gets, for keeping the group on screen. */
private val LIFT_MAX = 220.dp
private const val LIFTED_LINES = 8
