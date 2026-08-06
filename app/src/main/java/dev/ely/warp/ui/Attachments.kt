package dev.ely.warp.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ely.warp.ai.Attachment
import dev.ely.warp.data.AttachmentStore
import dev.ely.warp.ui.theme.HairlineWidth
import dev.ely.warp.ui.theme.WarpSpace

/**
 * Showing it something — §5h.
 *
 * You could always tell Warp what was wrong; you could not show it. Everything
 * here exists to close that gap, and it is deliberately small: a button beside
 * the send key, and a row of what is waiting to go.
 */

/**
 * The one that opens the picker.
 *
 * `GetContent` rather than the photo picker, for the same reason as the app
 * icon: it needs no runtime permission on any version, and `targetSdk 28` puts
 * Warp on the old storage model where asking for `READ_EXTERNAL_STORAGE` would
 * be far broader than "one file".
 *
 * Every type is offered, not only images. A log, a stack trace or a `.kt` needs
 * no vision model at all and is the most useful attachment per unit of work —
 * restricting the picker to pictures would hide the cheapest half of the
 * feature behind the half that costs the most.
 */
@Composable
fun AttachButton(onAttach: (Attachment) -> Unit) {
    val context = LocalContext.current

    val pick = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            // Copied now, not read later. A `content://` URI is permission to
            // read something at this moment rather than a place, and reopening
            // one after a reboot fails — so a message would lose its picture
            // exactly when someone went back to look at it.
            AttachmentStore.take(context, "_pending", uri).getOrNull()?.let(onAttach)
        }
    }

    Surface(
        shape = CircleShape,
        color = Color.Transparent,
        modifier = Modifier.size(36.dp),
        onClick = { pick.launch("*/*") },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Outlined.Add,
                contentDescription = "Attach a file",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * What is waiting to be sent, above the field.
 *
 * With a way to take each one back off. An attachment you cannot remove is one
 * you have to clear the whole message to be rid of — and pictures are the most
 * expensive thing in a conversation to send by accident.
 */
@Composable
fun PendingAttachments(
    attachments: List<Attachment>,
    onRemove: (Attachment) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (attachments.isEmpty()) return

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(WarpSpace.small),
        modifier = modifier.padding(bottom = WarpSpace.small),
    ) {
        items(attachments, key = { it.id }) { attachment ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(HairlineWidth, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(
                        start = WarpSpace.small,
                        end = WarpSpace.tiny,
                        top = WarpSpace.tiny,
                        bottom = WarpSpace.tiny,
                    ),
                ) {
                    Text(
                        attachment.label(),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(end = WarpSpace.tiny),
                    )
                    Surface(
                        shape = CircleShape,
                        color = Color.Transparent,
                        modifier = Modifier.size(22.dp),
                        onClick = { onRemove(attachment) },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = "Remove ${attachment.name}",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * What went with a message that has already been sent.
 *
 * Read-only, and part of the transcript rather than a control: taking something
 * back after the model has read it would be a lie about what the conversation
 * contains.
 */
@Composable
fun SentAttachments(attachments: List<Attachment>, modifier: Modifier = Modifier) {
    if (attachments.isEmpty()) return

    Row(
        horizontalArrangement = Arrangement.spacedBy(WarpSpace.tiny),
        modifier = modifier.padding(top = WarpSpace.tiny),
    ) {
        attachments.take(4).forEach { attachment ->
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Text(
                    attachment.label(),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(
                        horizontal = WarpSpace.small,
                        vertical = WarpSpace.tiny,
                    ),
                )
            }
        }
    }
}

/**
 * The name, and the size when it is worth knowing.
 *
 * Kilobytes on a chip because that is what an attachment costs you, and a
 * screenshot at three megabytes should look different from a stack trace at
 * four kilobytes before it is sent rather than afterwards.
 */
private fun Attachment.label(): String {
    val short = if (name.length <= 22) name else name.take(19) + "…"
    val size = when {
        bytes >= 1024 * 1024 -> "${bytes / 1024 / 1024} MB"
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }
    return "$short · $size"
}
