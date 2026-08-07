package dev.ely.warp.tools

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "WarpWeb"

/**
 * Reading a page — task M, §5g.
 *
 * Asked for three times in one session while building a call recorder: *do
 * research*, *check properly*, *search Google*. Answered honestly each time with
 * *"I have no internet access"* — and the answers were still right, which is the
 * part worth remembering. What was missing was never accuracy. It was
 * **evidence**: a claim that could be checked, rather than one that had to be
 * believed.
 *
 * This is the small half. It reads a page somebody names — no key, no per-query
 * cost, nothing to turn on. Search is the other half and is not here: it needs
 * an API key of its own and is charged per query, which on BYOK is the account
 * holder's money and therefore a setting rather than a default.
 */
object FetchUrl : Tool {
    override val name = "fetch_url"

    /**
     * RUNS, because it leaves the device.
     *
     * Not FREE despite changing nothing. FREE means "never asks", and a tool
     * that can send a request to any address on the internet is the last one
     * that should go unannounced — the person whose network it is should get to
     * say yes the first time.
     */
    override val risk = Risk.RUNS

    override val description =
        "Read a web page and get its text back. Give the full https:// address. " +
            "Use it when you need something you do not already know, or to check " +
            "a claim rather than assert it. It cannot search — you must know the " +
            "address. For Android facts use android_docs instead; it is free."

    override val schemaJson = """
        {"type":"object","properties":{
          "url":{"type":"string","description":"Full https:// address of the page."}},
         "required":["url"]}
    """.trimIndent()

    override fun describe(args: JSONObject): String =
        args.optString("url").takeIf { it.isNotBlank() } ?: "no address"

    override suspend fun run(env: ToolEnv, args: JSONObject): ToolResult =
        withContext(Dispatchers.IO) {
            val raw = args.optString("url").trim()
            if (raw.isBlank()) return@withContext ToolResult.Failed("no address given")

            val url = runCatching { URL(raw) }.getOrNull()
                ?: return@withContext ToolResult.Failed("that is not a web address")

            // https only. A page fetched in plaintext on somebody's phone
            // network can be read and rewritten by anything between here and
            // there, and "it was only a documentation page" is exactly how a
            // model ends up acting on someone else's text.
            if (!url.protocol.equals("https", ignoreCase = true)) {
                return@withContext ToolResult.Failed(
                    "only https addresses — ${url.protocol} is not encrypted"
                )
            }

            val connection = runCatching { url.openConnection() as HttpURLConnection }
                .getOrElse { return@withContext ToolResult.Failed("could not open $raw") }

            return@withContext try {
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.setRequestProperty("Accept", "text/html,text/plain,*/*")

                val code = connection.responseCode
                if (code !in 200..299) {
                    // The code, said plainly. "Failed to fetch" tells a model
                    // nothing it can act on; 404 tells it the address is wrong
                    // and 403 tells it the address is right.
                    return@withContext ToolResult.Failed("the page answered $code")
                }

                val type = connection.contentType.orEmpty()
                if (!type.isBlank() && !type.startsWith("text/") &&
                    !type.contains("json") && !type.contains("xml")
                ) {
                    return@withContext ToolResult.Failed(
                        "that is $type, not a page — this reads text, not files"
                    )
                }

                // Capped while reading, not after. A 40 MB page downloaded and
                // then trimmed has already cost the battery and the data, and
                // on a phone both are the user's.
                val body = connection.inputStream.bufferedReader()
                    .use { it.readText(MAX_BYTES) }

                val text = asReadableText(body)
                if (text.isBlank()) {
                    return@withContext ToolResult.Failed("the page had no readable text")
                }

                val cut = text.length > MAX_CHARS
                // The beginning, unlike a log. A page puts its answer at the
                // top and its navigation at the bottom; a stack trace does the
                // opposite, which is why the two are trimmed from opposite ends.
                val kept = if (cut) text.take(MAX_CHARS) else text

                ToolResult.Ok(
                    buildString {
                        append(url.host)
                        append(" · ${kept.length} chars")
                        if (cut) append(" (of ${text.length})")
                    },
                    kept,
                )
            } catch (e: Exception) {
                Log.w(TAG, "could not read $raw", e)
                ToolResult.Failed(e.message ?: "could not read that page")
            } finally {
                runCatching { connection.disconnect() }
            }
        }

    /**
     * HTML down to something worth paying for.
     *
     * Script and style blocks go first and go entirely — on a modern page they
     * are most of the bytes and none of the meaning, and sending them would
     * mean charging somebody to read minified JavaScript. What is left is
     * unwrapped: tags removed, entities decoded, blank lines collapsed.
     *
     * Deliberately not a real HTML parser. One would be more correct and much
     * larger, and the thing being produced is a paragraph for a model to read
     * rather than a document tree to walk.
     */
    private fun asReadableText(html: String): String {
        if (!html.contains('<')) return html.trim()

        return html
            .replace(Regex("(?is)<script\\b.*?</script>"), " ")
            .replace(Regex("(?is)<style\\b.*?</style>"), " ")
            .replace(Regex("(?is)<noscript\\b.*?</noscript>"), " ")
            .replace(Regex("(?is)<!--.*?-->"), " ")
            // Block ends become line breaks, so paragraphs survive as paragraphs
            // rather than running into one wall of text.
            .replace(Regex("(?i)</(p|div|li|tr|h[1-6]|section|article)>"), "\n")
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?s)<[^>]+>"), " ")
            .let(::decodeEntities)
            .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private fun decodeEntities(text: String) = text
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")

    /** Read no more than this, and stop reading rather than trim afterwards. */
    private fun java.io.BufferedReader.readText(limit: Int): String {
        val buffer = CharArray(8 * 1024)
        val out = StringBuilder()
        while (out.length < limit) {
            val n = read(buffer, 0, minOf(buffer.size, limit - out.length))
            if (n <= 0) break
            out.appendRange(buffer, 0, n)
        }
        return out.toString()
    }

    private const val TIMEOUT_MS = 15_000
    private const val MAX_BYTES = 2 * 1024 * 1024
    private const val MAX_CHARS = 30_000
    private const val USER_AGENT = "Warp/0.1 (Android; +https://github.com/GershonEly/Warp)"
}
