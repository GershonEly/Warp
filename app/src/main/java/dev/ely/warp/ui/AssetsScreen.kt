package dev.ely.warp.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import dev.ely.warp.ai.ImageGen
import dev.ely.warp.build.IconStudio
import dev.ely.warp.build.Projects
import dev.ely.warp.data.ImageModels
import dev.ely.warp.ui.theme.WarpMono
import dev.ely.warp.ui.theme.WarpSpace
import dev.ely.warp.ui.theme.WarpSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Your app's face — §8, and the screen the drawer has been promising.
 *
 * Assets has been a destination since §9d and a *"coming soon"* card ever since.
 * It is here rather than only in chat because looking at your app's icon is a
 * different mood from building: you are not asking for anything, you are
 * checking, and being made to open a conversation and type a sentence to see a
 * picture you already own is the wrong shape for that.
 *
 * Two levels like Files, and for the same reason — an assets screen that shows
 * whichever app the open chat happens to point at is invisible state deciding
 * what a screen says.
 */
@Composable
fun AssetsScreen(
    apps: List<Projects.App>,
    folderFor: (Projects.App) -> File,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    initial: Projects.App? = null,
) {
    var chosen by remember(initial, apps) {
        mutableStateOf(initial ?: apps.singleOrNull())
    }

    BackHandler(enabled = chosen != null && apps.size > 1) { chosen = null }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = WarpSpace.screen)) {
        val app = chosen
        if (app == null) {
            AssetsAppList(apps) { chosen = it }
        } else {
            IconStudioPanel(
                app = app,
                project = folderFor(app),
                showBack = apps.size > 1,
                onBack = { chosen = null },
                onOpenSettings = onOpenSettings,
            )
        }
    }
}

@Composable
private fun AssetsAppList(apps: List<Projects.App>, onPick: (Projects.App) -> Unit) {
    Text(
        "Assets",
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.padding(top = WarpSpace.large),
    )

    if (apps.isEmpty()) {
        Text(
            // What to do, not what is missing.
            "Nothing built yet. Ask for an app in a chat, and its icon appears here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = WarpSpace.medium),
        )
        return
    }

    Text(
        "Pick an app to change its icon.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = WarpSpace.small),
    )
    Spacer(Modifier.size(WarpSpace.medium))

    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        apps.forEach { app ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(app) }
                    .padding(vertical = 10.dp),
            ) {
                // The real icon where the shelf shows a coloured letter — on the
                // screen that is about icons, a stand-in for one would be odd.
                IconTile(app, size = 38.dp, rounding = 11.dp)
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
            }
        }
    }
}

/**
 * One app: what its icon is, and a box to say what it should be.
 *
 * The price is on the button rather than beside it. A button that says **Draw
 * it** and quietly costs fifteen cents is the thing this whole screen is trying
 * not to be — you should not have to have read Settings to know what a tap
 * costs.
 */
@Composable
private fun IconStudioPanel(
    app: Projects.App,
    project: File,
    showBack: Boolean,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var description by remember(app.conversationId) { mutableStateOf("") }
    var drawing by remember(app.conversationId) { mutableStateOf(false) }
    var message by remember(app.conversationId) { mutableStateOf<String?>(null) }
    var failed by remember(app.conversationId) { mutableStateOf(false) }
    // Bumped after a successful draw so the previews are read from disk again.
    // The files keep their names, so nothing else would tell Compose they changed.
    var version by remember(app.conversationId) { mutableIntStateOf(0) }

    val model = remember(version) { ImageModels.chosen(context) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = WarpSpace.small),
    ) {
        if (showBack) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
            }
            Spacer(Modifier.size(WarpSpace.tiny))
        }
        Text(
            app.name,
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }

    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        Spacer(Modifier.size(WarpSpace.medium))

        // Both shapes, because both are real. Which one a phone uses is the
        // launcher's choice, and an icon that only works as a square is an icon
        // that is broken on half the phones it lands on.
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(WarpSpace.large),
        ) {
            PreviewTile(project, round = false, version = version, size = 96.dp, label = "Square")
            PreviewTile(project, round = true, version = version, size = 64.dp, label = "Circle")
        }

        Spacer(Modifier.size(WarpSpace.section))

        Text("Describe a new one", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.size(WarpSpace.small))
        OutlinedTextField(
            value = description,
            onValueChange = { description = it },
            placeholder = { Text("a green paper plane, flat, simple") },
            enabled = !drawing,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            // Said once, here, rather than left for people to discover by
            // spending money on an icon with a misspelled word across it.
            "Subject only. Flat style, safe margins and no lettering are added " +
                "for you — words come out misspelled and are unreadable at icon size.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = WarpSpace.small),
        )

        Spacer(Modifier.size(WarpSpace.large))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                enabled = !drawing && description.isNotBlank(),
                onClick = {
                    drawing = true
                    message = null
                    scope.launch {
                        val outcome = withContext(Dispatchers.IO) {
                            IconStudio.draw(context, project, description)
                        }
                        drawing = false
                        when (outcome) {
                            is IconStudio.Outcome.Failed -> {
                                failed = true
                                message = outcome.message
                            }
                            is IconStudio.Outcome.Drawn -> {
                                failed = false
                                message = "New icon written · " + (
                                    outcome.costUsd
                                        ?.let { "${Math.round(it * 100).toInt().coerceAtLeast(1)}¢" }
                                        ?: "about ${ImageGen.cents(model)}"
                                    ) + "."
                                version++
                            }
                        }
                    }
                },
            ) {
                if (drawing) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.size(WarpSpace.small))
                    Text("Drawing…")
                } else {
                    Text("Draw it · ${ImageGen.cents(model)}")
                }
            }
            Spacer(Modifier.size(WarpSpace.medium))
            Text(
                // Tapping goes to the picker, because the moment you notice the
                // name is the moment you might want a different one.
                model.name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable(enabled = !drawing) { onOpenSettings() },
            )
        }

        message?.let {
            Spacer(Modifier.size(WarpSpace.medium))
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = if (failed) MaterialTheme.colorScheme.error else WarpSuccess,
            )
        }

        // The whole reason the first drawn icon looked like a failure.
        //
        // A new icon is a file in `res/`, and the app on the home screen is an
        // APK compiled before it existed. The old message said *"rebuild to see
        // it"* and this screen offered no way to rebuild, so the obvious move
        // was to reinstall — which puts back the same APK, with the same old
        // icon, and reads as the drawing not having worked at all.
        //
        // Shown whenever the built app is behind, not only after a draw: the
        // same gap opens every time a file is edited, and it was silent then too.
        val behind = remember(project, version, drawing) {
            dev.ely.warp.build.NewProject.staleReason(project)
        }
        if (behind != null) {
            Spacer(Modifier.size(WarpSpace.large))
            BuildAndInstall(project, enabled = !drawing)
        }

        Spacer(Modifier.size(WarpSpace.section))
    }
}

