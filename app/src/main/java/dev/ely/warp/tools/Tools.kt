package dev.ely.warp.tools

import android.content.Context
import dev.ely.warp.brain.AndroidBrain
import org.json.JSONObject
import java.io.File

/**
 * What the model can actually do.
 *
 * Until now a tool card appeared in the chat and **nothing happened** — the mock
 * emitted the card and no file was ever written. This is where that stops being
 * a picture.
 *
 * Every tool here reports **what it did**, not that it was called. `read_file`
 * says how many lines came back; `write_file` says how many bytes landed. A tool
 * that answers "ok" is a tool you cannot test, and this project has been caught
 * three times by things that reported success while doing nothing.
 */

/** What a tool did, or why it could not. */
sealed interface ToolResult {
    /** @param summary one line for the card. @param body the full output. */
    data class Ok(val summary: String, val body: String? = null) : ToolResult
    data class Failed(val reason: String) : ToolResult
}

/**
 * How much a tool can cost you if it is wrong.
 *
 * The grouping *is* the permission model — see §5f. Reading inside the project
 * never asks, because a dialog on every file read is how people learn to tap
 * Allow without looking, and guarding cheap things is what makes the expensive
 * prompts invisible.
 */
enum class Risk {
    /** Reading inside the project. Never asks. */
    FREE,

    /** Changes files. Asks once, then Allow / Always. */
    WRITES,

    /** Runs something, or leaves the device. Always asks. */
    RUNS,

    /**
     * Changes nothing, but cannot finish without you.
     *
     * Its own risk rather than FREE, because FREE means "runs without stopping"
     * and this always stops. It is also never grantable: there is no Always for
     * a question, since answering it *is* the tool.
     */
    ASKS,
}

/**
 * What a tool is allowed to touch.
 *
 * The folder was enough while every tool read and wrote files. Building,
 * installing and launching need the phone itself — a toolchain, a package
 * manager, an intent — so the environment is passed rather than the path.
 *
 * A single object rather than two parameters so the next thing a tool needs can
 * be added here instead of in every signature.
 */
data class ToolEnv(
    val project: File,
    val context: Context,
    /**
     * Who can send a helper, or null where nobody can — see [Delegate].
     *
     * Nullable rather than assumed, for the same reason the permission desk is:
     * the runner exists in places the chat engine does not, and a tool that
     * needs something absent should say so rather than pretend it worked.
     */
    val subagents: RunsSubagents? = null,
    /**
     * Where the steps are kept, or null where nothing keeps them — §5j.
     *
     * Nullable for the same reason [subagents] is: the runner exists in places
     * the chat engine does not, and a tool whose workings are missing should say
     * so rather than pretend it worked.
     */
    val tasks: TaskBoard? = null,
    /**
     * Where earlier conversations can be read — §5p. Null where none can.
     *
     * Nullable like the rest, and for the same reason: the runner exists in
     * places the store does not, and a tool whose workings are missing should
     * say so rather than answer as though nothing ever happened.
     */
    val sessions: ReadsSessions? = null,
)

interface Tool {
    val name: String
    val risk: Risk

    /**
     * What this is for, addressed to the model.
     *
     * Lives on the tool rather than in a table somewhere, because a description
     * that drifts from the behaviour is worse than none — it teaches the model
     * something untrue with full confidence.
     */
    val description: String

    /** JSON Schema for the arguments. */
    val schemaJson: String

    /** One line for the card, from the arguments alone, before it runs. */
    fun describe(args: JSONObject): String

    suspend fun run(env: ToolEnv, args: JSONObject): ToolResult
}

// ── reading ──────────────────────────────────────────────────────────────

/**
 * Resolve a path **inside the project**, or refuse.
 *
 * The check is on the canonical path, not the string, because `../` is a string
 * that looks fine and a path that is not. Without this, "scoped to the project"
 * is a comment rather than a boundary — and a read tool that never asks
 * permission is exactly the wrong place to be relaxed about it.
 */
internal fun resolve(project: File, path: String): File? {
    val target = File(project, path).canonicalFile
    val root = project.canonicalFile
    return if (target.path == root.path || target.path.startsWith(root.path + File.separator)) {
        target
    } else {
        null
    }
}

