package dev.ely.warp.ai

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Something you showed it — §5h.
 *
 * You could always tell Warp what was wrong. You could not show it, and the
 * cost of that is written down: forty messages of blind debugging in the
 * CallVault session, with *"no way to see your screen"* said out loud, and every
 * screenshot in this project going to a person on a PC instead.
 *
 * The bytes live on disk and the message holds a path, which is the same shape
 * the shelf uses for a project. Messages persist in Room; a megabyte of PNG
 * cannot, and a database row is the wrong place to learn that.
 */
data class Attachment(
    val id: String,
    /** What it was called where it came from. Shown, never trusted as a path. */
    val name: String,
    val mimeType: String,
    /** Absolute path on this phone. Null once the file has been cleaned up. */
    val path: String,
    val bytes: Long,
) {
    val kind: Kind
        get() = when {
            mimeType.startsWith("image/") -> Kind.IMAGE
            mimeType.startsWith("video/") -> Kind.VIDEO
            mimeType.startsWith("audio/") -> Kind.AUDIO
            isTextish -> Kind.TEXT
            else -> Kind.OTHER
        }

    /**
     * Whether this is really just text wearing a file extension.
     *
     * Checked by more than the MIME type, because Android hands back
     * `application/octet-stream` for a `.kt` about as often as it hands back
     * anything useful — and a source file arriving as OTHER would be the one
     * kind of attachment that needs no vision model at all, thrown away for a
     * guess made by whichever app shared it.
     */
    private val isTextish: Boolean
        get() = mimeType.startsWith("text/") ||
            mimeType in TEXT_TYPES ||
            name.substringAfterLast('.', "").lowercase() in TEXT_EXTENSIONS

    val file: File get() = File(path)

    enum class Kind { IMAGE, VIDEO, AUDIO, TEXT, OTHER }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("mimeType", mimeType)
        .put("path", path)
        .put("bytes", bytes)

    companion object {
        private val TEXT_TYPES = setOf(
            "application/json", "application/xml", "application/javascript",
            "application/x-yaml", "application/octet-stream",
        )

        private val TEXT_EXTENSIONS = setOf(
            "kt", "kts", "java", "xml", "json", "txt", "md", "log", "gradle",
            "properties", "yml", "yaml", "csv", "sh", "py", "js", "ts", "html",
            "css", "toml", "cfg", "ini", "pro",
        )

        fun fromJson(o: JSONObject) = Attachment(
            id = o.optString("id"),
            name = o.optString("name"),
            mimeType = o.optString("mimeType"),
            path = o.optString("path"),
            bytes = o.optLong("bytes"),
        )

        fun listToJson(all: List<Attachment>): String? =
            if (all.isEmpty()) null
            else JSONArray().apply { all.forEach { put(it.toJson()) } }.toString()

        fun listFromJson(raw: String?): List<Attachment> {
            if (raw.isNullOrBlank()) return emptyList()
            return runCatching {
                val array = JSONArray(raw)
                (0 until array.length()).mapNotNull { i ->
                    array.optJSONObject(i)?.let(::fromJson)
                }
            }.getOrDefault(emptyList())
        }
    }
}

/**
 * Turning what you attached into what a provider can carry.
 *
 * Text goes into the message itself, which is why a log or a `.kt` works on
 * every model including the ones that cannot see — and is the most useful half
 * of §5h per unit of work. Pictures need each provider's own shape, so they are
 * handed over as bytes and arranged there.
 */
object Attachments {

    /**
     * How much of a text file is worth sending.
     *
     * A cap rather than the whole thing, because a 3 MB logcat dump is a bill
     * rather than evidence, and the useful part of a log is almost always the
     * end. So the tail is what survives, and the message says how much was cut.
     */
    private const val TEXT_CAP = 40_000

    /**
     * The longest edge a picture is sent at.
     *
     * A phone screenshot is around 1080 × 2400 and a photo far larger; no model
     * reads either at full size, and every pixel above this is billed for
     * nothing. Re-encoded as JPEG at 85, which is invisible on a screenshot and
     * roughly a tenth of the bytes.
     */
    private const val MAX_EDGE = 1568

    data class Image(val mimeType: String, val base64: String)

    /**
     * The message text with its text-ish attachments folded in.
     *
     * Fenced and named, so the model can tell the log from the question. This
     * is deliberately part of the *text* rather than a separate block: it costs
     * nothing extra, needs no capability, and works identically on every
     * provider Warp speaks to.
     */
    fun textFor(message: ChatMessage): String {
        val readable = message.attachments.filter { it.kind == Attachment.Kind.TEXT }
        if (readable.isEmpty()) return message.text

        return buildString {
            append(message.text)
            readable.forEach { attachment ->
                val whole = runCatching { attachment.file.readText() }.getOrNull()
                    ?: return@forEach
                val cut = whole.length > TEXT_CAP
                // The tail, not the head. A stack trace ends with the cause and
                // a build log ends with the error; the first 40,000 characters
                // of either are the part nobody needed.
                val body = if (cut) whole.takeLast(TEXT_CAP) else whole

                append("\n\n")
                append(attachment.name)
                if (cut) append(" (last $TEXT_CAP of ${whole.length} characters)")
                append(":\n```\n")
                append(body)
                append("\n```")
            }
        }.trim()
    }

    /**
     * The pictures on this message, decoded down and base64 encoded.
     *
     * Skips silently what it cannot read: a file deleted from under us is a
     * thing that happens, and it must not take the question down with it.
     */
    fun imagesFor(message: ChatMessage): List<Image> =
        message.attachments
            .filter { it.kind == Attachment.Kind.IMAGE }
            .mapNotNull { attachment ->
                runCatching { encode(attachment) }.getOrNull()
            }

    private fun encode(attachment: Attachment): Image? {
        val bounds = android.graphics.BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        android.graphics.BitmapFactory.decodeFile(attachment.path, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null

        val options = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = generateSequence(1) { it * 2 }.first { longest / it <= MAX_EDGE }
        }
        val bitmap = android.graphics.BitmapFactory.decodeFile(attachment.path, options)
            ?: return null

        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
        bitmap.recycle()

        return Image(
            mimeType = "image/jpeg",
            base64 = android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP),
        )
    }

    /** True when this message carries anything at all worth sending. */
    fun any(message: ChatMessage) = message.attachments.isNotEmpty()
}
