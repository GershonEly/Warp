package dev.ely.warp.tools

import android.content.Intent
import androidx.core.content.FileProvider
import dev.ely.warp.build.ApkSigner
import dev.ely.warp.build.BuildEngine
import dev.ely.warp.build.NewProject
import dev.ely.warp.build.Toolchain
import org.json.JSONObject
import java.io.File

/**
 * The four that make Warp an IDE rather than a chat that edits files.
 *
 * Everything before this could write code. These compile it, put it on the
 * phone, start it, and read what it said — which is the loop: write, build,
 * read the error, fix, build again. That loop is the whole product, and until
 * now its second half could only be driven by a person tapping the Build tab
 * and reading the log back into the chat by hand.
 *
 * **All four are [Risk.RUNS], and RUNS never offers Always.** Writing a file
 * wrongly is annoying and visible; running things unattended is a different
 * kind of thing to be wrong about, and `/goal` can call these in a loop.
 */

/** Assemble the pipeline exactly as the Build screen does. One pipeline, not two. */
private fun engineFor(context: android.content.Context): BuildEngine {
    val toolchain = Toolchain.forContext(context)
    val workRoot = File(context.filesDir, "work").apply { mkdirs() }
    val signer = ApkSigner(
        toolchain = toolchain,
        keystoreFile = File(context.filesDir, "keys/warp-debug.p12"),
    )
    return BuildEngine(toolchain, workRoot, signer)
}

/** Where a finished APK ends up, by application id. */
internal fun apkFor(context: android.content.Context, applicationId: String): File =
    File(File(context.filesDir, "work"), "$applicationId.apk")

object BuildProject : Tool {
    override val name = "build"
    override val risk = Risk.RUNS
    override val description =
        "Compile the project into an installable APK, on this phone. Takes " +
            "about half a minute. On failure you get the compiler output, which " +
            "is what to fix."
    override val schemaJson = """{"type":"object","properties":{}}"""

    override fun describe(args: JSONObject) = "compile the project"

    override suspend fun run(env: ToolEnv, args: JSONObject): ToolResult {
        val meta = NewProject.meta(env.project)
            // Names the fix rather than the symptom. "No AndroidManifest.xml"
            // is true and tells you nothing about what to do next.
            ?: return ToolResult.Failed("there is no project here yet — call new_project first")

        val log = StringBuilder()
        val outcome = runCatching {
            engineFor(env.context).build(
                BuildEngine.Request(projectDir = env.project, applicationId = meta.applicationId),
                onLine = { log.appendLine(it.text) },
            )
        }.getOrElse { return ToolResult.Failed(it.message ?: "the build could not start") }

        return when (outcome) {
            is BuildEngine.Outcome.Success -> ToolResult.Ok(
                // Size and time, because they are what changes between builds
                // and what tells you the cache is working.
                "built ${outcome.apk.name} · ${outcome.apk.length() / 1024} KB · " +
                    "${outcome.totalMs / 1000}s" + if (outcome.signed) "" else " · UNSIGNED",
                outcome.stages.joinToString("\n") {
                    "${if (it.ok) "ok  " else "FAIL"} ${it.stage.label} (${it.durationMs} ms)"
                },
            )

            is BuildEngine.Outcome.Failure -> ToolResult.Failed(
                "${outcome.stage.label} failed: ${outcome.message}\n\n" +
                    // The compiler's own words, tail-first, because the useful
                    // part of a Kotlin error is at the end. This is the whole
                    // reason the tool exists — the model has to read this and
                    // fix it, and a summary would throw away the line numbers.
                    outcome.output.takeLast(2000).ifBlank { log.toString().takeLast(2000) }
            )
        }
    }
}

object InstallProject : Tool {
    override val name = "install"
    override val risk = Risk.RUNS
    override val description =
        "Hand the built APK to Android's installer. The person has to confirm " +
            "on screen; you cannot install silently."
    override val schemaJson = """{"type":"object","properties":{}}"""

    override fun describe(args: JSONObject) = "install the built APK"