object ReadFile : Tool {
    override val description = "Read a file inside the project and return its text."
    override val schemaJson = """{"type":"object","properties":{"path":{"type":"string","description":"Path relative to the project root."}},"required":["path"]}"""
    override val name = "read_file"
    override val risk = Risk.FREE
    override fun describe(args: JSONObject) = args.optString("path")

    override suspend fun run(env: ToolEnv, args: JSONObject): ToolResult {
        val path = args.optString("path").ifBlank { return ToolResult.Failed("no path given") }
        val file = resolve(env.project, path) ?: return ToolResult.Failed("outside the project")
        if (!file.isFile) return ToolResult.Failed("no such file")

        val text = runCatching { file.readText() }
            .getOrElse { return ToolResult.Failed(it.message ?: "could not read") }

        // Line and byte counts rather than "ok". They are what a caller checks
        // against, and what makes a truncated read visible instead of silent.
        val lines = text.count { it == '\n' } + 1
        return ToolResult.Ok("$lines lines · ${file.length()} bytes", text)
    }
}

object ListDir : Tool {
    override val description = "List the files and folders at a path inside the project."
    override val schemaJson = """{"type":"object","properties":{"path":{"type":"string","description":"Folder relative to the project root. Defaults to the root."}}}"""
    override val name = "list_dir"
    override val risk = Risk.FREE
    override fun describe(args: JSONObject) = args.optString("path").ifBlank { "." }

    override suspend fun run(env: ToolEnv, args: JSONObject): ToolResult {
        val dir = resolve(env.project, args.optString("path").ifBlank { "." })
            ?: return ToolResult.Failed("outside the project")
        if (!dir.isDirectory) return ToolResult.Failed("not a directory")

        val entries = dir.listFiles().orEmpty().sortedWith(
            // Folders first, then by name. A flat alphabetical list of a mixed
            // directory buries the structure in the files.
            compareBy({ !it.isDirectory }, { it.name.lowercase() })
        )
        val body = entries.joinToString("\n") {
            if (it.isDirectory) "${it.name}/" else "${it.name}  ${it.length()}"
        }
        return ToolResult.Ok("${entries.size} entries", body)
    }
}

object Glob : Tool {
    override val description = "Find files by name pattern, e.g. **/*.kt. Returns paths, not contents."
    override val schemaJson = """{"type":"object","properties":{"pattern":{"type":"string","description":"Shell glob. ** crosses folders, * does not."}},"required":["pattern"]}"""
    override val name = "glob"
    override val risk = Risk.FREE
    override fun describe(args: JSONObject) = args.optString("pattern")

    override suspend fun run(env: ToolEnv, args: JSONObject): ToolResult {
        val pattern = args.optString("pattern")
            .ifBlank { return ToolResult.Failed("no pattern given") }
        val regex = globToRegex(pattern)

        val hits = env.project.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(env.project).path.replace(File.separatorChar, '/') }
            .filter { regex.matches(it) }
            .take(LIMIT)
            .toList()

        // Says when it stopped. A capped list that does not admit to being
        // capped reads as "that is everything", which is the one thing it is not.
        val summary = if (hits.size == LIMIT) "$LIMIT matches (capped)" else "${hits.size} matches"
        return ToolResult.Ok(summary, hits.joinToString("\n"))
    }
}

object Grep : Tool {
    override val description = "Search file contents with a regular expression. Returns path:line: match."
    override val schemaJson = """{"type":"object","properties":{"pattern":{"type":"string","description":"Regular expression."},"glob":{"type":"string","description":"Only search files matching this glob."}},"required":["pattern"]}"""
    override val name = "grep"
    override val risk = Risk.FREE
    override fun describe(args: JSONObject) = args.optString("pattern")

