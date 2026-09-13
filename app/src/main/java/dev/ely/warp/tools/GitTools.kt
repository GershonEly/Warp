package dev.ely.warp.tools

import dev.ely.warp.git.Repo
import org.json.JSONObject

/**
 * Git as tools — §5n.
 *
 * **Everything asks.** His rule, in his words: *"it should always ask… maybe
 * there is a button not to ask, but it asks."* That button is **Always** on the
 * permission card, which the desk already remembers per conversation.
 *
 * The split that matters is the second one: `push`, `pull` and `clone` are
 * `RUNS`, which §5f asks every single time and never lets anyone grant. They are
 * the tools that leave the phone, and a secret pushed to GitHub cannot be
 * un-published — deleting it afterwards does not un-fetch it.
 */

/** Said the same way by every tool here, so the fix reads the same too. */
private fun noRepo() = ToolResult.Failed(
    "this project is not in git yet — call git_init first"
)

object GitInit : Tool {
    override val name = "git_init"
    override val risk = Risk.WRITES
    override val description =
        "Start tracking this project with git, so changes can be undone later. " +
            "Ask before doing this to a project the person did not ask to track."
    override val schemaJson = """{"type":"object","properties":{}}"""

    override fun describe(args: JSONObject) = "start tracking this project"

    override suspend fun run(env: ToolEnv, args: JSONObject): ToolResult =
        runCatching { ToolResult.Ok(Repo.init(env.project), null) }
            .getOrElse { ToolResult.Failed(it.message ?: "could not start a repository") }
}

object GitCommit : Tool {
    override val name = "git_commit"
    override val risk = Risk.WRITES
    override val description =
        "Save a point you can come back to, with a short message saying what " +
            "changed. Do this after finishing something that works, not after " +
            "every edit."
    override val schemaJson = """
        {"type":"object","properties":{
          "message":{"type":"string","description":"One line: what changed and why."}},
         "required":["message"]}
    """.trimIndent()

    override fun describe(args: JSONObject) = args.optString("message").ifBlank { "save a point" }

    override suspend fun run(env: ToolEnv, args: JSONObject): ToolResult {
        if (!Repo.isRepo(env.project)) return noRepo()
        return runCatching {
            val entry = Repo.commit(env.project, args.optString("message"))
            // Nothing to commit is said plainly rather than recorded as an empty
            // save point. A log full of commits that changed nothing is a log
            // you cannot navigate.
                ?: return ToolResult.Ok("nothing had changed — no save point made", null)
            ToolResult.Ok("saved ${entry.id} · ${entry.message.lineSequence().first()}", null)
        }.getOrElse { ToolResult.Failed(it.message ?: "could not save") }
    }
}

object GitLog : Tool {
    override val name = "git_log"
    override val risk = Risk.WRITES
    override val description =
        "List the save points, newest first, with their ids — use an id with " +
            "git_restore to go back to one."
    override val schemaJson = """{"type":"object","properties":{}}"""

    override fun describe(args: JSONObject) = "the save points so far"

    override suspend fun run(env: ToolEnv, args: JSONObject): ToolResult {
        if (!Repo.isRepo(env.project)) return noRepo()
        return runCatching {
            val entries = Repo.log(env.project)
            if (entries.isEmpty()) return ToolResult.Ok("no save points yet", null)
            ToolResult.Ok(
                "${entries.size} save point${if (entries.size == 1) "" else "s"}",
                entries.joinToString("\n") {
                    "${it.id}  ${it.message.lineSequence().first()}"
                },
            )
        }.getOrElse { ToolResult.Failed(it.message ?: "could not read the history") }
    }
}

object GitDiff : Tool {
    override val name = "git_diff"
    override val risk = Risk.WRITES
    override val description =
        "Show what has changed since the last save point, as a diff. Use it to " +
            "check your own work before saving."
    override val schemaJson = """{"type":"object","properties":{}}"""

    override fun describe(args: JSONObject) = "what changed since the last save"

    override suspend fun run(env: ToolEnv, args: JSONObject): ToolResult {
        if (!Repo.isRepo(env.project)) return noRepo()
        return runCatching {
            val text = Repo.diff(env.project)
            if (text.isBlank()) ToolResult.Ok("nothing has changed", null)
            else ToolResult.Ok("${text.lines().size} lines of changes", text.take(20_000))
        }.getOrElse { ToolResult.Failed(it.message ?: "could not read the changes") }
    }
}

object GitRestore : Tool {
    override val name = "git_restore"
    override val risk = Risk.WRITES
    override val description =
        "Put every file back to how it was at a save point, throwing away " +
            "everything since. Get the id from git_log first, and say what will " +
            "be lost before you do it."
    override val schemaJson = """
        {"type":"object","properties":{
          "commit":{"type":"string","description":"The save point id from git_log."}},
         "required":["commit"]}
    """.trimIndent()

