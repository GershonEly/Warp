package dev.ely.warp.ai

import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.HttpsURLConnection

/**
 * The HTTP and Server-Sent-Events plumbing every provider needs.
 *
 * Kept in one place so the four providers differ only where the APIs actually
 * differ — request shape and response shape — rather than each carrying its own
 * copy of connection setup and stream parsing.
 *
 * No networking library on purpose: what Warp needs from these APIs is one
 * streaming POST and one GET each, which would cost more in dependency weight
 * than it saves in code.
 */
internal object ProviderHttp {

    /** Long enough that a model pausing mid-answer does not look like a failure. */
    private const val READ_TIMEOUT_MS = 300_000
    private const val CONNECT_TIMEOUT_MS = 30_000

    fun open(
        url: String,
        method: String,
        headers: Map<String, String> = emptyMap(),
    ): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpsURLConnection
        connection.requestMethod = method
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty("Content-Type", "application/json")
        headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        return connection
    }

    fun HttpURLConnection.writeJson(body: JSONObject) {
        doOutput = true
        outputStream.bufferedWriter().use { it.write(body.toString()) }
    }

    fun HttpURLConnection.errorBody(): String =
        runCatching { errorStream?.bufferedReader()?.readText().orEmpty() }.getOrDefault("")

    /**
     * Parse an SSE stream into JSON objects.
     *
     * `data:` lines carry the payload; `event:` lines repeat a type that is
     * already inside the JSON, so they are skipped. `[DONE]` is OpenAI's
     * end-of-stream marker and is not JSON.
     */
    fun sseEvents(reader: BufferedReader): Sequence<JSONObject> = sequence {
        while (true) {
            val line = reader.readLine() ?: break
            if (!line.startsWith("data:")) continue
            val payload = line.removePrefix("data:").trim()
            if (payload.isEmpty() || payload == "[DONE]") continue
            runCatching { JSONObject(payload) }.getOrNull()?.let { yield(it) }
        }
    }

    /**
     * Map an HTTP status to something worth telling the user apart.
     *
     * 401 and 403 both mean "the key was not accepted" from the user's point of
     * view, even though they differ on the wire.
     */
    fun errorFor(code: Int, body: String): AiError {
        val message = extractMessage(body)
        return when (code) {
            401, 403 -> AiError.BadKey
            429 -> AiError.RateLimited
            in 500..599 -> AiError.Server(message.ifBlank { "HTTP $code" })
            else -> AiError.Unknown(message.ifBlank { "HTTP $code" })
        }
    }

    /**
     * Pull a human-readable message out of an error body.
     *
     * Every provider nests it slightly differently, so try each shape rather
     * than showing the user raw JSON.
     */
    private fun extractMessage(body: String): String = runCatching {
        val json = JSONObject(body)
        json.optJSONObject("error")?.optString("message")?.takeIf { it.isNotEmpty() }
            ?: json.optString("message").takeIf { it.isNotEmpty() }
            ?: ""
    }.getOrDefault("")

    fun asAiError(e: Throwable): AiError = when (e) {
        is AiException -> e.error
        // No DNS and no route both surface as IOException; to a user on a phone
        // they are the same thing.
        is UnknownHostException -> AiError.Offline
        is IOException -> AiError.Offline
        else -> AiError.Unknown("${e.javaClass.simpleName}: ${e.message}")
    }
}

/**
 * A string field, or null — never the four characters `"null"`.
 *
 * `JSONObject.optString` returns the **literal string "null"** when the value is
 * JSON null, because it stringifies the null sentinel. Every OpenAI-style
 * streaming chunk that carries a tool call sends `"content": null`, so Warp
 * appended "null" to the reply text once per tool call. A conversation where the
 * model used four tools read: `nullnullnullnull`.
 *
 * It was found by the user looking at their own screen, not by any test — and
 * the code already guarded exactly one field (`finish_reason`) with
 * `it != "null"`, which means this was hit before, patched where it hurt, and
 * never recognised as general.
 */
internal fun org.json.JSONObject.textOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