    override suspend fun run(env: ToolEnv, args: JSONObject): ToolResult {
        val pattern = args.optString("pattern")
            .ifBlank { return ToolResult.Failed("no pattern given") }
        val regex = runCatching { Regex(pattern) }
            .getOrElse { return ToolResult.Failed("bad pattern: ${it.message}") }

        val within = args.optString("glob").takeIf { it.isNotBlank() }?.let { globToRegex(it) }
        val out = StringBuilder()
        var count = 0

        env.project.walkTopDown().filter { it.isFile }.forEach { file ->
            val rel = file.relativeTo(env.project).path.replace(File.separatorChar, '/')
            if (within != null && !within.matches(rel)) return@forEach
            if (count >= LIMIT) return@forEach

            runCatching {
                file.useLines { lines ->
                    lines.forEachIndexed { i, line ->
                        if (count < LIMIT && regex.containsMatchIn(line)) {
                            out.appendLine("$rel:${i + 1}: ${line.trim().take(160)}")
                            count++
                        }
                    }
                }
            }
        }

        val summary = if (count >= LIMIT) "$LIMIT matches (capped)" else "$count matches"
        return ToolResult.Ok(summary, out.toString().trimEnd())
    }
}

// ── changing things ──────────────────────────────────────────────────────

object WriteFile : Tool {
    override val name = "write_file"
    override val risk = Risk.WRITES
    override val description =
        "Create a file inside the project, or replace one completely. " +
            "Write the whole thing you mean to write — a new file, or a " +
            "rewrite of one you are reshaping. Do not build a file up over " +
            "many small edits. Use edit_file for a fix inside a file that is " +
            "already what you want."
    override val schemaJson = """
        {"type":"object","properties":{
          "path":{"type":"string","description":"Path relative to the project root."},
          "content":{"type":"string","description":"The whole file."}},
         "required":["path","content"]}
    """.trimIndent()

    // Says whether it is new, and how big. "src/Main.kt" alone does not tell
    // you whether you are about to lose four hundred lines of work.
    override fun describe(args: JSONObject): String {
        val path = args.optString("path")
        val size = args.optString("content").length
        return "$path · $size chars"
    }

    override suspend fun run(env: ToolEnv, args: JSONObject): ToolResult {
        val path = args.optString("path").ifBlank { return ToolResult.Failed("no path given") }
        val file = resolve(env.project, path) ?: return ToolResult.Failed("outside the project")
        if (file.isDirectory) return ToolResult.Failed("that is a folder")

        val content = args.optString("content")
        val existed = file.isFile
        val before = if (existed) file.length() else 0L

        file.parentFile?.mkdirs()
        runCatching { file.writeText(content) }
            .getOrElse { return ToolResult.Failed(it.message ?: "could not write") }

        // Reads the file back rather than reporting what it meant to write. A
        // write that half-succeeds is exactly the case worth catching, and the
        // length it *intended* would report success either way.
        val written = runCatching { file.readText() }.getOrNull()
            ?: return ToolResult.Failed("wrote, but could not read it back")
        if (written != content) {
            return ToolResult.Failed("wrote ${written.length} chars, expected ${content.length}")
        }

        val lines = content.count { it == '\n' } + 1
        val what = if (existed) "replaced ($before → ${file.length()} bytes)" else "created"

        // A Kotlin file whose `package` is not the app's own is the other way
        // this project loses two builds to one typo. `aapt2` generates `R`
        // under the application id, so a file declaring a different package
        // compiles until it touches `R` and then fails with "unresolved
        // reference: R" — which reads as a resource problem, and sends the
        // model to look at res/ where nothing is wrong. Seen exactly once and
        // diagnosed only on the third build: *"the problem is the package"*.
        val wrongPackage = packageMismatch(env, file, content)

        // The read-back is said out loud, because this line is now the
        // whole of what the model is told. Without it the only way to be
        // sure the write landed is to read the file again, which costs
        // more than the four words do.
        return ToolResult.Ok(
            "$what · $lines lines · read back and matched" +
                if (wrongPackage == null) "" else " · ⚠ $wrongPackage",
            if (wrongPackage == null) content else "$wrongPackage\n\n$content",
        )
    }
}

