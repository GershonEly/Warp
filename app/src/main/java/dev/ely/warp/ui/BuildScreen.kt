package dev.ely.warp.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ely.warp.build.BuildEngine
import dev.ely.warp.build.ApkSigner
import dev.ely.warp.build.SampleProject
import dev.ely.warp.build.Toolchain
import dev.ely.warp.build.ToolchainInstaller
import dev.ely.warp.ui.theme.WarpMono
import dev.ely.warp.ui.theme.WarpSuccess
import dev.ely.warp.ui.theme.WarpWarning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The build screen: install the toolchain, compile a sample app, watch it work.
 *
 * This is the first place the engine actually runs, so it deliberately shows
 * the raw compiler output rather than hiding it — when something breaks, that
 * log is the whole story.
 */
@Composable
fun BuildScreen(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var toolchainState by remember {
        mutableStateOf<ToolchainInstaller.State>(ToolchainInstaller.State.Checking)
    }
    var buildState by remember { mutableStateOf<BuildUiState>(BuildUiState.Idle) }
    val log = remember { mutableStateListOf<String>() }

    LaunchedEffect(Unit) {
        toolchainState = withContext(Dispatchers.IO) { ToolchainInstaller.currentState(context) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        Text(
            text = "Build",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = "Compile an Android app on this phone",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.size(24.dp))

        ToolchainCard(
            state = toolchainState,
            onInstall = {
                scope.launch {
                    toolchainState = ToolchainInstaller.install(context) { toolchainState = it }
                }
            },
            onRemove = {
                scope.launch {
                    ToolchainInstaller.uninstall(context)
                    toolchainState = withContext(Dispatchers.IO) {
                        ToolchainInstaller.currentState(context)
                    }
                }
            },
        )

        Spacer(Modifier.size(16.dp))

        val ready = toolchainState is ToolchainInstaller.State.Installed
        BuildCard(
            enabled = ready && buildState !is BuildUiState.Running,
            state = buildState,
            log = log,
            onBuild = {
                log.clear()
                buildState = BuildUiState.Running(BuildEngine.Stage.PREPARE)
                scope.launch {
                    buildState = runBuild(
                        context = context,
                        onStage = { stage -> buildState = BuildUiState.Running(stage) },
                        onLine = { line ->
                            if (isHarmlessNoise(line)) return@runBuild
                            // Compose snapshot state is safe to touch from the
                            // compiler's output threads. Capped so a chatty
                            // build cannot grow the list without bound.
                            if (log.size > 400) log.removeRange(0, 200)
                            log.add(line)
                        },
                    )
                }
            },
            onInstallApk = { apk -> installApk(context, apk) },
        )
    }
}

// ── state ────────────────────────────────────────────────────────────────

sealed interface BuildUiState {
    data object Idle : BuildUiState
    data class Running(val stage: BuildEngine.Stage) : BuildUiState
    data class Done(val outcome: BuildEngine.Outcome.Success) : BuildUiState
    data class Failed(val outcome: BuildEngine.Outcome.Failure) : BuildUiState
}

private suspend fun runBuild(
    context: Context,
    onStage: (BuildEngine.Stage) -> Unit,
    onLine: (String) -> Unit,
): BuildUiState = withContext(Dispatchers.IO) {
    val toolchain = Toolchain.forContext(context)
    val workRoot = File(context.filesDir, "work").apply { mkdirs() }

    val projectDir = SampleProject.write(File(workRoot, "sample"))

    val signer = ApkSigner(
        toolchain = toolchain,
        keystoreFile = File(context.filesDir, "keys/warp-debug.p12"),
    )
    val engine = BuildEngine(toolchain, workRoot, signer)

    val outcome = engine.build(
        request = BuildEngine.Request(
            projectDir = projectDir,
            applicationId = SampleProject.APPLICATION_ID,
        ),
        onStage = onStage,
        onLine = { onLine(it.text) },
    )

    when (outcome) {
        is BuildEngine.Outcome.Success -> BuildUiState.Done(outcome)
        is BuildEngine.Outcome.Failure -> BuildUiState.Failed(outcome)
    }
}

/**
 * Hand the built APK to Android's package installer.
 *
 * A FileProvider is required: Warp targets API 28, and since API 24 handing a
 * raw `file://` URI to another app throws FileUriExposedException.
 */
private fun installApk(context: Context, apk: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}

// ── pieces ───────────────────────────────────────────────────────────────

@Composable
private fun ToolchainCard(
    state: ToolchainInstaller.State,
    onInstall: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(title = "Toolchain") {
        when (state) {
            is ToolchainInstaller.State.Checking ->
                Text("Checking…", style = MaterialTheme.typography.bodyMedium)

            is ToolchainInstaller.State.NotBundled -> {
                Status("Not included in this APK", MaterialTheme.colorScheme.error)
                Spacer(Modifier.size(8.dp))
                Text(
                    "This build was made without the toolchain. Build the bundle " +
                        "on a PC and reinstall:\n" +
                        "py toolchain/build_toolchain.py --sdk <sdk>",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            is ToolchainInstaller.State.NotInstalled -> {
                Status("Ready to install", WarpWarning)
                Spacer(Modifier.size(4.dp))
                Text(
                    "About 300 MB will be unpacked into Warp's storage. This takes a moment.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(12.dp))
                Button(onClick = onInstall) { Text("Install toolchain") }
            }

            is ToolchainInstaller.State.Installing -> {
                Status("Unpacking…", WarpWarning)
                Spacer(Modifier.size(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.size(8.dp))
                Text(
                    "${state.files} files · ${state.megabytes} MB",
                    style = WarpMono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            is ToolchainInstaller.State.Installed -> {
                Status("Installed", WarpSuccess)
                Spacer(Modifier.size(4.dp))
                Text(
                    "version ${state.version ?: "unknown"} · ${state.megabytes} MB",
                    style = WarpMono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(12.dp))
                OutlinedButton(onClick = onRemove) { Text("Remove") }
            }

            is ToolchainInstaller.State.Failed -> {
                Status(state.message, MaterialTheme.colorScheme.error)
                if (state.detail.isNotBlank()) {
                    Spacer(Modifier.size(6.dp))
                    Text(
                        state.detail,
                        style = WarpMono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.size(12.dp))
                Button(onClick = onInstall) { Text("Try again") }
            }
        }
    }
}

@Composable
private fun BuildCard(
    enabled: Boolean,
    state: BuildUiState,
    log: List<String>,
    onBuild: () -> Unit,
    onInstallApk: (File) -> Unit,
) {
    Card(title = "Sample app") {
        Text(
            "A one-screen Kotlin app. It uses R.string, so the whole " +
                "resource chain has to work.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(12.dp))

        when (state) {
            is BuildUiState.Idle ->
                Button(onClick = onBuild, enabled = enabled) { Text("Build APK") }

            is BuildUiState.Running -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(12.dp))
                    Text(state.stage.label, style = MaterialTheme.typography.titleSmall)
                }
            }

            is BuildUiState.Done -> {
                Status("Built successfully", WarpSuccess)
                Spacer(Modifier.size(8.dp))
                Ltr {
                    Text(
                        "${state.outcome.apk.name} · " +
                            "${state.outcome.apk.length() / 1024} KB · " +
                            "${state.outcome.totalMs / 1000}s",
                        style = WarpMono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.size(10.dp))
                StageTimes(state.outcome.stages)
                Spacer(Modifier.size(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.outcome.signed) {
                        Button(onClick = { onInstallApk(state.outcome.apk) }) { Text("Install it") }
                    }
                    OutlinedButton(onClick = onBuild) { Text("Build again") }
                }
            }

            is BuildUiState.Failed -> {
                Status("Failed at: ${state.outcome.stage.label}", MaterialTheme.colorScheme.error)
                Spacer(Modifier.size(6.dp))
                Text(
                    state.outcome.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(10.dp))
                StageTimes(state.outcome.stages)
                Spacer(Modifier.size(12.dp))
                Button(onClick = onBuild) { Text("Try again") }
            }
        }

        if (log.isNotEmpty()) {
            Spacer(Modifier.size(16.dp))
            Text(
                "Compiler output",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(6.dp))
            Ltr {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
                        .verticalScroll(rememberScrollState())
                        .padding(10.dp),
                ) {
                    log.takeLast(120).forEach { line ->
                        Text(line, style = WarpMono, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun StageTimes(stages: List<BuildEngine.StageResult>) = Ltr {
    Column {
        stages.forEach { s ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (s.ok) "✓" else "✕",
                    color = if (s.ok) WarpSuccess else MaterialTheme.colorScheme.error,
                    style = WarpMono,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    s.stage.label,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${s.durationMs / 1000f}s".take(5),
                    style = WarpMono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Status(text: String, tint: androidx.compose.ui.graphics.Color) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = tint, fontWeight = FontWeight.Medium)
}

/**
 * True for compiler output that looks alarming but means nothing.
 *
 * `kotlinc` bundles jansi, a library for colouring terminal output. On Android
 * it detects "Linux/arm64", extracts its native helper, and fails to load it
 * because that build needs glibc's libc.so.6 — Android uses Bionic instead.
 * Kotlin falls back to plain text and compiles perfectly.
 *
 * No JVM flag suppresses it (Ansi.disable, jansi.force and library.jansi.path
 * were all tried on the device and made no difference), and jansi is shaded
 * inside kotlin-compiler.jar so it cannot be removed from the classpath.
 * Hiding it here keeps the log honest about real problems.
 */
private fun isHarmlessNoise(line: String): Boolean {
    val l = line.lowercase()
    return "jansi" in l ||
        ("libc.so.6" in l && "dlopen" in l) ||
        l.startsWith("osinfo:")
}

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(10.dp))
        content()
    }
}
