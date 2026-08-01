package dev.ely.warp.build

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * Where an app built by Warp posts its dying words.
 *
 * Reading another app's crash out of logcat does not work and cannot be made to
 * work: Android limits an app to its own log unless `READ_LOGS` is granted, and
 * on Xiaomi that grant is a **one-time prompt**. It worked in one session and
 * had expired an hour later, which is worse than never working — a tool that is
 * right in the morning and silent in the afternoon teaches you to distrust it.
 *
 * So the crash is *delivered* instead of hunted for. Every generated app gets a
 * handler that writes here before it dies, and Warp keeps the trace in its own
 * storage where no permission can be taken away.
 *
 * **Exported, and unguarded on purpose.** Any app on the phone could post a
 * fake crash. That is an acceptable trade: the content is only ever shown inside
 * Warp's own chat, attributed to the package that claims it, and the alternative
 * — a signature check — cannot work, because Warp signs generated apps with a
 * debug key that is not the key Warp itself is signed with.
 */
class CrashInbox : ContentProvider() {

    override fun onCreate() = true

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val context = context ?: return null
        val id = values?.getAsString(COLUMN_PACKAGE)?.takeIf { it.isNotBlank() } ?: return null
        val trace = values.getAsString(COLUMN_TRACE).orEmpty()

        // The file name is the package, so a second crash from the same app
        // replaces the first. The newest is the one being debugged; a growing
        // pile would mean reading yesterday's failure while chasing today's.
        runCatching {
            folder(context).resolve(safeName(id)).writeText(trace)
            Log.i(TAG, "crash from $id, ${trace.length} chars")
        }.onFailure { Log.w(TAG, "could not store crash from $id", it) }

        return uri
    }

    // Nothing else is offered. A provider that only accepts is a provider with
    // one thing to get wrong.
    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor? = null

    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?,
    ) = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

    override fun getType(uri: Uri): String? = null

    companion object {
        private const val TAG = "WarpCrash"

        const val AUTHORITY = "dev.ely.warp.crashes"
        const val COLUMN_PACKAGE = "package"
        const val COLUMN_TRACE = "trace"

        private fun folder(context: android.content.Context) =
            File(context.filesDir, "crashes").apply { mkdirs() }

        /** A package name is already safe, but a hostile one need not be. */
        private fun safeName(id: String) =
            id.map { if (it.isLetterOrDigit() || it == '.' || it == '_') it else '_' }
                .joinToString("") + ".txt"

        /** The last crash from this app, or null if it has not reported one. */
        fun lastCrash(context: android.content.Context, applicationId: String): String? =
            folder(context).resolve(safeName(applicationId))
                .takeIf { it.isFile }
                ?.let { runCatching { it.readText() }.getOrNull() }
                ?.takeIf { it.isNotBlank() }

        /** Forget it, so the next read cannot return a crash that was fixed. */
        fun clear(context: android.content.Context, applicationId: String) {
            runCatching { folder(context).resolve(safeName(applicationId)).delete() }
        }
    }
}