object EditFile : Tool {
    override val name = "edit_file"
    override val risk = Risk.WRITES
    override val description =
        "Fix a specific piece of an existing file. The old text must appear " +
            "exactly once, so include enough context to make it unique. " +
            "For a file you are still writing, or a change touching most of " +
            "one, write_file the whole thing instead — a file built out of " +
            "twenty small edits costs twenty round trips and ends up wrong."
    override val schemaJson = """
        {"type":"object","properties":{
          "path":{"type":"string","description":"Path relative to the project root."},
          "old":{"type":"string","description":"Exact text to replace. Must be unique in the file."},
          "new":{"type":"string","description":"What to put there instead."}},
         "required":["path","old","new"]}
    """.trimIndent()

    override fun describe(args: JSONObject): String {
        val old = args.optString("old").lines().firstOrNull().orEmpty().trim()
        return "${args.optString("path")} · ${old.take(48)}"
    }

    override suspend fun run(env: ToolEnv, args: JSONObject): ToolResult {
        val path = args.optString("path").ifBlank { return ToolResult.Failed("no path given") }
        val file = resolve(env.project, path) ?: return ToolResult.Failed("outside the project")
        if (!file.isFile) return ToolResult.Failed("no such file")

        val old = args.optString("old")
        if (old.isEmpty()) return ToolResult.Failed("no text to replace — use write_file to create")
        val new = args.optString("new")

        val text = runCatching { file.readText() }
            .getOrElse { return ToolResult.Failed(it.message ?: "could not read") }

        // Refuses rather than guessing. Replacing the first of several matches
        // silently edits the wrong line and hands back a success, and a wrong
        // edit reported as done is far worse than an edit that did not happen.
        val hits = text.split(old).size - 1
        when (hits) {
            0 -> return ToolResult.Failed("that text is not in the file")
            1 -> Unit
            else -> return ToolResult.Failed("that text appears $hits times — add more context")
        }

        val after = text.replace(old, new)
        runCatching { file.writeText(after) }
            .getOrElse { return ToolResult.Failed(it.message ?: "could not write") }

        val removed = old.count { it == '\n' } + 1
        val added = new.count { it == '\n' } + 1
        // What this edit took away that the file still needs — see [orphaned].
        val broke = orphaned(old, new, after)
        return ToolResult.Ok(
            "replaced 1 match · -$removed +$added lines" +
                if (broke.isEmpty()) "" else " · ⚠ removed ${broke.joinToString(", ")}",
            // A diff rather than the whole file. It is what you look at before
            // saying yes, and what the model needs to see it landed.
            buildString {
                if (broke.isNotEmpty()) {
                    appendLine(
                        "This edit removed ${broke.joinToString(", ")}, which the " +
                            "rest of the file still uses. Put ${
                                if (broke.size == 1) "it" else "them"
                            } back before building, or the compiler will fail " +
                            "somewhere else and the reason will not be obvious."
                    )
                    appendLine()
                }
                old.lines().forEach { appendLine("- $it") }
                new.lines().forEach { appendLine("+ $it") }
            }.trimEnd(),
        )
    }
}

/**
 * A `package` line that does not match the app, or null when it is fine.
 *
 * Only for Kotlin inside `src/`, and only when the project already knows its
 * own application id — before `new_project` there is nothing to compare with.
 *
 * Subpackages are allowed: `com.example.roll.ui` is a normal thing to write
 * and `R` still resolves, because `R` lives under the application id and gets
 * imported. What breaks is a package in a *different* tree, which is what
 * happened: the project was `com.example.roll` and the file said
 * `com.warp.roll`.
 */
internal fun packageMismatch(env: ToolEnv, file: File, content: String): String? {
    if (file.extension != "kt") return null
    val appId = dev.ely.warp.build.NewProject.meta(env.project)?.applicationId ?: return null

    val declared = content.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith("package ") }
        ?.removePrefix("package ")?.trim()?.trimEnd(';')
        ?: return null

    if (declared == appId || declared.startsWith("$appId.")) return null
    return "package is `$declared` but this app is `$appId` — R will not resolve"
}

