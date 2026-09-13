package dev.ely.warp.git

import android.content.Context
import dev.ely.warp.ai.KeyVault
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Git, on the phone — §5n.
 *
 * §5i calls the absence of this the scariest gap in the plan: *there is no undo
 * today*. One bad edit takes an app apart and nothing goes back.
 *
 * Everything here is **local** except [push], [pull] and [clone], and that split
 * is the permission model: a save point costs nothing and cannot be seen by
 * anyone, while sending code to GitHub is public and cannot be taken back.
 *
 * JGit was proved on the device before any of this was written — a repository
 * made, a commit written and read back, and the SSH artifact loaded. Android has
 * no home directory, no git config and no user identity, and JGit looks for all
 * three, so that was not a safe assumption.
 */
object Repo {

    /** What a stored credential is called in the vault, beside the API keys. */
    private const val TOKEN = "git.token"

    /**
     * Who commits show up as.
     *
     * Fixed rather than asked for. A git identity is an email address published
     * with every commit, and asking somebody to type their real one into a
     * coding tool to get an undo button is a bad trade. It can be changed later
     * if anyone ever wants their name on it.
     */
    private const val WHO = "Warp"
    private const val WHERE = "warp@localhost"

    /** How many commits a log returns before it stops being a thing you read. */
    private const val LOG_LIMIT = 30

    data class Entry(val id: String, val message: String, val whenMillis: Long)

    /** True once this folder is a repository. */
    fun isRepo(project: File): Boolean = File(project, ".git").isDirectory

    private fun <T> open(project: File, body: (Git) -> T): T =
        Git.open(project).use(body)

    /**
     * Start tracking, and refuse to start twice.
     *
     * A second `init` on a repository that already exists would quietly do
     * nothing in real git, and "quietly did nothing" is the answer this project
     * most dislikes.
     */
    fun init(project: File): String {
        if (isRepo(project)) return "already a repository"
        Git.init().setDirectory(project).call().use { }
        writeIgnore(project)
        return "started tracking"
    }

    /**
     * What must never be committed.
     *
     * Written at init rather than left to the model. A key or a build output in
     * a repository that is about to be pushed is the one mistake here that
     * cannot be undone — see §5n on why push always asks.
     */
    private fun writeIgnore(project: File) {
        val file = File(project, ".gitignore")
        if (file.exists()) return
        file.writeText(
            """
            build/
            *.apk
            *.keystore
            *.jks
            local.properties
            warp.json
            """.trimIndent() + "\n"
        )
    }

    /**
     * Everything that changed, as one save point.
     *
     * Returns null when there is nothing to commit, so the caller can say so
     * rather than writing an empty commit that pretends work happened.
     */
    fun commit(project: File, message: String): Entry? = open(project) { git ->
        git.add().addFilepattern(".").call()
        // Removals too. `add .` stages new and changed files but not deleted
        // ones, so a commit after a delete would silently keep the file.
        git.add().addFilepattern(".").setUpdate(true).call()

        if (git.status().call().isClean) return@open null

        val commit = git.commit()
            .setMessage(message.ifBlank { "Changes from Warp" })
            .setAuthor(WHO, WHERE)
            .call()
        Entry(commit.name.take(8), commit.fullMessage, commit.commitTime * 1000L)
    }

    fun log(project: File): List<Entry> = open(project) { git ->
        git.log().setMaxCount(LOG_LIMIT).call().map {
            Entry(it.name.take(8), it.fullMessage.trim(), it.commitTime * 1000L)
        }
    }

    /** What has changed since the last commit, as a unified diff. */
    fun diff(project: File): String = open(project) { git ->
        val out = ByteArrayOutputStream()
        DiffFormatter(out).use { formatter ->
            formatter.setRepository(git.repository)
            val head = git.repository.resolve("HEAD^{tree}")
            val old = if (head == null) {
                // Nothing committed yet, so everything is new. An empty tree
                // rather than a failure — "no commits" is a state, not an error.
                CanonicalTreeParser()
            } else {
                CanonicalTreeParser().apply {
                    git.repository.newObjectReader().use { reset(it, head) }
                }
            }
            formatter.format(old, org.eclipse.jgit.treewalk.FileTreeIterator(git.repository))
        }
        out.toString(Charsets.UTF_8.name())
    }