    override suspend fun run(env: ToolEnv, args: JSONObject): ToolResult {
        val meta = NewProject.meta(env.project)
            ?: return ToolResult.Failed("there is no project here yet")
        val apk = apkFor(env.context, meta.applicationId)
        if (!apk.isFile) return ToolResult.Failed("nothing built yet — call build first")

        // A FileProvider, because Warp targets API 28 and since API 24 handing
        // a file:// URI to another app throws FileUriExposedException.
        val uri = FileProvider.getUriForFile(
            env.context, "${env.context.packageName}.fileprovider", apk,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            // Not started from an Activity, so it needs its own task.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { env.context.startActivity(intent) }
            .getOrElse { return ToolResult.Failed(it.message ?: "could not open the installer") }

        // Says what actually happened, which is *not* "installed". Android's
        // installer is a separate screen somebody has to agree to, and claiming
        // the install succeeded here would be the tool lying about the one
        // thing it cannot see. `launch` is what finds out.
        return ToolResult.Ok(
            "handed ${apk.name} to Android's installer — confirm it on screen",
            "The install is not done until you tap Install. Use launch to check.",
        )
    }
}

object LaunchProject : Tool {
    override val name = "launch"
    override val risk = Risk.RUNS
    override val description =
        "Open the app that was built, if it is installed. Also the way to find " +
            "out whether an install actually went through."
    override val schemaJson = """{"type":"object","properties":{}}"""

    override fun describe(args: JSONObject) = "open the built app"

    override suspend fun run(env: ToolEnv, args: JSONObject): ToolResult {
        val meta = NewProject.meta(env.project)
            ?: return ToolResult.Failed("there is no project here yet")

        val intent = env.context.packageManager.getLaunchIntentForPackage(meta.applicationId)
            ?: return ToolResult.Failed(
                "${meta.applicationId} is not installed — the install was not confirmed"
            )

        runCatching {
            env.context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.getOrElse { return ToolResult.Failed(it.message ?: "could not start it") }

        return ToolResult.Ok("started ${meta.applicationId}", null)
    }
}

object ReadLogcat : Tool {
    override val name = "logcat"
    override val risk = Risk.RUNS
    override val description =
        "Read the phone's log for the app that was built — crashes, stack " +
            "traces, anything it printed. Use this after launch to find out why " +
            "something did not work."
    override val schemaJson = """
        {"type":"object","properties":{
          "lines":{"type":"integer","description":"How many recent lines to scan. Default 400."}}}
    """.trimIndent()

    override fun describe(args: JSONObject) = "read the log"

    override suspend fun run(env: ToolEnv, args: JSONObject): ToolResult {
        val meta = NewProject.meta(env.project)
            ?: return ToolResult.Failed("there is no project here yet")
        val depth = args.optInt("lines", 400).coerceIn(50, 2000)

        val raw = runCatching {
            ProcessBuilder("logcat", "-d", "-t", depth.toString(), "-v", "brief")
                .redirectErrorStream(true)
                .start()
                .inputStream.bufferedReader().use { it.readText() }
        }.getOrElse { return ToolResult.Failed("could not read the log: ${it.message}") }

        val lines = raw.lines()
        if (lines.size <= 1) {
            return ToolResult.Failed(
                "this phone will not let Warp read the log. On Xiaomi, turn on " +
                    "Developer options > USB debugging (Security settings)."
            )
        }

        // Two passes: find which processes belong to this app, then take
        // everything they said. A crash reports "Process: com.example.notes"
        // on one line and the stack trace on the next forty, and matching only
        // lines containing the package name would return the header and throw
        // away the trace.
        val id = meta.applicationId
        val pids = lines.filter { id in it }
            .mapNotNull { PID.find(it)?.groupValues?.get(1) }
            .toSet()
        val mine = lines.filter { line ->
            id in line || PID.find(line)?.groupValues?.get(1) in pids
        }

        if (mine.isEmpty()) {
            // Empty is not the same as clean, and the difference matters more
            // here than anywhere: "no errors" is what a person reads into a
            // blank result, and this phone may simply be hiding other apps'
            // logs. Say which it is.
            return ToolResult.Ok(
                "nothing from $id in the last ${lines.size} lines",
                "This means either the app has not run, or this phone only lets " +
                    "Warp see its own log. It does NOT mean the app ran cleanly.",
            )
        }

        val crashed = mine.any { "FATAL EXCEPTION" in it || "AndroidRuntime" in it }
        return ToolResult.Ok(
            if (crashed) "CRASHED · ${mine.size} lines from $id"
            else "${mine.size} lines from $id",
            mine.takeLast(200).joinToString("\n"),
        )
    }

    /** The pid column of a `-v brief` line: `D/Tag ( 1234): text`. */
    private val PID = Regex("""\(\s*(\d+)\)""")
}

/** Everything that runs something. Kept named, because RUNS never gets Always. */
val DEVICE_TOOLS: List<Tool> = listOf(BuildProject, InstallProject, LaunchProject, ReadLogcat)