/**
 * What an edit took away that the rest of the file still needs.
 *
 * **The most expensive small bug in this project.** An edit replaces a block
 * and quietly drops the imports above it; the tool reports success, and the
 * failure turns up at the next build as `unresolved reference 'Bundle'` — a
 * long way from the edit that caused it, in a file the model then re-reads
 * looking for a mistake it did not make. One session narrated it exactly:
 * *"I clobbered the earlier imports"*, four failed builds later.
 *
 * Said here, where it happened, rather than left to the compiler.
 *
 * **A warning and not a refusal.** Removing something and then removing its
 * last use in the next call is a perfectly ordinary two-step, and a tool that
 * blocked it would be wrong more often than it was right. This only has to
 * arrive before the build does.
 *
 * Deliberately shallow: no parser, just names. It catches the case that keeps
 * happening and will miss cleverer ones, which is the correct trade for a check
 * that runs on every edit.
 */
internal fun orphaned(old: String, new: String, after: String): List<String> {
    fun namesIn(text: String): Map<String, String> = buildMap {
        text.lines().forEach { line ->
            val t = line.trim()
            when {
                // `import a.b.C` and `import a.b.C as D` — the name that
                // matters is the one the code actually types. A star import
                // introduces no name, so there is nothing to orphan.
                t.startsWith("import ") && !t.endsWith("*") -> {
                    val name = t.substringAfterLast(" as ", t.substringAfterLast('.')).trim()
                    if (name.isNotEmpty()) put(name, "import $name")
                }
                else -> DECLARATION.find(t)?.groupValues?.get(2)?.let { put(it, it) }
            }
        }
    }

    val lost = namesIn(old) - namesIn(new).keys
    if (lost.isEmpty()) return emptyList()

    // What is left once imports are set aside: an import cannot keep itself
    // alive, and neither can a declaration that is only ever declared.
    val body = after.lines()
        .filterNot { it.trim().startsWith("import ") }
        .joinToString("\n")

    return lost.filterKeys { name ->
        Regex("\\b${Regex.escape(name)}\\b").containsMatchIn(body)
    }.values.toList()
}

private val DECLARATION =
    Regex("""^\s*(?:private\s+|internal\s+|public\s+|data\s+|open\s+|abstract\s+)*(fun|val|var|class|object|interface)\s+(\w+)""")

// ── asking you ───────────────────────────────────────────────────────────

/**
 * Put one question to the person, and wait.
 *
 * The engine that carries this is `/grill-me`. Structured rather than left as
 * prose because the options are meant to be tappable — a question you can answer
 * with a thumb gets answered, and one that needs a paragraph typed back gets put
 * off. The schema is the difference between an interrogation and a wall of text.
 */
object AskUser : Tool {
    override val name = "ask"
    override val risk = Risk.ASKS
    override val description =
        "Ask the user exactly one question and wait for the answer. Give 2-4 " +
            "concrete options and say which you recommend. Use this to resolve " +
            "a decision you cannot make for them — never to check in."
    override val schemaJson = """
        {"type":"object","properties":{
          "question":{"type":"string","description":"One question. Not a list."},
          "options":{"type":"array","items":{"type":"string"},
                     "description":"2-4 concrete answers, each a few words."},
          "recommended":{"type":"integer",
                     "description":"Index of the option you would pick, from 0."},
          "because":{"type":"string","description":"One line on why you recommend it."}},
         "required":["question","options"]}
    """.trimIndent()

    override fun describe(args: JSONObject) = args.optString("question")

    // Never reached. The runner sends this to the question desk instead, because
    // the answer comes from a person and a File cannot supply one. It is here
    // because Tool requires it, and throwing would be worse than saying so.
    override suspend fun run(env: ToolEnv, args: JSONObject): ToolResult =
        ToolResult.Failed("ask is answered by you, not by the project")
}

/**
 * Start an app, properly.
 *
 * WRITES rather than FREE even though the folder is usually empty, because it
 * lays down five files under names the whole build depends on. It is also the
 * moment a project acquires an application id, which is effectively permanent
 * once the app is installed anywhere — worth one prompt.
 */
/**
 * Looking something up instead of asking you — §7.
 *
 * `FREE`, and it is the purest example of what that risk level is for: it reads
 * nothing on disk, changes nothing, leaves nothing behind, and cannot cost
 * anything but the tokens of the answer. A prompt asking permission to consult
 * its own memory would be a prompt people learn to ignore.
 *
 * The reason this is a tool at all rather than a longer system prompt is the
 * bill. Everything here would otherwise be re-sent with every message for the
 * rest of the conversation — the same waste taken out of tool output in
 * `7c93b98`, put back deliberately and permanently. As a tool it costs nothing
 * until the moment it is needed.
 */
