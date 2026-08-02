package dev.ely.warp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ely.warp.build.Installer
import dev.ely.warp.build.Projects
import dev.ely.warp.ui.theme.WarpMono
import dev.ely.warp.ui.theme.WarpSpace
import dev.ely.warp.ui.theme.WarpSuccess

/**
 * The shelf — §9h.
 *
 * A grid of apps this phone compiled. It exists because Warp's design borrowed
 * the visual language of products that make **messages**, while Warp makes an
 * APK; the thing the whole project is for was a log line on a tab you had to go
 * and visit.
 *
 * No icons yet — those are Step 8 — so each app shows a coloured tile with its
 * initial. The colour is derived from the **application id**, which is at least
 * stable and yours, rather than from a hash of a conversation id. §9h is blunt
 * about why that matters: *colour derived from a hash is decoration pretending
 * to be meaning.* This is a placeholder standing in for a real icon's dominant
 * colour, and it is labelled as such rather than pretending to be the idea.
 */
@Composable
fun AppsScreen(
    apps: List<Projects.App>,
    onOpenChat: (String) -> Unit,
    onOpenFiles: (Projects.App) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf<Projects.App?>(null) }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = WarpSpace.screen)) {
        Text(
            "Your apps",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = WarpSpace.large, bottom = WarpSpace.small),
        )

        if (apps.isEmpty()) {
            // Says how one is made, because an empty shelf with no explanation
            // reads as a broken screen rather than a new one.
            Text(
                "Nothing built yet. Ask for an app in a chat — it appears here " +
                    "the moment the first file is written.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = WarpSpace.medium),
            )
            return@Column
        }

        Text(
            "${apps.size} built on this phone",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(WarpSpace.medium))

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 104.dp),
            horizontalArrangement = Arrangement.spacedBy(WarpSpace.medium),
            verticalArrangement = Arrangement.spacedBy(WarpSpace.medium),
        ) {
            items(apps, key = { it.conversationId }) { app ->
                AppTile(app) { open = app }
            }
        }
    }

    open?.let { app ->
        AppSheet(
            app,
            onDismiss = { open = null },
            onOpenChat = onOpenChat,
            onOpenFiles = { open = null; onOpenFiles(it) },
        )
    }
}

@Composable
private fun AppTile(app: Projects.App, onClick: () -> Unit) {
    val context = LocalContext.current
    val installed = remember(app.applicationId) {
        Installer.isInstalled(context, app.applicationId)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                // Rounded like a launcher icon, because that is what it stands
                // in for. A square would read as a file, not an app.
                .clip(RoundedCornerShape(22.dp))
                .background(Color(app.colour)),
        ) {
            Text(
                app.name.trim().firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
        }
        Spacer(Modifier.size(6.dp))
        Text(
            app.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            // The one line that says where this app actually is. §9h's mock-up
            // has exactly this under every tile.
            when {
                installed -> "installed"
                app.built -> "built"
                else -> "${app.fileCount} files"
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (installed) WarpSuccess else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppSheet(
    app: Projects.App,
    onDismiss: () -> Unit,
    onOpenChat: (String) -> Unit,
    onOpenFiles: (Projects.App) -> Unit,
) {
    val context = LocalContext.current
    var note by remember { mutableStateOf<String?>(null) }
    val installed = Installer.isInstalled(context, app.applicationId)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = WarpSpace.screen)
                .padding(bottom = WarpSpace.large),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(app.colour)),
                ) {
                    Text(
                        app.name.trim().firstOrNull()?.uppercase() ?: "?",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                    )
                }
                Spacer(Modifier.size(WarpSpace.medium))
                Column {
                    Text(app.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        app.applicationId,
                        style = WarpMono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.size(WarpSpace.medium))
            Text(
                buildString {
                    append("${app.fileCount} files")
                    if (app.built) append(" · APK ${app.apkBytes / 1024} KB")
                    append(if (installed) " · installed" else " · not installed")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            note?.let {
                Spacer(Modifier.size(WarpSpace.small))
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.size(WarpSpace.medium))
            Row(horizontalArrangement = Arrangement.spacedBy(WarpSpace.small)) {
                // Disabled rather than hidden when there is no APK, so the
                // button teaches you that building comes first instead of
                // leaving you wondering where Install went.
                Button(
                    onClick = {
                        note = dev.ely.warp.build.NewProject
                            .lastApk(Projects.forConversation(context, app.conversationId))
                            ?.let { Installer.open(context, it) }
                            ?: "nothing built yet — ask the chat to build it"
                    },
                    enabled = app.built,
                ) { Text(if (installed) "Reinstall" else "Install") }

                if (installed) {
                    TextButton(onClick = {
                        note = Installer.launch(context, app.applicationId)
                    }) { Text("Open") }
                }

                // Straight into the code from the shelf. Tapping an app and
                // being offered everything except a way to see what it is made
                // of was the gap that made Files hard to find at all.
                TextButton(onClick = { onOpenFiles(app) }) { Text("Files") }

                Spacer(Modifier.weight(1f))
                // §9h: "the way into the conversation that made it".
                TextButton(onClick = { onOpenChat(app.conversationId) }) { Text("Chat") }
            }
        }
    }
}

/**
 * A stand-in for the icon's dominant colour.
 *
 * Derived from the application id, so it is at least stable and comes from
 * something you chose. **This is a placeholder for Step 8**, where a real icon
 * exists and the colour is taken from it — which is the actual idea in §9h, and
 * the reason a hash of the conversation id was rejected.
 *
 * Every hue is kept dark enough for white text to sit on it. §9h is explicit
 * that legibility outranks the effect entirely.
 */
internal fun appTileColour(applicationId: String): Color {
    val hue = ((applicationId.hashCode() % 360) + 360) % 360
    return Color.hsv(hue.toFloat(), saturation = 0.55f, value = 0.55f)
}
