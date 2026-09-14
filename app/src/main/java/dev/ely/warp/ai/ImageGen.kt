package dev.ely.warp.ai

import android.content.Context
import android.util.Base64
import dev.ely.warp.ai.ProviderHttp.errorBody
import dev.ely.warp.ai.ProviderHttp.writeJson
import dev.ely.warp.data.ImageModels
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Ask a model for a picture — §8.
 *
 * The image endpoint rather than a chat turn, and the difference is not
 * cosmetic. A chat call returns an image as an afterthought: no way to demand a
 * square, no quality control, and **a turn that half-fails is still billed**. The
 * images endpoint takes `aspect_ratio` and bills all or nothing, which for four
 * to fifteen cents a go is the whole argument.
 *
 * Deliberately separate from [AiProvider]: providers stream text, carry
 * conversation history and handle tools, none of which applies to one request for
 * one picture. Pretending this is a chat would mean pretending a picture is a
 * message.
 */
object ImageGen {

    /** The images endpoint lives under the same key the conversation uses. */
    private const val PROVIDER = "openrouter"
    private const val URL_IMAGES = "https://openrouter.ai/api/v1/images/generations"

    /** What `BitmapFactory` can actually open. Everything else is a dead end. */
    private val BITMAPS = setOf("image/png", "image/jpeg", "image/webp")

    sealed interface Result {
        /**
         * @param costUsd what the provider said it charged, or null when it did
         *   not say. Null is shown as the catalogue estimate with a "about",
         *   never as free — see §5o on invented numbers.
         */
        data class Drawn(val png: ByteArray, val costUsd: Double?) : Result

        data class Failed(val message: String) : Result
    }

    /**
     * One square picture.
     *
     * One, not three. The launcher masks the icon to a circle, a squircle or a
     * rounded square depending on the phone, so offering three *shapes* would be
     * offering to pay three times for something the system does for free. Three
     * different *drawings* is a real thing to want, and it is a second tap, made
     * deliberately rather than by default.
     *
     * Blocking — callers are already off the main thread, as everything that
     * touches the network here is.
     */
    fun draw(context: Context, prompt: String): Result {
        val key = KeyVault.load(context, PROVIDER)?.takeIf { it.isNotBlank() }
            ?: return Result.Failed(
                "No OpenRouter key. Settings → Providers, and paste a key there."
            )
        val model = ImageModels.chosen(context)

        return runCatching {
            val connection = ProviderHttp.open(
                URL_IMAGES, "POST",
                mapOf("Authorization" to "Bearer $key"),
            )
            connection.writeJson(
                JSONObject()
                    .put("model", model.id)
                    .put("prompt", prompt)
                    // Square because a launcher icon is square. Asking for it is
                    // cheaper than cropping a landscape picture afterwards and
                    // losing whatever was at the edges.
                    //
                    // The only optional parameter sent, and deliberately. The
                    // catalogue normalises `aspect_ratio` across every provider;
                    // `n` it does not — how many images a model will draw per
                    // call is the model's own business, one to ten — so asking
                    // for one by name is a request some models would reject for
                    // a request they were going to honour anyway.
                    .put("aspect_ratio", "1:1")
            )

            if (connection.responseCode !in 200..299) {
                return@runCatching Result.Failed(
                    readableError(connection.responseCode, connection.errorBody(), model)
                )
            }

            val body = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })

            // Said before the bytes are looked at, because the failure it
            // catches is specific and the generic one is not: a vector model
            // returns `image/svg+xml`, which decodes to nothing, and "that is
            // not a picture" would be both true and useless. Android needs a
            // VectorDrawable, not an SVG, so this is a real dead end rather
            // than a bug to fix.
            val type = body.optJSONArray("data")?.optJSONObject(0)?.optString("media_type")
            if (!type.isNullOrBlank() && type !in BITMAPS) {
                return@runCatching Result.Failed(
                    "${model.name} returned $type, which Android cannot use as " +
                        "an icon. Settings → Icons, and pick one that draws PNGs."
                )
            }

