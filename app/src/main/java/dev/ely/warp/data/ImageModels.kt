package dev.ely.warp.data

import android.content.Context

/**
 * Who draws the icons — §8.
 *
 * Its own setting rather than the chat model, because they are different jobs:
 * the model you talk to usually cannot draw at all, and a model built to draw is
 * poor company in a conversation. §5o is what happens when one choice is made to
 * stand for two.
 *
 * **An icon costs real money** — four to fifteen cents depending on the choice,
 * on the account holder's own key. That is a thousand times more than a message,
 * and it is why the price travels with the name everywhere this list is shown.
 */
object ImageModels {

    private const val PREFS = "warp.images"
    private const val KEY = "model"

    /**
     * One option in the picker.
     *
     * @param centsEach what one image costs, to the nearest sensible fraction.
     *   Approximate on purpose: the catalogue prices image output **per token**
     *   and an image is roughly 1290 of them, so an exact figure would be a
     *   precision nobody can act on. Four cents against fifteen is the decision.
     */
    data class Choice(
        val id: String,
        val name: String,
        val centsEach: Double,
        val note: String,
    )

    /**
     * Best first, and the screen says so.
     *
     * **The order is the makers' own tiering** — Pro above Flash, the newer
     * generation above the older, the full model above its Mini. No catalogue
     * publishes a quality score, so this is Google's and OpenAI's ranking of
     * their own range rather than a measurement anyone here made. Price agrees
     * with it top to bottom, which is the nearest thing to corroboration
     * available without sitting down and comparing pictures.
     */
    val all = listOf(
        Choice(
            "google/gemini-3-pro-image", "Nano Banana Pro", 15.0,
            "The best of them, and the dearest.",
        ),
        Choice(
            "google/gemini-3.1-flash-image", "Nano Banana 2", 7.7,
            "Newer than the default, twice the price.",
        ),
        Choice(
            "openai/gpt-5-image", "GPT-5 Image", 5.0,
            "OpenAI's, if you prefer its look.",
        ),
        Choice(
            "google/gemini-2.5-flash-image", "Nano Banana", 3.9,
            "The default. Made for this, and cheap enough not to think about.",
        ),
        Choice(
            "openai/gpt-5-image-mini", "GPT-5 Image Mini", 1.0,
            "The cheapest that still draws something usable.",
        ),
    )

    /**
     * Not the best one.
     *
     * Fifteen cents against four, for artwork that is 192 pixels at its largest
     * on a phone. Somebody who wants the best can pick it in two taps; a default
     * that quietly costs four times more is a decision made on their behalf with
     * their money.
     */
    val default = all.first { it.id == "google/gemini-2.5-flash-image" }

    fun chosen(context: Context): Choice {
        val id = prefs(context).getString(KEY, null)
        // An id that is no longer offered falls back rather than failing. Models
        // are withdrawn, and a stored choice should not be able to break the
        // screen that chose it.
        return (all.firstOrNull { it.id == id } ?: default).also { lastKnown = it }
    }

    fun set(context: Context, id: String) {
        prefs(context).edit().putString(KEY, id).apply()
        all.firstOrNull { it.id == id }?.let { lastKnown = it }
    }

    /**
     * The choice, for the one caller that cannot ask for it.
     *
     * A tool describes itself for the permission card from its arguments alone —
     * no `Context` reaches that far — and the price belongs on that card more
     * than anywhere else, because the card is where the money is agreed to.
     *
     * Kept in step by [chosen] and [set], and **warmed at startup** so it is
     * never merely the default because nothing has looked yet. If it were ever
     * stale the wrong thing shown is a price, on a card that is about to be
     * shown a real one — so the failure is a slightly wrong estimate, not a
     * wrong charge.
     */
    @Volatile
    private var lastKnown: Choice = default

    val lastChosen: Choice get() = lastKnown

    /** Called once when the app starts, so [lastChosen] is true before it is read. */
    fun warm(context: Context) {
        chosen(context)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
