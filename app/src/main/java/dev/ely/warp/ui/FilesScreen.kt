package dev.ely.warp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import dev.ely.warp.ui.theme.WarpSuccess
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ely.warp.build.Projects
import dev.ely.warp.ui.theme.WarpMono
import dev.ely.warp.ui.theme.WarpSpace
import java.io.File

/**
 * Two levels: your apps, then one app's files.
 *
 * The first version listed only the open chat's app, which meant Files showed
 * either your code or "nothing here yet" with **no way to tell which chat it was
 * reading**. That is invisible state deciding what a screen says, and it is
 * exactly the kind of thing that reads as broken rather than as empty.
 *
 * So it browses. §9d keeps Files a destination because *browsing a project is a
 * real place* — and a place you can only reach by first opening the right
 * conversation is not one.
 */
@Composable
fun FilesScreen(
    apps: List<Projects.App>,
    /** Where an app's files live. */
    folderFor: (Projects.App) -> File,
    onOpen: (File) -> Unit,
    modifier: Modifier = Modifier,
    /** Start inside this app, when arriving from the shelf. */
    initial: Projects.App? = null,
) {
    var chosen by remember(initial, apps) {
        mutableStateOf(initial ?: apps.singleOrNull())
    }

    // Back goes up a level before it leaves the screen, which is what "into a
    // folder" has meant on every phone for fifteen years.
    BackHandler(enabled = chosen != null && apps.size > 1) { chosen = null }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = WarpSpace.screen)) {
        val app = chosen
        if (app == null) {
            AppList(apps) { chosen = it }
        } else {
            FileList(
                app = app,
                folder = folderFor(app),
                showBack = apps.size > 1,
                onBack = { chosen = null },
                onOpen = onOpen,
            )
        }
    }
}

@Composable
private fun AppList(apps: List<Projects.App>, onPick: (Projects.App) -> Unit) {
    var query by remember { mutableStateOf("") }
    val shown = remember(apps, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) apps
        else apps.filter { q in it.name.lowercase() || q in it.applicationId.lowercase() }
    }

    Text(
        "Files",
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.padding(top = WarpSpace.large),
    )

    if (apps.isEmpty()) {
        Text(
            // What to do, not what is missing. An empty list and a broken list
            // look the same.
            "Nothing built yet. Ask for an app in a chat and its files appear here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = WarpSpace.medium),
        )
        return
    }

    // Only once there is enough to search. A search box above two rows is
    // furniture pretending to be a feature.
    if (apps.size >= 4) {
        Spacer(Modifier.size(WarpSpace.small))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search apps") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    Spacer(Modifier.size(WarpSpace.small))
    Text(
        if (query.isBlank()) "${apps.size} apps" else "${shown.size} of ${apps.size}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.size(WarpSpace.small))

    LazyColumn {
        items(items = shown, key = { a: Projects.App -> a.conversationId }) { app ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(app) }
                    .padding(vertical = 10.dp),
            ) {
                // The same tile as the shelf, so an app is visibly the same
                // object in both places rather than two things that happen to
                // share a name.
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(Color(app.colour)),
                ) {
                    Text(
                        app.name.trim().firstOrNull()?.uppercase() ?: "?",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                    )
                }
                Spacer(Modifier.size(WarpSpace.medium))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        app.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        app.applicationId,
                        style = WarpMono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    "${app.fileCount} files",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FileList(
    app: Projects.App,
    folder: File,
    showBack: Boolean,
    onBack: () -> Unit,
    onOpen: (File) -> Unit,
) {
    val all = remember(folder, folder.lastModified()) {
        folder.walkTopDown()
            .filter { it.isFile }
            // Output, not source. Listing it invites tapping 700 KB of zip.
            .filter { it.name != "app.apk" && it.name != "warp.json" }
            // git's own bookkeeping is not the project — §5i item 9.
            .filter { !it.invariantPath(folder).startsWith(".git/") }
            .sortedBy { it.invariantPath(folder) }
            .toList()
    }

    // What the model touched, at a glance — §5i item 9.
    //
    // One call for the whole list rather than one per row: this walks the tree,
    // and doing it per file would walk it once for every file. Empty when the
    // project is not in git, which is what makes the dots simply not appear
    // rather than appear wrong.
    val status = remember(folder, folder.lastModified()) {
        runCatching {
            if (dev.ely.warp.git.Repo.isRepo(folder)) {
                dev.ely.warp.git.Repo.status(folder)
            } else {
                emptyMap()
            }
        }.getOrElse { emptyMap() }
    }

    var query by remember(folder) { mutableStateOf("") }
    val files = remember(all, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) all else all.filter { q in it.invariantPath(folder).lowercase() }
    }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        if (showBack) {
            IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "All apps")
            }
            Spacer(Modifier.size(4.dp))
        }
        Column {
            Text(app.name, style = MaterialTheme.typography.titleMedium)
            Text(
                // Says how many have changed, because that is the number you
                // are looking for after a turn — "18 files" answers nothing.
                buildString {
                    append("${all.size} files")
                    val touched = status.size
                    if (touched > 0) append(" · $touched changed")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // Same rule the app list uses: a search box over a handful of rows is
    // furniture pretending to be a feature.
    if (all.size >= 8) {
        Spacer(Modifier.size(WarpSpace.small))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Find a file") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Spacer(Modifier.size(WarpSpace.small))

    if (files.isEmpty()) {
        Text(
            "No file here matches “${query.trim()}”.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = WarpSpace.medium),
        )
        return
    }

    LazyColumn {
        items(items = files, key = { f: File -> f.absolutePath }) { file ->
            FileRow(file, folder, status[file.invariantPath(folder)]) { onOpen(file) }
        }
    }
}

@Composable
private fun FileRow(
    file: File,
    project: File,
    /** Changed since the last save point, or new, or null for untouched. */
    state: dev.ely.warp.git.Repo.State? = null,
    onClick: () -> Unit,
) {
    val folder = file.invariantPath(project).substringBeforeLast('/', "")

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        // A dot before the icon, or the space where one would be — §5i item 9.
        //
        // The space is kept even when there is nothing to say, so the file names
        // stay on one line down the list. Names that shift sideways depending on
        // whether a file changed is a list that is harder to read than one with
        // no dots at all.
        Box(modifier = Modifier.size(8.dp)) {
            if (state != null) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(
                            when (state) {
                                // New is the louder of the two on purpose: a
                                // file that did not exist before is the bigger
                                // surprise when you are scanning for what
                                // happened.
                                dev.ely.warp.git.Repo.State.NEW -> WarpSuccess
                                else -> MaterialTheme.colorScheme.primary
                            }
                        )
                )
            }
        }
        Spacer(Modifier.size(WarpSpace.small))

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
                // The folder under the name rather than a tree with chevrons. A
                // project here is five files deep at most, and a tree would be
                // three taps to reach what one line already shows.
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
