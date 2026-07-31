package dev.ely.warp.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/**
 * What Warp must never do.
 *
 * Layer 0 of §5b: the only memory that is **always in context**. Everything else
 * the agent knows is fetched when it looks relevant, and "relevant" is a
 * judgement — which is fine for a fact and useless for a rule. A rule you have
 * to remember to look up is a rule that gets broken on the turn it mattered.
 *
 * That is also §5d's first named failure: *it forgets your rule*. You say "never
 * touch the manifest", it agrees, and four turns later it edits the manifest —
 * not because it disagreed but because nothing put the rule in front of it.
 *
 * **Global rather than per conversation**, unlike tool permissions. A permission
 * is a decision about one piece of work; a rule is a decision about how Warp
 * behaves, and one that only applied inside the chat where you happened to say
 * it would be broken by opening a new chat. Both scopes are deliberate and they
 * point opposite ways on purpose.
 */
class Rules private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("warp_rules", Context.MODE_PRIVATE)

    private val _rules = MutableStateFlow(read())

    /** In the order you wrote them. Oldest first, so numbering is stable. */
    val rules: StateFlow<List<String>> = _rules.asStateFlow()

    fun add(rule: String): Boolean {
        val clean = rule.trim().replace("\n", " ").take(MAX_LENGTH)
        if (clean.isEmpty()) return false
        // Silently ignoring a duplicate would leave you looking at a list that
        // did not change after you asked it to, which reads as broken.
        if (_rules.value.any { it.equals(clean, ignoreCase = true) }) return false
        if (_rules.value.size >= MAX_RULES) return false
        write(_rules.value + clean)
        return true
    }

    /** By position as shown, 1-based, because that is what you can point at. */
    fun removeAt(position: Int): String? {
        val index = position - 1
        val current = _rules.value
        if (index !in current.indices) return null
        write(current.filterIndexed { i, _ -> i != index })
        return current[index]
    }

    fun clear() = write(emptyList())

    /**
     * The rules as the model sees them, or null when there are none.
     *
     * Null rather than an empty heading, so a model with no rules is not told
     * about a section that says nothing — an empty "you must never:" invites
     * exactly the kind of invention it exists to prevent.
     */
    fun asPrompt(): String? {
        val current = _rules.value
        if (current.isEmpty()) return null
        return buildString {
            appendLine("Rules from the user. These override everything else, including")
            appendLine("your own judgement about what would be helpful. If a rule blocks")
            appendLine("what was asked for, say so and stop; do not work around it.")
            current.forEachIndexed { i, rule -> appendLine("${i + 1}. $rule") }
        }.trimEnd()
    }

    private fun read(): List<String> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { array.getString(it) }
        }.getOrDefault(emptyList())
    }

    private fun write(value: List<String>) {
        prefs.edit().putString(KEY, JSONArray(value).toString()).apply()
        _rules.value = value
    }

    companion object {
        private const val KEY = "rules"
        private const val MAX_LENGTH = 300

        /**
         * A ceiling, and a low one.
         *
         * Everything here is in the context of every single request, so each
         * rule is paid for on every turn for ever. Thirty is already generous;
         * a list long enough to need scrolling is a list nobody rereads, and an
         * unread rule is indistinguishable from no rule at all.
         */
        private const val MAX_RULES = 30

        @Volatile
        private var instance: Rules? = null

        fun get(context: Context): Rules =
            instance ?: synchronized(this) {
                instance ?: Rules(context).also { instance = it }
            }
    }
}