object AndroidDocs : Tool {
    override val name = "android_docs"
    override val risk = Risk.FREE
    override val description =
        "Read what Warp already knows about Android — how to build a screen " +
            "here, making it look designed, icon sizes, project layout, " +
            "permissions and the things that fail quietly. Read `compose` or " +
            "`layout` before writing any UI, whichever this project uses. Use " +
            "this instead of asking the user, and instead of guessing. " +
            "Topics: " + AndroidBrain.names.joinToString(", ")

    override val schemaJson = """
        {"type":"object","properties":{
          "topic":{"type":"string","enum":[${AndroidBrain.names.joinToString(",") { "\"$it\"" }}],
                   "description":"Which section to read."}},
         "required":["topic"]}
    """.trimIndent()

    override fun describe(args: JSONObject): String = args.optString("topic")

    override suspend fun run(env: ToolEnv, args: JSONObject): ToolResult {
        val topic = args.optString("topic").trim().lowercase()
        // Names what it does have rather than only what it does not. A model
        // told "no such topic" guesses again; a model handed the list picks.
        val text = AndroidBrain.topics[topic]
            ?: return ToolResult.Failed(
                "no topic called \"$topic\" — there is: ${AndroidBrain.names.joinToString(", ")}"
            )

        // `design` is two pages behind one name: making an app look considered
        // means `colors.xml` and a theme in one kind of project, and a
        // `ColorScheme` in the other. One name because the model should not
        // have to know which page it needs before it knows what it needs, and
        // because a name it can guess wrong is a dead end it cannot see.
        //
        // Served from the project rather than asked about, so it cannot be got
        // wrong: a Compose app was handed the XML page, which opens by saying
        // MaterialTheme is not available. It is the most important thing in a
        // Compose app. The model believed it and used the defaults.
        val forProject = AndroidBrain.pageFor(
            topic,
            compose = dev.ely.warp.build.NewProject.meta(env.project)?.compose,
        ) ?: text

        return ToolResult.Ok("$topic · ${forProject.lines().size} lines", forProject)
    }
}

object NewProjectTool : Tool {
    override val name = "new_project"
    override val risk = Risk.WRITES
    override val description =
        "Create the skeleton of an Android app: manifest, resources and a " +
            "MainActivity that already compiles. Do this once, before writing " +
            "any code, in an empty project. It will refuse if one already exists."
    override val schemaJson = """
        {"type":"object","properties":{
          "name":{"type":"string","description":"What the app is called, as a person would say it."},
          "package":{"type":"string",
            "description":"Application id like com.example.notes. Leave out to derive one."},
          "compose":{"type":"boolean","description":
            "Use Jetpack Compose instead of XML layouts. Default to true — do NOT ask which one they want. Compose looks modern and gives Material 3, dark mode and proper spacing for free, and the person asking for an app has no way to choose between two Android toolkits they have never heard of. Only pass false if they specifically ask for XML, or say the app must be as small as possible: Compose costs about 8 MB against 50 KB. This cannot be changed later, so if they did not say, pick Compose."}},
         "required":["name"]}
    """.trimIndent()

    override fun describe(args: JSONObject): String {
        val name = args.optString("name")
        val id = args.optString("package").ifBlank {
            dev.ely.warp.build.NewProject.derivePackage(name)
        }
        // The choice is on the card, because it is the one decision here that
        // cannot be undone afterwards.
        val kind = if (args.optBoolean("compose", false)) "Compose" else "XML layouts"
        return "$name ($id) · $kind"
    }

