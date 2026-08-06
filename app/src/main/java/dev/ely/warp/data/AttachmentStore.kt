package dev.ely.warp.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import dev.ely.warp.ai.Attachment
import java.io.File
import java.util.UUID

private const val TAG = "WarpAttach"

/**
 * Where the bytes go — §5h.
 *
 * A picked file arrives as a `content://` URI, which is a permission to read
 * something *right now* rather than a place. Reopening one later usually fails,
 * and always fails after a reboot, so what gets stored is a copy Warp owns.
 *
 * Kept beside the conversation rather than inside its project. A project is the
 * app being built and everything in it ends up in an APK; a screenshot of a bug
 * is evidence about that app, not part of it.
 */
object AttachmentStore {

    /**
     * Twenty megabytes, and it is about the model rather than the disk.
     *
     * Phones have room for a video. What they do not have is a reason to send
     * one — every byte becomes tokens, on the account holder's own key.
     */
    private const val MAX_BYTES = 20L * 1024 * 1024

    private fun dir(context: Context, conversationId: String) =
        File(File(context.filesDir, "attachments"), conversationId).apply { mkdirs() }

    /**
     * Copy what was picked into Warp's own storage.
     *
     * @return the attachment, or null with the reason logged — a file that
     *   cannot be read is a thing that happened, not an exception.
     */
    fun take(context: Context, conversationId: String, uri: Uri): Result<Attachment> {
        val name = displayName(context, uri) ?: "attachment"
        val mime = context.contentResolver.getType(uri)
            ?: guessType(name)

        val size = sizeOf(context, uri)
        if (size != null && size > MAX_BYTES) {
            return Result.failure(
                IllegalArgumentException(
                    "${name} is ${size / 1024 / 1024} MB — the limit is ${MAX_BYTES / 1024 / 1024} MB"
                )
            )
        }

        val id = UUID.randomUUID().toString()
        // The stored name is the id, never theirs: a picked file can be called
        // "../../databases/warp.db" and a display name is not a path.
        val target = File(dir(context, conversationId), "$id-${name.takeLast(60).sanitised()}")

        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: error("could not open $name")

            Attachment(
                id = id,
                name = name,
                mimeType = mime,
                path = target.absolutePath,
                bytes = target.length(),
            )
        }.onFailure {
            Log.w(TAG, "could not take $name", it)
            target.delete()
        }
    }

    /** Everything belonging to a conversation, when it is deleted for good. */
    fun forget(context: Context, conversationId: String) {
        runCatching { dir(context, conversationId).deleteRecursively() }
    }

    private fun String.sanitised() = filter { it.isLetterOrDigit() || it in "._-" }
        .ifBlank { "file" }

    private fun displayName(context: Context, uri: Uri): String? =
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) c.getString(i) else null
            }
        }.getOrNull() ?: uri.lastPathSegment

    private fun sizeOf(context: Context, uri: Uri): Long? =
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.SIZE)
                if (i >= 0 && c.moveToFirst() && !c.isNull(i)) c.getLong(i) else null
            }
        }.getOrNull()

    /**
     * A type from the name, for when the resolver has no opinion.
     *
     * It often has none for files shared out of other apps, and
     * `application/octet-stream` on a `.kt` would send a source file down the
     * "cannot be read" path — the one kind of attachment that needs no vision
     * model at all.
     */
    private fun guessType(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "mp4" -> "video/mp4"
        "m4a", "aac" -> "audio/aac"
        "mp3" -> "audio/mpeg"
        else -> "application/octet-stream"
    }
}