/**
 * Compile the app and hand it to Android's installer.
 *
 * Runs the real `build` tool rather than a second call into the engine. That
 * tool already writes missing icons, copies the APK out of the shared work
 * directory and records where it landed — a screen doing two of those three
 * would be a screen that works until somebody changes the third.
 *
 * It does **not** claim the app was installed. Android's installer is a separate
 * screen somebody has to agree to, and no app can tap it for them.
 */
@Composable
private fun BuildAndInstall(project: File, enabled: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember(project) { mutableStateOf(false) }
    var note by remember(project) { mutableStateOf<String?>(null) }
    var wrong by remember(project) { mutableStateOf(false) }

    Text(
        "The installed app was built before this icon, so it still shows the old one.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.size(WarpSpace.small))

    Button(
        enabled = enabled && !running,
        onClick = {
            running = true
            note = null
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    dev.ely.warp.tools.BuildProject.run(
                        dev.ely.warp.tools.ToolEnv(project = project, context = context),
                        org.json.JSONObject(),
                    )
                }
                when (result) {
                    is dev.ely.warp.tools.ToolResult.Failed -> {
                        wrong = true
                        note = result.reason.lineSequence().first()
                    }
                    is dev.ely.warp.tools.ToolResult.Ok -> {
                        val apk = dev.ely.warp.build.NewProject.lastApk(project)
                        val failure = apk?.let {
                            dev.ely.warp.build.Installer.open(context, it)
                        }
                        wrong = failure != null
                        note = failure
                            ?: "${result.summary} — confirm the install on screen."
                    }
                }
                running = false
            }
        },
    ) {
        if (running) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier.size(16.dp),
                color = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(Modifier.size(WarpSpace.small))
            Text("Building…")
        } else {
            Text("Build and install")
        }
    }

    note?.let {
        Spacer(Modifier.size(WarpSpace.small))
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = if (wrong) MaterialTheme.colorScheme.error else WarpSuccess,
        )
    }
}

/** One preview, read from the project's own `res/` rather than from memory. */
@Composable
private fun PreviewTile(
    project: File,
    round: Boolean,
    version: Int,
    size: androidx.compose.ui.unit.Dp,
    label: String,
) {
    val file = remember(project, round, version) {
        if (round) IconStudio.currentRound(project) else IconStudio.current(project)
    }
    val bitmap: ImageBitmap? = remember(file, version) {
        file?.let {
            runCatching { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }.getOrNull()
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(size)
                .clip(if (round) CircleShape else RoundedCornerShape(size / 4.5f))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = label,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // A project with no icon yet is a real state — an app made
                // before icons existed, or one whose res/ was emptied by hand.
                Text(
                    "—",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.size(WarpSpace.small))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The app's real icon, falling back to the shelf's coloured letter. */
@Composable
private fun IconTile(app: Projects.App, size: androidx.compose.ui.unit.Dp, rounding: androidx.compose.ui.unit.Dp) {
    val context = LocalContext.current
    val bitmap: ImageBitmap? = remember(app.conversationId) {
        IconStudio.current(Projects.forConversation(context, app.conversationId))?.let {
            runCatching { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }.getOrNull()
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(rounding))
            .background(Color(app.colour)),
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                app.name.trim().firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
        }
    }
}