    override suspend fun run(env: ToolEnv, args: JSONObject): ToolResult {
        val name = args.optString("name")
        val compose = args.optBoolean("compose", false)
        val failure = dev.ely.warp.build.NewProject.create(
            dir = env.project,
            name = name,
            applicationId = args.optString("package"),
            compose = compose,
        )
        if (failure != null) return ToolResult.Failed(failure)

        val meta = dev.ely.warp.build.NewProject.meta(env.project)
        // Lists what it made. "Project created" gives you nothing to check, and
        // the file names are exactly what the next tool call will refer to.
        return ToolResult.Ok(
            "created ${meta?.applicationId}" + if (compose) " · Compose" else "",
            buildList {
                add("AndroidManifest.xml")
                add("res/values/strings.xml")
                add("res/values/styles.xml")
                if (!compose) {
                    add("res/layout/activity_main.xml")
                    add("res/values/colors.xml")
                    add("res/drawable/card.xml")
                }
                add("src/MainActivity.kt")
                // Listed because it is real and the model will see it in the
                // folder. A file that appears from nowhere invites being tidied
                // away, and this one is what reports crashes back to Warp.
                add("src/CrashReporter.kt  (sends crashes to Warp — leave it)")
                if (compose) {
                    add("")
                    // Said here as well as in the Brain, because this is the
                    // message the model reads immediately before writing its
                    // first screen.
                    add(
                        "This is a Compose project. Build the interface in " +
                            "Kotlin with @Composable functions — there is no " +
                            "layout XML and setContentView is not used."
                    )
                }
            }.joinToString("\n"),
        )
    }
}

/**
 * Say the goal is reached.
 *
 * A tool rather than a phrase, because "I think that's everything!" is
 * something a model says at the end of every turn whether or not it is true.
 * Calling this is a deliberate act with a shape, and the engine can act on it
 * without reading tea leaves.
 *
 * It changes nothing, which is why it is FREE — the *engine* stops the loop
 * when it sees the call, not the tool.
 */
object GoalDone : Tool {
    override val name = "goal_done"
    override val risk = Risk.FREE
    override val description =
        "Call this the moment the stated goal is true, and not before. Say how " +
            "you know. If it is not true yet, do not call this — keep working."
    override val schemaJson = """
        {"type":"object","properties":{
          "how_you_know":{"type":"string",
            "description":"The evidence. What you checked, and what it said."}},
         "required":["how_you_know"]}
    """.trimIndent()

    override fun describe(args: JSONObject) = args.optString("how_you_know")

    override suspend fun run(env: ToolEnv, args: JSONObject): ToolResult {
        // Refused while steps are still open — §5j.
        //
        // This is the concrete thing the checklist buys. `goal_done` already
        // demands evidence, but evidence of *what* was left to the model, and it
        // answered "I built the first playable prototype only" when asked
        // whether it had coded everything on the plan. A list it wrote itself is
        // something the answer can be measured against.
        //
        // **Not the goal giving up**, which §6 says it must never do. The
        // opposite: it is told there is work left and sent back to it, which is
        // exactly what the nine ordinary messages that finished the job by hand
        // did. And the way out is named in the refusal rather than left to be
        // guessed at — a step that turns out to be wrong is corrected with
        // `set_tasks`, not by pretending it is done.
        val open = env.tasks?.open().orEmpty()
        if (open.isNotEmpty()) {
            return ToolResult.Failed(
                "not finished — ${open.size} step${if (open.size == 1) "" else "s"} " +
                    "still open: " + open.joinToString("; ") { it.text } +
                    ". Finish them. If the plan itself was wrong, call set_tasks " +
                    "with the corrected list and carry on."
            )
        }
        return ToolResult.Ok("goal reached", args.optString("how_you_know"))
    }
}

// A shell glob, as a regex.
//
// A double star crosses directories and a single one does not, which is the
// distinction the pattern is written for: "*.kt" should mean this folder, and
// the recursive form should mean everywhere. Translating both to ".*" makes
// every pattern recursive and quietly ignores what was asked for.
//
// Written as line comments rather than a doc block on purpose — a glob example
// inside a /* block contains a star followed by a slash, which ends the comment
// halfway through the sentence explaining it.
internal fun globToRegex(glob: String): Regex {
    val out = StringBuilder("^")
    var i = 0
    while (i < glob.length) {
        when (val c = glob[i]) {
            '*' -> if (glob.getOrNull(i + 1) == '*') {
                out.append(".*"); i++
            } else {
                out.append("[^/]*")
            }
            '?' -> out.append("[^/]")
            '.', '(', ')', '+', '|', '^', '$', '@', '%', '{', '}', '[', ']' ->
                out.append('\\').append(c)
            else -> out.append(c)
        }
        i++
    }
    return Regex(out.append('$').toString())
}