    // The card says what is about to happen, because this is the one tool here
    // that destroys work — §9f's argument, applied to files instead of messages.
    override fun describe(args: JSONObject) =
        "throw away everything after ${args.optString("commit")}"

    override suspend fun run(env: ToolEnv, args: JSONObject): ToolResult {
        if (!Repo.isRepo(env.project)) return noRepo()
        val commit = args.optString("commit").ifBlank {
            return ToolResult.Failed("which save point? call git_log to see them")
        }
        return runCatching { ToolResult.Ok(Repo.restore(env.project, commit), null) }
            .getOrElse { ToolResult.Failed(it.message ?: "could not go back") }
    }
}

// ── the half that leaves the phone ───────────────────────────────────────

/** Said the same way wherever a token is needed, so the fix is always the same. */
private fun noToken() = ToolResult.Failed(
    "no GitHub token saved yet — the person adds one in Settings, and it is " +
        "kept on the phone. Ask them for it rather than guessing a way round."
)

object GitPush : Tool {
    override val name = "git_push"
    override val risk = Risk.RUNS
    override val description =
        "Send the save points to a GitHub repository. Needs the repository " +
            "address the first time — never invent one, and never push to a " +
            "repository the person did not name."
    override val schemaJson = """
        {"type":"object","properties":{
          "url":{"type":"string",
                 "description":"https://github.com/owner/name.git — only needed once."}},
         "required":[]}
    """.trimIndent()

    override fun describe(args: JSONObject): String =
        args.optString("url").takeIf { it.isNotBlank() }?.let { "push to $it" }
            ?: "push to GitHub"

    override suspend fun run(env: ToolEnv, args: JSONObject): ToolResult {
        if (!Repo.isRepo(env.project)) return noRepo()
        val token = Repo.token(env.context) ?: return noToken()

        args.optString("url").takeIf { it.isNotBlank() }?.let { Repo.setRemote(env.project, it) }
        val remote = Repo.remote(env.project)
            ?: return ToolResult.Failed(
                "no repository set for this project yet — ask the person which " +
                    "GitHub repository it should go to, and pass it as url"
            )

        return runCatching { ToolResult.Ok(Repo.push(env.project, token), "to $remote") }
            .getOrElse { ToolResult.Failed(it.message ?: "could not push") }
    }
}

object GitPull : Tool {
    override val name = "git_pull"
    override val risk = Risk.RUNS
    override val description =
        "Bring down changes from the GitHub repository this project is linked to."
    override val schemaJson = """{"type":"object","properties":{}}"""

    override fun describe(args: JSONObject) = "fetch changes from GitHub"

    override suspend fun run(env: ToolEnv, args: JSONObject): ToolResult {
        if (!Repo.isRepo(env.project)) return noRepo()
        Repo.remote(env.project) ?: return ToolResult.Failed("no repository set for this project")
        return runCatching {
            ToolResult.Ok(Repo.pull(env.project, Repo.token(env.context)), null)
        }.getOrElse { ToolResult.Failed(it.message ?: "could not pull") }
    }
}

object GitCreateRepo : Tool {
    override val name = "git_create_repo"
    override val risk = Risk.RUNS
    override val description =
        "Make a NEW repository on GitHub for this app and link it. Only when " +
            "the person asks for one — if they named a repository that already " +
            "exists, use git_push with its address instead. Always ask whether " +
            "it should be private."
    override val schemaJson = """
        {"type":"object","properties":{
          "name":{"type":"string","description":"The repository name, e.g. Streak."},
          "private":{"type":"boolean","description":"Ask the person. Do not assume."}},
         "required":["name","private"]}
    """.trimIndent()

    override fun describe(args: JSONObject): String {
        val visibility = if (args.optBoolean("private")) "private" else "public"
        return "create a $visibility repo called ${args.optString("name")}"
    }

    override suspend fun run(env: ToolEnv, args: JSONObject): ToolResult {
        if (!Repo.isRepo(env.project)) return noRepo()
        val token = Repo.token(env.context) ?: return noToken()
        val name = args.optString("name").ifBlank {
            return ToolResult.Failed("what should the repository be called?")
        }

        return runCatching {
            val created = dev.ely.warp.git.GitHub.createRepo(
                token = token,
                name = name,
                private = args.optBoolean("private", true),
            )
            Repo.setRemote(env.project, created.cloneUrl)
            ToolResult.Ok("created ${created.fullName}", created.htmlUrl)
        }.getOrElse { ToolResult.Failed(it.message ?: "could not create the repository") }
    }
}

/** Everything git, named once so the registry and the docs cannot drift apart. */
val GIT_TOOLS: List<Tool> = listOf(
    GitInit, GitCommit, GitLog, GitDiff, GitRestore,
    GitPush, GitPull, GitCreateRepo,
)
