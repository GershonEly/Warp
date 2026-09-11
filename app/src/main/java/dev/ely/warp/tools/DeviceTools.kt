package dev.ely.warp.tools

import android.content.Intent
import androidx.core.content.FileProvider
import dev.ely.warp.build.ApkSigner
import dev.ely.warp.build.BuildEngine
import dev.ely.warp.build.BuildStatus
import dev.ely.warp.build.CrashInbox
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

        // An app built before icons existed has none, and would keep
        // installing as a blank grey square for ever. Writing them here means
        // the next build fixes it, without a migration nobody would run.
        dev.ely.warp.build.Icons.ensure(env.project, meta.name, meta.colour)

        val log = StringBuilder()
        // Announced before the work starts and closed in a finally, so a build
        // that throws cannot leave the room moving for ever.
        BuildStatus.started()
        // Thirty seconds of silence is where somebody puts the phone down, so
        // the notification names the app being compiled rather than "working" —
        // and `during` protects the build even when no turn started it.
        val outcome = runCatching {
            dev.ely.warp.work.Working.during("Compiling ${meta.name}") {
                engineFor(env.context).build(
                    BuildEngine.Request(projectDir = env.project, applicationId = meta.applicationId),
                    onLine = { log.appendLine(it.text) },
                )
            }
        }.getOrElse {
            BuildStatus.finished(ok = false)
            return ToolResult.Failed(it.message ?: "the build could not start")
        }
        BuildStatus.finished(ok = outcome is BuildEngine.Outcome.Success)

        return when (outcome) {
            is BuildEngine.Outcome.Success -> {
                // Copied out of the shared work directory into the project's
                // own folder, because the work directory holds exactly one
                // build: compiling a second app deleted the first app's APK,
                // and the shelf then said "not built" for something you had
                // watched build a minute earlier.
                //
                // The work directory stays shared on purpose — it holds the
                // pre-dexed Kotlin runtime, which is what took a build from
                // 41 s to 18 s. Only the finished artefact moves.
                val kept = File(env.project, "app.apk")
                val apk = runCatching { outcome.apk.copyTo(kept, overwrite = true) }
                    .getOrDefault(outcome.apk)
                NewProject.recordBuild(env.project, apk)
                ToolResult.Ok(
                // Size and time, because they are what changes between builds
                // and what tells you the cache is working.
                "built ${apk.name} · ${apk.length() / 1024} KB · " +
                    "${outcome.totalMs / 1000}s" + if (outcome.signed) "" else " · UNSIGNED",
                    outcome.stages.joinToString("\n") {
                        "${if (it.ok) "ok  " else "FAIL"} ${it.stage.label} (${it.durationMs} ms)"
                    },
                )
            }

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
        val apk = NewProject.lastApk(env.project)
            ?: return ToolResult.Failed("nothing built yet — call build first")

        // Through Installer, not a second copy of the same intent. The copy
        // here did not wrap the FileProvider call, so when the APK moved into
        // the project folder this threw straight out of the tool and killed the
        // request instead of reporting a failure.
        dev.ely.warp.build.Installer.open(env.context, apk)
            ?.let { return ToolResult.Failed(it) }

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

        // Forget the last crash before starting, so a run that succeeds cannot
        // be reported as the failure it replaced. A stale crash is worse than no
        // crash: it sends you to fix something that is already fixed.
        CrashInbox.clear(env.context, meta.applicationId)

        runCatching {
            env.context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.getOrElse { return ToolResult.Failed(it.message ?: "could not start it") }

        // "Asked", not "started". `startActivity` returns as soon as the
        // request is accepted; the app can still fail to launch, and this one
        // did — it crashed on instantiation and the tool cheerfully said
        // "started". Pointing at logcat is the useful half of the answer.
        return ToolResult.Ok(
            "asked Android to open ${meta.applicationId}",
            "Starting is not the same as running. Call logcat to see what it did.",
        )
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

        // The delivered crash first, because it is the only source that cannot
        // be taken away. Every app Warp builds reports its own death through a
        // ContentProvider; logcat is the fallback for apps that died before the
        // reporter was installed, and for anything printed rather than thrown.
        CrashInbox.lastCrash(env.context, meta.applicationId)?.let { trace ->
            return ToolResult.Ok("CRASHED · ${headline(trace)}", trace)
        }

        // **`-b crash` is the load-bearing flag.** Android writes uncaught
        // exceptions to a separate buffer, and without naming it a freshly
        // crashed app produced no output at all: the tool reported "nothing from
        // com.example.timer", which is the exact wrong answer to "why did my app
        // die". Found by reading the crash buffer by hand from the PC after the
        // tool insisted there was nothing there.
        val raw = runCatching {
            ProcessBuilder(
                "logcat", "-d", "-b", "main,system,crash",
                "-t", depth.toString(), "-v", "brief",
            )
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
            // Empty is not the same as clean, and this is the one place that
            // distinction can cost you an evening: "no errors" is what anybody
            // reads into a blank result.
            //
            // It says what was actually observed rather than deciding which
            // explanation is true, because it cannot tell. Counting other
            // visible processes looked like a test and is not one — Warp
            // could see two system processes on a phone that was still hiding
            // the app's crash, so the count proved nothing and the confident
            // wording built on it was wrong.
            //
            // Android limits an app to its own log unless READ_LOGS is granted,
            // and on Xiaomi that is a one-time prompt: it can work in the
            // morning and not the afternoon on the same phone.
            val self = android.os.Process.myPid().toString()
            val others = lines.mapNotNull { PID.find(it)?.groupValues?.get(1) }
                .filter { it != self }.distinct().size

            return ToolResult.Ok(
                "nothing from $id in ${lines.size} lines — cannot say it ran cleanly",
                "Read ${lines.size} lines, $others other processes visible, none " +
                    "mentioning $id.\n\nEither it has not run since, or this phone " +
                    "will not let Warp read another app's log. Reading a crash " +
                    "reliably needs Developer options > USB debugging (Security " +
                    "settings), and on Xiaomi that grant is one-time.",
            )
        }

        val crashed = mine.any { "FATAL EXCEPTION" in it || "AndroidRuntime" in it }
        return ToolResult.Ok(
            if (crashed) "CRASHED · ${mine.size} lines from $id"
            else "${mine.size} lines from $id",
            mine.takeLast(200).joinToString("\n"),
        )
    }

    /**
     * The one line worth putting on the card.
     *
     * The last `Caused by:` wins, because that is the actual fault: an Android
     * crash arrives wrapped as *"Unable to start activity ...: some other
     * exception"*, and the wrapper is the same sentence every time. The first
     * attempt at this took the first non-blank line and produced "CRASHED ·
     * Thread: main", which is true and says nothing.
     */
    private fun headline(trace: String): String {
        val lines = trace.lines().map { it.trim() }.filter { it.isNotBlank() }
        return (lines.lastOrNull { it.startsWith("Caused by:") }
            ?: lines.firstOrNull { "Exception" in it || "Error" in it }
            ?: lines.firstOrNull()
            .orEmpty())
            .removePrefix("Caused by:")
            .trim()
            .take(110)
    }

    /** The pid column of a `-v brief` line: `D/Tag ( 1234): text`. */
    private val PID = Regex("""\(\s*(\d+)\)""")
}

