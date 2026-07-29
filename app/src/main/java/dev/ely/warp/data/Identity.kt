package dev.ely.warp.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Who is using Warp.
 *
 * One place, because a name that appears on two screens must not be able to
 * disagree with itself — and because the name was briefly a string literal in
 * the drawer, which greets everyone who sideloads Warp as its author.
 *
 * Local for now. Google Sign-In is a settled decision in the plan and will fill
 * exactly this field, along with a real picture; nothing above this file needs
 * to change when it does, which is most of the reason the field exists at all
 * rather than each screen reading a preference for itself.
 *
 * **A flow, and one shared instance, and both are the fix for a real bug.** The
 * first version read the preference once inside `remember`, which cached it: you
 * typed your name in Settings, went back, and the avatar still showed no name,
 * because nothing had told it to look again. Preferences are not observable, so
 * the observable part has to live here — and it only works if every screen holds
 * the *same* Identity, which is why the constructor is private.
 */
class Identity private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("warp_identity", Context.MODE_PRIVATE)

    private val _name = MutableStateFlow(read())

    /**
     * What to call you, or null if you have not said.
     *
     * **Null is a real answer, not a missing one.** Warp does not invent a name
     * and does not fall back to "User" or "You": a greeting that calls you
     * something you never chose is worse than a greeting with no name in it.
     * Every reader has to handle null, and that is deliberate.
     */
    val name: StateFlow<String?> = _name.asStateFlow()

    fun setName(value: String?) {
        val clean = value?.trim()?.replace("\n", "")?.take(MAX)?.takeIf { it.isNotEmpty() }
        prefs.edit().apply {
            if (clean == null) remove(KEY_NAME) else putString(KEY_NAME, clean)
        }.apply()
        _name.value = clean
    }

    private fun read(): String? =
        prefs.getString(KEY_NAME, null)?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        /** A greeting is not a place for a paragraph. */
        const val MAX = 40

        /**
         * The letter for an avatar.
         *
         * An extension on the name rather than a second flow. The first attempt
         * derived it into its own StateFlow built from the name's current value,
         * which reproduced the bug this class exists to fix: it was computed once
         * and never heard about a change. One source of truth, read where it is
         * needed.
         *
         * Null when there is no name, and the caller must not substitute a letter
         * of its own — a circle holding "U" for "User" is a worse answer than a
         * circle holding something honest.
         */
        fun initialOf(name: String?): String? = name?.firstOrNull()?.uppercase()

        private const val KEY_NAME = "name"

        @Volatile
        private var instance: Identity? = null

        /**
         * The one instance for the process.
         *
         * Shared rather than constructed per screen, because the whole point is
         * that Settings changing the name updates the drawer — and two Identity
         * objects would each hold their own flow and never hear about the other.
         */
        fun get(context: Context): Identity =
            instance ?: synchronized(this) {
                instance ?: Identity(context).also { instance = it }
            }
    }
}