/** Enough to be useful, few enough to fit in a context window. */
private const val LIMIT = 200

/** The tools that only look. Kept named because "never asks" is a promise. */
val READ_TOOLS: Map<String, Tool> =
    // `android_docs` belongs here rather than beside the writing tools: it only
    // looks, and a `/plan` turn is exactly when knowing the real icon sizes
    // matters most. A planner that has to guess writes a plan that has to be
    // corrected.
    // `past_session` reads only what you already said, in this app, and changes
    // nothing — so it belongs with the tools that look. It is also exactly what
    // a planning turn wants: the cheapest way to avoid repeating a week ago.
    listOf(ReadFile, ListDir, Glob, Grep, AndroidDocs, PastSessions).associateBy { it.name }

/** Everything the model can be offered, by name. */
val ALL_TOOLS: Map<String, Tool> =
    // `fetch_url` deliberately not in READ_TOOLS: a subagent is handed the
    // files it may read and nothing else, and §5c's whole argument is that it
    // cannot wander because there is nowhere to wander to. A URL is somewhere
    // to wander to. The main agent fetches; the helper reads what it was given.
    (READ_TOOLS.values +
        listOf(NewProjectTool, WriteFile, EditFile, AskUser, GoalDone, Delegate, FetchUrl) +
        listOf(SetTasks, TaskDone) +
        // §5n. Deliberately not in READ_TOOLS: a helper reading files is one
        // thing, and a helper with the ability to push is another.
        GIT_TOOLS +
        // §8. Not in READ_TOOLS for the plainest reason there is: it spends
        // money, and a helper that can spend money without being asked is a
        // helper nobody should have given a key to.
        listOf(MakeIcon) +
        DEVICE_TOOLS).associateBy { it.name }

/** Everything, plus the way out. What a `/goal` turn is given. */
val GOAL_TOOL_SPECS: List<dev.ely.warp.ai.ToolSpec> =
    (ALL_TOOLS.values.filter { it.risk != Risk.ASKS }).map {
        dev.ely.warp.ai.ToolSpec(it.name, it.description, it.schemaJson)
    }

/** Reading, plus the one tool that asks you. What `/grill-me` is given. */
val GRILL_TOOL_SPECS: List<dev.ely.warp.ai.ToolSpec> =
    (READ_TOOLS.values + AskUser).map {
        dev.ely.warp.ai.ToolSpec(it.name, it.description, it.schemaJson)
    }

/**
 * Just the reading tools, for a turn that is not allowed to act — plus the list.
 *
 * `/plan` gets `set_tasks` and `task_done` even though it may change nothing,
 * because a planning turn that produces a checklist *is* the plan, and the two
 * tools write nothing but the list itself.
 *
 * Built from `READ_TOOLS.values` plus these two rather than by widening
 * `READ_TOOLS`, and that distinction is load-bearing: §5c hands a subagent tools
 * out of that map, and a helper must not be able to rewrite the main plan.
 */
val READ_TOOL_SPECS: List<dev.ely.warp.ai.ToolSpec> =
    (READ_TOOLS.values + SetTasks + TaskDone).map {
        dev.ely.warp.ai.ToolSpec(it.name, it.description, it.schemaJson)
    }

/** The same tools, in the shape a provider hands to a model. */
val ALL_TOOL_SPECS: List<dev.ely.warp.ai.ToolSpec> =
    // Without `ask`, and without `goal_done`. A model holding a question tool
    // uses it to check in, which its own description forbids; a model holding
    // goal_done with no goal set has nothing true to say with it. Both belong
    // to the mode that needs them.
    ALL_TOOLS.values.filter { it.risk != Risk.ASKS && it.name != "goal_done" }.map {
        dev.ely.warp.ai.ToolSpec(it.name, it.description, it.schemaJson)
    }