/** Everything that runs something. Kept named, because RUNS never gets Always. */
/**
 * Ask the app it built what it actually stored — §5m.
 *
 * The other half of §5g's forty messages of blind debugging. Warp cannot read
 * another app's sandbox and no permission changes that, so the generated app
 * carries a provider that serves its own files, and this knocks on it.
 *
 * `FREE`, like the other reading tools. It cannot reach anything Warp did not
 * build: the door is opened by a key written into that project at creation, and
 * held in that project's own metadata.
 */
object AppData : Tool {
    override val name = "app_data"
    override val risk = Risk.FREE
    override val description =
        "Read what the app you built actually stored — its files, and the rows " +
            "in its database. Use it instead of guessing why something looks " +
            "empty or wrong after the app has run."
    override val schemaJson = """
        {"type":"object","properties":{
          "what":{"type":"string","enum":["list","read","table"],
                  "description":"list its files, read one file, or read a table."},
          "path":{"type":"string","description":"For read: the path from list."},
          "db":{"type":"string","description":"For table: the database's path from list."},
          "table":{"type":"string","description":"For table: the table name."}},
         "required":["what"]}
    """.trimIndent()

    override fun describe(args: JSONObject): String = when (args.optString("what")) {
        "read" -> args.optString("path")
        "table" -> "${args.optString("table")} in ${args.optString("db")}"
        else -> "what the app stored"
    }

