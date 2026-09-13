package dev.ely.warp.git

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The one thing git cannot do for itself — §5n.
 *
 * Pushing needs a repository to push *to*, and creating one is GitHub's API
 * rather than git. It is here, alone, because it is the only part of this
 * feature that is about GitHub specifically rather than about git.
 *
 * **Only ever called when somebody asks for a new repository.** Warp does not
 * create one because it seemed helpful: an app might belong in a repository that
 * already exists, under another account, or inside somebody else's project. His
 * reasoning, and it is right.
 *
 * Hand-rolled over `HttpURLConnection` for the same reason the key vault is:
 * it is thirty lines that can be read end to end, against a dependency for one
 * POST.
 */
object GitHub {

    private const val ENDPOINT = "https://api.github.com/user/repos"

    data class Created(val fullName: String, val cloneUrl: String, val htmlUrl: String)

    /**
     * Make a repository on the account the token belongs to.
     *
     * @param private asked of the person rather than defaulted, because "public"
     *   is a decision that cannot be quietly walked back once anything is in it.
     */
    fun createRepo(token: String, name: String, private: Boolean): Created {
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Content-Type", "application/json")
            // GitHub asks for one and blocks requests without it.
            setRequestProperty("User-Agent", "Warp")
        }

        val body = JSONObject()
            .put("name", name)
            .put("private", private)
            .put("auto_init", false)
            .toString()

        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

        val code = connection.responseCode
        val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()

        if (code !in 200..299) {
            // GitHub's own sentence, because the useful ones are specific: a
            // name already taken, a token without the `repo` scope, a token that
            // has expired. "Could not create the repository" is none of those.
            val said = runCatching { JSONObject(text).optString("message") }.getOrNull()
            throw IllegalStateException(
                when (code) {
                    401 -> "GitHub rejected the token. Check it in Settings."
                    403 -> "That token is not allowed to create repositories — " +
                        "it needs the `repo` scope."
                    422 -> said?.ifBlank { null }
                        ?: "GitHub refused that name — you may already have one called this."
                    else -> said?.ifBlank { null } ?: "GitHub answered $code"
                }
            )
        }

        val json = JSONObject(text)
        return Created(
            fullName = json.optString("full_name"),
            cloneUrl = json.optString("clone_url"),
            htmlUrl = json.optString("html_url"),
        )
    }
}
