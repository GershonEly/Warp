package dev.ely.warp.tools

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

    suspend fun run(project: File, args: JSONObject): ToolResult
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

    override suspend fun run(project: File, args: JSONObject): ToolResult {
        val path = args.optString("path").ifBlank { return ToolResult.Failed("no path given") }
        val file = resolve(project, path) ?: return ToolResult.Failed("outside the project")
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

    override suspend fun run(project: File, args: JSONObject): ToolResult {
        val dir = resolve(project, args.optString("path").ifBlank { "." })
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

    override suspend fun run(project: File, args: JSONObject): ToolResult {
        val pattern = args.optString("pattern")
            .ifBlank { return ToolResult.Failed("no pattern given") }
        val regex = globToRegex(pattern)

        val hits = project.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(project).path.replace(File.separatorChar, '/') }
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

    override suspend fun run(project: File, args: JSONObject): ToolResult {
        val pattern = args.optString("pattern")
            .ifBlank { return ToolResult.Failed("no pattern given") }
        val regex = runCatching { Regex(pattern) }
            .getOrElse { return ToolResult.Failed("bad pattern: ${it.message}") }

        val within = args.optString("glob").takeIf { it.isNotBlank() }?.let { globToRegex(it) }
        val out = StringBuilder()
        var count = 0

        project.walkTopDown().filter { it.isFile }.forEach { file ->
            val rel = file.relativeTo(project).path.replace(File.separatorChar, '/')
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
            "Prefer edit_file when only part of a file changes."
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

    override suspend fun run(project: File, args: JSONObject): ToolResult {
        val path = args.optString("path").ifBlank { return ToolResult.Failed("no path given") }
        val file = resolve(project, path) ?: return ToolResult.Failed("outside the project")
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
        return ToolResult.Ok("$what · $lines lines", content)
    }
}

object EditFile : Tool {
    override val name = "edit_file"
    override val risk = Risk.WRITES
    override val description =
        "Replace an exact piece of text in a file. The old text must appear " +
            "exactly once, so include enough context to make it unique."
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

    override suspend fun run(project: File, args: JSONObject): ToolResult {
        val path = args.optString("path").ifBlank { return ToolResult.Failed("no path given") }
        val file = resolve(project, path) ?: return ToolResult.Failed("outside the project")
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

        runCatching { file.writeText(text.replace(old, new)) }
            .getOrElse { return ToolResult.Failed(it.message ?: "could not write") }

        val removed = old.count { it == '\n' } + 1
        val added = new.count { it == '\n' } + 1
        return ToolResult.Ok(
            "-$removed +$added lines",
            // A diff rather than the whole file. It is what you look at before
            // saying yes, and what the model needs to see it landed.
            buildString {
                old.lines().forEach { appendLine("- $it") }
                new.lines().forEach { appendLine("+ $it") }
            }.trimEnd(),
        )
    }
}

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
    override suspend fun run(project: File, args: JSONObject): ToolResult =
        ToolResult.Failed("ask is answered by you, not by the project")
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
    listOf(ReadFile, ListDir, Glob, Grep).associateBy { it.name }

/** Everything the model can be offered, by name. */
val ALL_TOOLS: Map<String, Tool> =
    (READ_TOOLS.values + listOf(WriteFile, EditFile, AskUser)).associateBy { it.name }

/** Reading, plus the one tool that asks you. What `/grill-me` is given. */
val GRILL_TOOL_SPECS: List<dev.ely.warp.ai.ToolSpec> =
    (READ_TOOLS.values + AskUser).map {
        dev.ely.warp.ai.ToolSpec(it.name, it.description, it.schemaJson)
    }

/** Just the reading tools, for a turn that is not allowed to act. */
val READ_TOOL_SPECS: List<dev.ely.warp.ai.ToolSpec> =
    READ_TOOLS.values.map {
        dev.ely.warp.ai.ToolSpec(it.name, it.description, it.schemaJson)
    }

/** The same tools, in the shape a provider hands to a model. */
val ALL_TOOL_SPECS: List<dev.ely.warp.ai.ToolSpec> =
    // Without `ask`. An ordinary turn should get on with it; a model holding a
    // question tool will use it to check in, which is exactly what its own
    // description forbids and exactly what it would do anyway. Asking is what
    // `/grill-me` is for, and it can be opened up later if that proves wrong.
    ALL_TOOLS.values.filter { it.risk != Risk.ASKS }.map {
        dev.ely.warp.ai.ToolSpec(it.name, it.description, it.schemaJson)
    }
