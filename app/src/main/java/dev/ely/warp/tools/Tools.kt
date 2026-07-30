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

/** Every tool the model can be offered, by name. */
val READ_TOOLS: Map<String, Tool> =
    listOf(ReadFile, ListDir, Glob, Grep).associateBy { it.name }

/** The same tools, in the shape a provider hands to a model. */
val READ_TOOL_SPECS: List<dev.ely.warp.ai.ToolSpec> =
    READ_TOOLS.values.map {
        dev.ely.warp.ai.ToolSpec(it.name, it.description, it.schemaJson)
    }
