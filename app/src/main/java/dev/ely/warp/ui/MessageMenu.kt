package dev.ely.warp.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
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
    val grow by animateFloatAsState(1f, warpTween(WarpMotion.QUICK), label = "held")

    val screenHeight = with(density) { config.screenHeightDp.dp.toPx() }
    val menuHeight = with(density) { MENU_HEIGHT.toPx() }
    val gap = with(density) { WarpSpace.small.toPx() }
    val edge = with(density) { WarpSpace.screen.toPx() }

    // Below the bubble when there is room, above it when there is not. A menu
    // that opens off the bottom of the screen is a menu whose last row nobody
    // can reach — and the last row is the one that destroys work.
    val openBelow = held.bounds.bottom + menuHeight + gap < screenHeight
    val menuY = if (openBelow) held.bounds.bottom + gap
    else (held.bounds.top - menuHeight - gap).coerceAtLeast(gap)

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Tap anywhere else to put it away. No ripple: the whole screen is
            // not a button, it is the way out.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            )
            .background(Color.Black.copy(alpha = 0.55f * grow))
    ) {
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(held.bounds.left.roundToInt(), held.bounds.top.roundToInt())
                }
                .graphicsLayer {
                    val scale = 1f + 0.035f * grow
                    scaleX = scale
                    scaleY = scale
                }
        ) {
            LiftedBubble(held.message)
        }

        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 3.dp,
            modifier = Modifier
                .offset {
                    IntOffset(
                        held.bounds.left.coerceAtLeast(edge).roundToInt(),
                        menuY.roundToInt(),
                    )
                }
                .graphicsLayer { alpha = grow }
                .widthIn(min = 168.dp),
        ) {
            Column {
                MenuRow(Icons.Outlined.ContentCopy, "Copy", onCopy)
                if (onEdit != null) {
                    // "Edit" is what §9f calls it and what the gesture means to
                    // a person: change what I said. What it *does* is rewind,
                    // and the confirmation is where that gets explained — a menu
                    // row is the wrong place to teach a concept.
                    MenuRow(Icons.Outlined.Undo, "Edit", onEdit)
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
private const val LIFTED_LINES = 8
