package dev.ely.warp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ely.warp.ui.theme.WarpMono
import dev.ely.warp.ui.theme.WarpSpace
import java.io.File

/**
 * The files of the app you are working on.
 *
 * A real destination, unlike the editor: §9d settles that *browsing a project is
 * a real place*, while an editor is something you open over what you were doing
 * and close again. Nobody starts their day in an editor on a phone.
 *
 * It follows the open conversation, because files belong to a chat now. Showing
 * "the project" without saying which one was fine when there was only ever one;
 * with a shelf full of apps it would be a screen that quietly lies.
 */
@Composable
fun FilesScreen(
    /** The app's folder, or null when this chat has not built anything. */
    project: File?,
    /** What the app is called, for the header. */
    appName: String?,
    onOpen: (File) -> Unit,
    modifier: Modifier = Modifier,
) {
    val files = remember(project, project?.lastModified()) {
        project?.walkTopDown()
            ?.filter { it.isFile }
            // The APK is an output, not source. Listing it invites tapping it,
            // and there is nothing useful to show for 700 KB of zip.
            ?.filter { it.name != "app.apk" }
            ?.sortedBy { it.invariantPath(project) }
            ?.toList()
            .orEmpty()
    }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = WarpSpace.screen)) {
        Text(
            "Files",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = WarpSpace.large),
        )

        if (project == null || files.isEmpty()) {
            Text(
                // Says what to do rather than that something is missing. An
                // empty file list and a broken file list look identical.
                "This chat has not built an app yet. Ask for one, and its files " +
                    "appear here as they are written.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = WarpSpace.medium),
            )
            return@Column
        }

        Text(
            "${appName ?: "This app"} · ${files.size} files",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(WarpSpace.medium))

        LazyColumn {
            items(items = files, key = { f: File -> f.absolutePath }) { file ->
                FileRow(file, project) { onOpen(file) }
            }
        }
    }
}

@Composable
private fun FileRow(file: File, project: File, onClick: () -> Unit) {
    val path = file.invariantPath(project)
    val folder = path.substringBeforeLast('/', "")

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        Icon(
            if (folder.isEmpty()) Icons.Outlined.Description else Icons.Outlined.FolderOpen,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.size(WarpSpace.medium))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                file.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (folder.isNotEmpty()) {
                // The folder under the name rather than a tree with chevrons.
                // A project here is five files deep at most, and an expandable
                // tree would be three taps to reach what one line can show.
                Text(
                    folder,
                    style = WarpMono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }

        Text(
            file.length().asSize(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The path inside the project, with forward slashes whatever the platform. */
internal fun File.invariantPath(root: File): String =
    relativeTo(root).path.replace(File.separatorChar, '/')

private fun Long.asSize(): String = when {
    this < 1024 -> "$this B"
    this < 1024 * 1024 -> "${this / 1024} KB"
    else -> "${this / (1024 * 1024)} MB"
}
