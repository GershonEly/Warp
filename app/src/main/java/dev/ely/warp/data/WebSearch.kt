package dev.ely.warp.data

import android.content.Context

/**
 * Whether the model may search the web — task M, second half.
 *
 * `fetch_url` reads a page somebody names and costs nothing. Searching is
 * different: the model decides when to do it, and each one is charged. On
 * OpenRouter that is about **half a cent per search** billed to the same key
 * that pays for the conversation — no second account, no separate API key.
 *
 * **Off by default, and that is deliberate.** Warp is BYOK, so the balance is
 * the account holder's, and *"it only costs a little"* is a judgement only they
 * get to make. The same reasoning already governs the AI naming setting.
 */
object WebSearch {

    private const val PREFS = "warp.web"
    private const val KEY = "search"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * **On by default — changed 2026-09-26, and the old reasoning is kept.**
     *
     * It shipped off. The argument was that Warp is BYOK, that each search is
     * about half a cent on the account holder's own balance, and that *"it only
     * costs a little"* is a judgement only they get to make.
     *
     * He overruled it, which is his to do — it is his key. What decided it was
     * not the money but the friction: the switch had to be found and turned on
     * again and again, and a model that cannot check a fact it is unsure of is
     * worth less than half a cent.
     *
     * The switch stays, the prompt line still tells the model the truth either
     * way, and [promptLine] still tells it not to search for things it knows.
     */
    fun isOn(context: Context): Boolean = prefs(context).getBoolean(KEY, true)

    fun set(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY, on).apply()
    }

    /**
     * What the model is told about its own reach, every turn.
     *
     * The off case is the whole point of this function. Before it, a model
     * asked to look something up said *"I have no internet access"* — true,
     * unhelpful, and unactionable, and it was said three times in one session
     * while the person kept asking. Naming the switch turns a dead end into
     * something the person can do in ten seconds.
     *
     * Composed fresh each turn, like the rules, so flipping the switch applies
     * to the very next message rather than the next launch.
     *
     * @param canSearch false where the provider has no search at all, so the
     *   sentence does not offer a setting that would change nothing.
     */
    fun promptLine(context: Context, canSearch: Boolean): String = when {
        !canSearch ->
            "You cannot search the web on this provider. You can read a page if " +
                "the user gives you its address — use fetch_url. If you need " +
                "something you cannot reach, say what you would look up and ask " +
                "for a link."

        isOn(context) ->
            "You can search the web. Do it when a fact matters and you are not " +
                "sure of it, and say what you found rather than only that you " +
                "looked. Each search costs the user money, so do not search for " +
                "things you already know."

        else ->
            "Web search is available but switched OFF in this user's settings. " +
                "You can still read a page they give you the address of, with " +
                "fetch_url. When you would have searched, say so plainly — that " +
                "searching is off in Settings and they can turn it on — rather " +
                "than saying you have no internet access. Then answer as best " +
                "you can and be clear about what you are unsure of."
    }
}