    /**
     * Put the project back to a commit.
     *
     * Hard, because a half-restore is worse than none: the point of asking for
     * this is to stop having a mixture of two versions.
     */
    fun restore(project: File, commitId: String): String = open(project) { git ->
        val target: ObjectId = git.repository.resolve(commitId)
            ?: return@open "no commit called $commitId"
        val commit: RevCommit = git.repository.parseCommit(target)
        git.reset().setMode(ResetCommand.ResetType.HARD).setRef(commit.name).call()
        "back to ${commit.name.take(8)} · ${commit.fullMessage.trim().lineSequence().first()}"
    }

    // ── the half that leaves the phone ───────────────────────────────────

    /** The saved token, or null. Used for pushing and for creating a repo. */
    fun token(context: Context): String? =
        KeyVault.load(context, TOKEN)?.takeIf { it.isNotBlank() }

    fun saveToken(context: Context, token: String): Boolean =
        KeyVault.save(context, TOKEN, token)

    fun hasToken(context: Context): Boolean = KeyVault.hasKey(context, TOKEN)

    /**
     * A token is a password, and JGit wants it as one.
     *
     * GitHub takes the token as the password with any username, which is why
     * the name here is a constant nobody has to be asked for.
     */
    private fun credentials(token: String) =
        UsernamePasswordCredentialsProvider("warp", token)

    /**
     * Point this repository at a remote, replacing whatever was there.
     *
     * Replacing rather than adding, because a project that has been pushed to
     * the wrong place should be fixable by saying the right place once.
     */
    fun setRemote(project: File, url: String): Unit = open(project) { git ->
        git.remoteRemove().setRemoteName(Constants.DEFAULT_REMOTE_NAME).call()
        git.remoteAdd()
            .setName(Constants.DEFAULT_REMOTE_NAME)
            .setUri(org.eclipse.jgit.transport.URIish(url))
            .call()
    }

    fun remote(project: File): String? = open(project) { git ->
        git.repository.config
            .getString("remote", Constants.DEFAULT_REMOTE_NAME, "url")
    }

    fun push(project: File, token: String): String = open(project) { git ->
        val branch = git.repository.branch ?: "main"
        val results = git.push()
            .setCredentialsProvider(credentials(token))
            .setRemote(Constants.DEFAULT_REMOTE_NAME)
            // Named explicitly so a fresh repository with no upstream still
            // works — the common case here, since every app starts empty.
            .setRefSpecs(RefSpec("refs/heads/$branch:refs/heads/$branch"))
            .call()

        // What the server said, not that the call returned. A push can come
        // back "ok" at the transport level and still have been rejected.
        val messages = results.flatMap { it.remoteUpdates }
            .map { "${it.remoteName.substringAfterLast('/')}: ${it.status}" }
        "pushed $branch · " + messages.joinToString(", ").ifBlank { "nothing to send" }
    }

    fun pull(project: File, token: String?): String = open(project) { git ->
        val pull = git.pull().setRemote(Constants.DEFAULT_REMOTE_NAME)
        token?.let { pull.setCredentialsProvider(credentials(it)) }
        val result = pull.call()
        if (result.isSuccessful) "up to date with the remote" else "could not merge cleanly"
    }

    fun clone(into: File, url: String, token: String?): String {
        val clone = Git.cloneRepository().setURI(url).setDirectory(into)
        token?.let { clone.setCredentialsProvider(credentials(it)) }
        clone.call().use { git -> return "cloned into ${into.name} · ${branchOf(git.repository)}" }
    }

    private fun branchOf(repository: Repository): String =
        repository.branch?.let { "on $it" } ?: "detached"
}
