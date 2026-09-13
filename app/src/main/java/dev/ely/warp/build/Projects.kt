package dev.ely.warp.build

import android.content.Context
import java.io.File

/**
 * Where each app lives.
 *
 * One folder per conversation, because §9h's shelf is a list of apps and a
 * single shared folder can only ever hold one. Until now every chat wrote into
 * `files/project`, so asking for a second app silently overwrote the first —
 * you would go back to the tic-tac-toe conversation and find a weather app in it.
 *
 * Keyed by conversation id rather than by name, for the same reason the drawer
 * is: two apps can be called Notes, and a folder named after something the user
 * can rename is a folder that gets orphaned the first time they rename it.
 *
 * **This is also the fix for the permission caveat.** Tool grants are per
 * conversation, but with one shared folder a grant in chat A protected nothing
 * from chat B — same files. Now the scope of the grant and the scope of the
 * files finally agree.
 */
object Projects {

    /** Everything, under one roof, so the shelf has somewhere to look. */
    fun root(context: Context): File =
        File(context.filesDir, "projects").apply { mkdirs() }

    /**
     * The folder for one conversation.
     *
     * @param conversationId null before the first message has created one. That
     *   is a real state and not an error: the engine creates a conversation on
     *   the first message, so a tool running before then has nowhere sensible to
     *   write. It gets a scratch folder rather than a crash — and rather than
     *   the previous behaviour, which was to write into whatever the last chat
     *   had been using.
     */
    fun forConversation(context: Context, conversationId: String?): File =
        File(root(context), conversationId ?: SCRATCH).apply { mkdirs() }

    /** One app on the shelf. */
    data class App(
        val conversationId: String,
        val name: String,
        val applicationId: String,
        /** Files written so far. Zero means the folder exists but is empty. */
        val fileCount: Int,
        /** True once a build has produced an APK that is still on disk. */
        val built: Boolean,
        val apkBytes: Long,
        /** When the folder was last touched, for ordering. */
        val updatedAt: Long,
        /** Taken from the app's own icon. §9h's identity colour. */
        val colour: Int,
    )

    /**
     * Every conversation that has become an app.
     *
     * §9h: *a conversation becomes an app when the AI writes its first file.*
     * So the test is the presence of a project, not a flag somebody has to set
     * and keep in step. A folder with nothing in it is not an app and does not
     * appear — that would put a row on the shelf for every chat that merely
     * thought about building something.
     */
    fun all(context: Context): List<App> =
        root(context).listFiles().orEmpty()
            .filter { it.isDirectory && it.name != SCRATCH }
            .mapNotNull { dir ->
                val meta = NewProject.meta(dir) ?: return@mapNotNull null
                // Source only. The APK and the metadata are outputs, and a
                // count that includes them disagrees with the file list it
                // labels — the row said 6 files and tapping it showed 5.
                //
                // `.git` is the same fault at a larger scale, and it arrived
                // with §5n: a project of eight files read "76 files" because git
                // keeps its own objects inside the folder. Seen in a screenshot
                // within a minute of the feature existing.
                val files = dir.walkTopDown()
                    .onEnter { it.name != ".git" }
                    .filter { it.isFile && it.name != "app.apk" && it.name != "warp.json" }
                    .count()
                val apk = NewProject.lastApk(dir)
                App(
                    conversationId = dir.name,
                    name = meta.name,
                    applicationId = meta.applicationId,
                    fileCount = files,
                    built = apk != null,
                    apkBytes = apk?.length() ?: 0L,
                    updatedAt = dir.lastModified(),
                    colour = meta.colour,
                )
            }
            .sortedByDescending { it.updatedAt }

    /**
     * Delete the folders of conversations that no longer exist.
     *
     * The counterpart to the foreign key that cascades tool grants: SQL can
     * clean up its own rows, but a folder full of Kotlin needs somebody to go
     * and remove it. Without this, deleting a chat left its app on the phone
     * for ever — invisible, unreachable, and still taking space.
     *
     * @param alive every id the database still knows, **including soft-deleted
     *   ones**. Those are still undoable, and removing the files behind an
     *   undoable conversation would turn Undo into a lie: the row would come
     *   back and its app would not.
     * @return how many were removed.
     */
    fun removeOrphans(context: Context, alive: Set<String>): Int =
        root(context).listFiles().orEmpty()
            .filter { it.isDirectory && it.name != SCRATCH && it.name !in alive }
            .count { it.deleteRecursively() }

    /** Everything this conversation built, gone. Used when you confirm a delete. */
    fun remove(context: Context, conversationId: String): Boolean =
        forConversation(context, conversationId).deleteRecursively()

    /**
     * Move the one pre-existing project into its conversation's folder.
     *
     * Warp is unreleased, so there is exactly one of these in the world and it
     * is on the author's phone — but it holds a working game that took real
     * money to generate, and deleting it to simplify a migration would be the
     * wrong trade. Runs once; afterwards the old path does not exist.
     */
    fun adoptLegacy(context: Context, conversationId: String): Boolean {
        val legacy = File(context.filesDir, "project")
        if (!legacy.isDirectory || NewProject.meta(legacy) == null) return false

        val target = forConversation(context, conversationId)
        if (NewProject.meta(target) != null) return false  // never overwrite

        return legacy.renameTo(target).also {
            // A rename across the same filesystem is atomic; if it somehow
            // failed, leave the original alone rather than half-copying it.
            if (!it) legacy.copyRecursively(target, overwrite = false)
        }
    }

    private const val SCRATCH = "_scratch"
}
