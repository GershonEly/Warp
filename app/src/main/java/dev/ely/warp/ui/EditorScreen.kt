package dev.ely.warp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ely.warp.ui.theme.WarpMono
import dev.ely.warp.ui.theme.WarpSpace
import java.io.File

/**
 * One file, opened over whatever you were doing.
 *
 * **Not a destination**, and §9d is explicit about why: on a desktop the editor
 * is the app and chat is a side panel, and on a phone it is the other way round.
 * Nobody writes Kotlin on a phone keyboard. But an editor is still needed — to
 * read what the AI just wrote, or to fix one line yourself rather than spending
 * a whole turn asking for it. *That is something you open, not somewhere you go.*
 *
 * Reading is the common case and is what the layout is built around; editing is
 * the exception, and only starts when you tap Edit.
 */
@Composable
fun EditorScreen(
    file: File,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val original = remember(file) {
        runCatching { file.readText() }.getOrElse { "could not read this file: ${it.message}" }
    }
    var editing by remember(file) { mutableStateOf(false) }
    var value by remember(file) { mutableStateOf(TextFieldValue(original)) }
    var saved by remember(file) { mutableStateOf<String?>(null) }
    val dirty = editing && value.text != original

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // The editor covers the shell, so it also inherits the shell's
                // job of keeping out of the status bar. Without this the file
                // name printed straight over the clock and the battery icon.
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding(),
        ) {

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = WarpSpace.medium, top = 4.dp, bottom = 4.dp),
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Close")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        file.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        // Says what state it is in, because "is this saved?" is
                        // the question an editor has to answer without being asked.
                        when {
                            dirty -> "unsaved changes"
                            saved != null -> saved!!
                            editing -> "editing"
                            else -> "${original.lines().size} lines"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (dirty) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (!editing) {
                    TextButton(onClick = { editing = true; saved = null }) { Text("Edit") }
                } else {
                    TextButton(
                        onClick = {
                            // Written, then read back and compared. A save that
                            // reports success without checking is the same lie
                            // as a tool that answers "ok".
                            val result = runCatching {
                                file.writeText(value.text)
                                file.readText() == value.text
                            }
                            saved = when {
                                result.isFailure -> "could not save: ${result.exceptionOrNull()?.message}"
                                result.getOrDefault(false) -> "saved"
                                else -> "saved, but the file does not match"
                            }
                            editing = false
                        },
                        enabled = dirty,
                    ) { Text("Save") }
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                if (editing) {
                    Editable(value) { value = it }
                } else {
                    Reading(value.text)
                }
            }

            // Only while editing. A key row above the transcript when you are
            // reading would be a permanent strip of clutter for a case that is
            // not happening.
            if (editing) {
                CodingKeys { insert ->
                    val at = value.selection.start
                    value = TextFieldValue(
                        text = value.text.substring(0, at) + insert + value.text.substring(value.selection.end),
                        selection = TextRange(at + insert.length),
                    )
                }
            }
        }
    }
}

/**
 * The file, laid out for reading.
 *
 * Line numbers in their own column so they do not scroll sideways with the code
 * — otherwise the numbers wander off the screen exactly when a long line makes
 * you want them. And **no wrapping**: a wrapped line of code is a different line
 * from the one on disk, and the whole point of opening a file is to see what is
 * actually in it.
 */
@Composable
private fun Reading(text: String) {
    val lines = remember(text) { text.lines() }
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
      val viewport = maxHeight
      Row(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(vertical),
      ) {
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier
                // At least the height of the screen, or the gutter stops where
                // the code stops and leaves a grey block hanging in mid-air.
                // Alignment and background inside a scroll do nothing without a
                // height — this project has been bitten by that twice already.
                .heightIn(min = viewport)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(horizontal = 8.dp, vertical = 10.dp)
                .width(42.dp),
        ) {
            lines.forEachIndexed { i, _ ->
                Text(
                    "${i + 1}",
                    style = WarpMono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Column(
            modifier = Modifier
                .horizontalScroll(horizontal)
                .padding(horizontal = 10.dp, vertical = 10.dp),
        ) {
            lines.forEach { line ->
                Text(line.ifEmpty { " " }, style = WarpMono, softWrap = false)
            }
        }
      }
    }
}

@Composable
private fun Editable(value: TextFieldValue, onChange: (TextFieldValue) -> Unit) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        textStyle = WarpMono.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

/**
 * The row that decides whether editing code on a phone is real.
 *
 * Every one of these is buried two taps deep on a phone keyboard, and they are
 * the characters code is mostly made of. Without this, fixing one line means
 * switching layouts six times, and nobody does it twice.
 */
@Composable
private fun CodingKeys(onInsert: (String) -> Unit) {
    val keys = listOf("{", "}", "(", ")", "=", ":", ".", ",", "\"", "<", ">", "->", "\$", ";")
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        keys.forEach { key ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    key,
                    style = WarpMono,
                    modifier = Modifier
                        .clickable { onInsert(key) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
        Spacer(Modifier.size(4.dp))
    }
}