    override suspend fun run(env: ToolEnv, args: JSONObject): ToolResult {
        val meta = NewProject.meta(env.project)
            ?: return ToolResult.Failed("there is no project here yet")

        // The case that must never read as "the app stored nothing" — §5m. An
        // app built before the door existed cannot answer, and the fix is a
        // rebuild, so the answer says so rather than coming back empty.
        val key = meta.dataKey
            ?: return ToolResult.Failed(
                "this app was built before Warp could look inside it. " +
                    "Build and install it again, then try once it has run."
            )

        val what = args.optString("what").ifBlank { "list" }
        val uri = android.net.Uri.parse("content://${meta.applicationId}.warpdata")
            .buildUpon()
            .appendQueryParameter("key", key)
            .appendQueryParameter("what", what)
            .apply {
                args.optString("path").takeIf { it.isNotBlank() }
                    ?.let { appendQueryParameter("path", it) }
                args.optString("db").takeIf { it.isNotBlank() }
                    ?.let { appendQueryParameter("db", it) }
                args.optString("table").takeIf { it.isNotBlank() }
                    ?.let { appendQueryParameter("table", it) }
            }
            .build()

        val cursor = runCatching {
            env.context.contentResolver.query(uri, null, null, null, null)
        }.getOrNull()
            ?: return ToolResult.Failed(
                // Three different facts, one symptom, so the answer names them
                // rather than guessing which one it was.
                "no answer from ${meta.applicationId} — it may not be installed, " +
                    "may never have been run, or may have been built before the " +
                    "door existed. Install it, open it once, and try again."
            )

        return cursor.use { rows ->
            when (what) {
                "read" -> {
                    if (!rows.moveToFirst()) return ToolResult.Failed("no such file in the app")
                    val text = rows.getString(0)
                    val size = rows.getLong(1)
                    val capped = rows.getInt(2) == 1
                    ToolResult.Ok(
                        "$size bytes" + if (capped) " · showing the first part" else "",
                        text,
                    )
                }

                "table" -> {
                    val names = rows.columnNames.joinToString(" | ")
                    val body = StringBuilder(names).append('\n')
                    var count = 0
                    while (rows.moveToNext()) {
                        count++
                        body.append(
                            (0 until rows.columnCount).joinToString(" | ") {
                                rows.getString(it) ?: "null"
                            }
                        ).append('\n')
                    }
                    // Zero rows is an answer, and usually *the* answer — "your
                    // app saved nothing" is what the forty messages were about.
                    ToolResult.Ok("$count row${if (count == 1) "" else "s"}", body.toString().trim())
                }

                else -> {
                    val body = StringBuilder()
                    var count = 0
                    while (rows.moveToNext()) {
                        count++
                        body.append(rows.getString(0))
                            .append("  ")
                            .append(rows.getLong(1))
                            .append(" bytes\n")
                    }
                    if (count == 0) {
                        ToolResult.Ok("the app has stored nothing yet", null)
                    } else {
                        ToolResult.Ok("$count file${if (count == 1) "" else "s"}", body.toString().trim())
                    }
                }
            }
        }
    }
}

val DEVICE_TOOLS: List<Tool> =
    listOf(BuildProject, InstallProject, LaunchProject, ReadLogcat, AppData)