            val png = pngFrom(body)
                ?: return@runCatching Result.Failed(
                    "The model answered, but with no picture in it. " +
                        "Keys in the reply: ${body.keys().asSequence().joinToString()}"
                )
            Result.Drawn(png, costOf(body))
        }.getOrElse { Result.Failed(it.message ?: "The request failed.") }
    }

    /**
     * Find the picture, whichever way it was sent.
     *
     * Three shapes are accepted because three are in use: OpenAI returns
     * `data[].b64_json`, some resold models return `data[].url`, and models
     * reached through the chat-shaped path return a `data:` URI under
     * `images[].image_url.url`. Matching only the documented one would break the
     * first time a model was swapped in Settings, and the person swapping it
     * would have no way to know why.
     */
    private fun pngFrom(body: JSONObject): ByteArray? {
        val data = body.optJSONArray("data")?.optJSONObject(0)
        data?.optString("b64_json")?.takeIf { it.isNotBlank() }?.let { return decode(it) }
        data?.optString("url")?.takeIf { it.isNotBlank() }?.let { return fetch(it) }

        val chatShaped = body.optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optJSONArray("images")?.optJSONObject(0)
            ?.optJSONObject("image_url")?.optString("url")
        return chatShaped?.takeIf { it.isNotBlank() }?.let { fetch(it) }
    }

    /** A `data:` URI carries the bytes; anything else has to be gone and got. */
    private fun fetch(url: String): ByteArray? {
        if (url.startsWith("data:")) return decode(url.substringAfter(",", ""))
        return runCatching {
            (URL(url).openConnection() as HttpURLConnection).run {
                connectTimeout = 30_000
                readTimeout = 120_000
                inputStream.use { stream ->
                    ByteArrayOutputStream().also { stream.copyTo(it) }.toByteArray()
                }
            }
        }.getOrNull()
    }

    private fun decode(base64: String): ByteArray? =
        runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrNull()

    /**
     * What it actually cost, when the provider says.
     *
     * OpenRouter reports the real charge on the response, which beats any
     * estimate this app could make — the catalogue prices per token and only the
     * provider knows how many an image came to. The estimate is the fallback,
     * not the headline.
     */
    private fun costOf(body: JSONObject): Double? =
        body.optJSONObject("usage")?.let { usage ->
            usage.optDouble("cost", Double.NaN).takeIf { !it.isNaN() }
                ?: usage.optDouble("total_cost", Double.NaN).takeIf { !it.isNaN() }
        }

    /**
     * Say which thing went wrong, and what to do about it.
     *
     * §5o's third finding was a message that blamed the key for a busy provider.
     * Every case here names the actual cause; the model's name is in the two
     * where the fix is to pick a different one.
     */
    private fun readableError(code: Int, body: String, model: ImageModels.Choice): String {
        val detail = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message")
        }.getOrNull().orEmpty()

        return when (code) {
            401 -> "OpenRouter rejected the key. Settings → Providers."
            402 -> "Out of credit. An icon costs about ${cents(model)}, " +
                "and the account has less than that."
            403 -> "The account is not allowed to use ${model.name}." +
                detail.ifBlank { "" }.let { if (it.isBlank()) "" else " $it" }
            404 -> "${model.name} is not available any more. " +
                "Settings → Icons, and pick another."
            429 -> "${model.name} is busy. Waiting a moment usually fixes it — " +
                "nothing was charged."
            in 500..599 -> "The provider had a problem. Nothing was charged."
            else -> detail.ifBlank { "The request failed with code $code." }
        }
    }

    /**
     * "4¢", from the catalogue. Only ever shown as an estimate.
     *
     * Rounded to whole pennies above one, because the fraction is noise at that
     * size and a price with a decimal point in it reads as more exact than this
     * number is.
     */
    fun cents(model: ImageModels.Choice): String {
        val rounded = Math.round(model.centsEach).toInt()
        return if (rounded < 1) "under 1¢" else "$rounded¢"
    }
}
