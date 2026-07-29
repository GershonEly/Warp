package dev.ely.warp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ely.warp.data.Folder
import dev.ely.warp.ui.theme.WarpRadius
import dev.ely.warp.ui.theme.WarpSpace
import kotlin.math.roundToInt

/**
 * Where folders are made, named, ordered and removed.
 *
 * A sheet of its own rather than editing in place in the drawer, and the reason
 * is the reordering. Dragging an item inside a scrolling container fights the
 * scroll — you reach to move something and the list runs away under your finger.
 * A dedicated surface, where the only vertical gesture that means anything is
 * the drag, makes it unambiguous. Creating and renaming moved here too, because
 * one place that manages folders beats three places that each manage a bit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageFoldersSheet(
    folders: List<Folder>,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (Folder) -> Unit,
    onReorder: (List<Folder>) -> Unit,
) {
    // The working copy. A drag has to redraw on every frame, and driving that
    // from the database would mean a write per frame and a list that lags a
    // finger by a round trip. The order is committed when the finger lifts.
    var order by remember(folders) { mutableStateOf(folders) }

    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Folder?>(null) }
    var deleting by remember { mutableStateOf<Folder?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = WarpSpace.screen)
                .padding(bottom = WarpSpace.large),
        ) {
            Text("Folders", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.size(WarpSpace.tiny))
            Text(
                if (order.isEmpty()) {
                    "Group conversations however you like. Flat — no folders inside folders."
                } else {
                    "Hold the handle to drag a folder into place."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.size(WarpSpace.medium))

            DraggableFolderList(
                folders = order,
                onOrderChanged = { order = it },
                onSettled = { onReorder(order) },
                onRename = { renaming = it },
                onDelete = { deleting = it },
            )

            Spacer(Modifier.size(WarpSpace.medium))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(WarpRadius.small))
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable { creating = true }
                    .padding(WarpSpace.medium),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Outlined.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.size(WarpSpace.small))
                Text(
                    "New folder",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }

    if (creating) {
        NameDialog(
            title = "New folder",
            initial = "",
            confirmLabel = "Create",
            onDismiss = { creating = false },
            onConfirm = {
                creating = false
                onCreate(it)
            },
        )
    }

    renaming?.let { folder ->
        NameDialog(
            title = "Rename folder",
            initial = folder.name,
            confirmLabel = "Save",
            onDismiss = { renaming = null },
            onConfirm = {
                renaming = null
                onRename(folder.id, it)
            },
        )
    }

    deleting?.let { folder ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete folder?") },
            // Said plainly, because the alternative reading is the frightening
            // one. Deleting a folder must never look like it might take the
            // conversations with it.
            text = {
                Text(
                    "\"${folder.name}\" will be removed. The conversations inside " +
                        "it are kept — they move back to Recent."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleting = null
                        onDelete(folder)
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("Cancel") }
            },
        )
    }
}

/**
 * The list, with drag to reorder.
 *
 * A plain Column rather than a LazyColumn, deliberately. Folders are a handful,
 * every row is the same fixed height, and knowing that turns the drag into
 * arithmetic — how many row-heights has the finger travelled — instead of a
 * hit-test against items that may not be composed. It is also what lets this be
 * written without a drag-and-drop dependency.
 */
@Composable
private fun DraggableFolderList(
    folders: List<Folder>,
    onOrderChanged: (List<Folder>) -> Unit,
    onSettled: () -> Unit,
    onRename: (Folder) -> Unit,
    onDelete: (Folder) -> Unit,
) {
    val rowHeightPx = with(LocalDensity.current) { ROW_HEIGHT.toPx() }

    var draggingIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableStateOf(0f) }

    Column {
        folders.forEachIndexed { index, folder ->
            val dragging = index == draggingIndex

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ROW_HEIGHT)
                    .graphicsLayer {
                        // The dragged row rides above the others and lifts
                        // slightly, so it reads as picked up rather than as the
                        // list having glitched.
                        if (dragging) {
                            translationY = dragOffset
                            shadowElevation = 8f
                            scaleX = 1.02f
                            scaleY = 1.02f
                        }
                    }
                    .clip(RoundedCornerShape(WarpRadius.small))
                    .background(
                        if (dragging) MaterialTheme.colorScheme.surfaceContainerHighest
                        else MaterialTheme.colorScheme.surfaceContainer
                    )
                    .padding(horizontal = WarpSpace.medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.DragHandle,
                    contentDescription = "Reorder",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(20.dp)
                        .pointerInput(folders) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    draggingIndex = index
                                    dragOffset = 0f
                                },
                                onDragEnd = {
                                    draggingIndex = -1
                                    dragOffset = 0f
                                    onSettled()
                                },
                                onDragCancel = {
                                    draggingIndex = -1
                                    dragOffset = 0f
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragOffset += amount.y

                                    // Swap as soon as the finger has travelled a
                                    // full row, so the list rearranges under the
                                    // drag rather than all at once on release —
                                    // you can see where it will land before you
                                    // commit to it.
                                    val steps = (dragOffset / rowHeightPx).roundToInt()
                                    if (steps != 0) {
                                        val from = draggingIndex
                                        val to = (from + steps)
                                            .coerceIn(0, folders.lastIndex)
                                        if (to != from) {
                                            onOrderChanged(folders.moved(from, to))
                                            draggingIndex = to
                                            dragOffset -= (to - from) * rowHeightPx
                                        }
                                    }
                                },
                            )
                        },
                )

                Spacer(Modifier.size(WarpSpace.medium))
                Text(
                    folder.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )

                Icon(
                    Icons.Outlined.Edit,
                    contentDescription = "Rename ${folder.name}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(WarpRadius.small))
                        .clickable { onRename(folder) }
                        .padding(WarpSpace.small)
                        .size(18.dp),
                )
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = "Delete ${folder.name}",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .clip(RoundedCornerShape(WarpRadius.small))
                        .clickable { onDelete(folder) }
                        .padding(WarpSpace.small)
                        .size(18.dp),
                )
            }

            Spacer(Modifier.size(WarpSpace.small))
        }
    }
}

private fun <T> List<T>.moved(from: Int, to: Int): List<T> =
    toMutableList().apply { add(to, removeAt(from)) }

/** One text field and two buttons, for creating and for renaming. */
@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember {
        mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length)))
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text("Folder name") },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text.text) },
                enabled = text.text.isNotBlank(),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private val ROW_HEIGHT = 52.dp
