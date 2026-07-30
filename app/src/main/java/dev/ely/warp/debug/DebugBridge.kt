package dev.ely.warp.debug

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The seam between the running app and whatever is driving it.
 *
 * This lives in the main source set and the server does not, which is the whole
 * arrangement: release builds compile a bridge that nobody ever calls, and never
 * compile anything that could answer a socket. A flag can be flipped by mistake;
 * a class that is not in the build cannot be.
 *
 * The UI registers itself here while it is on screen. Everything is nullable
 * because the app spends its first moments with no screen at all, and a debug
 * tool that crashes the app it is inspecting is worse than no debug tool.
 */
object DebugBridge {

    /** What the app looks like right now. */
    @Volatile
    var state: (() -> Map<String, Any?>)? = null

    /** Send a message as though typed. Returns the id it was stored under. */
    @Volatile
    var send: ((String) -> String?)? = null

    /** Go to a destination by name. False if there is no such place. */
    @Volatile
    var navigate: ((String) -> Boolean)? = null

    /** Open a conversation by id. False if it does not exist. */
    @Volatile
    var open: ((String) -> Boolean)? = null

    /** Start a fresh conversation. */
    @Volatile
    var newChat: (() -> Unit)? = null

    /** Set one setting by name. Returns what it reads back as, or null if unknown. */
    @Volatile
    var setting: ((String, String) -> String?)? = null

    // ── the key ──────────────────────────────────────────────────────────

    private const val PREFS = "warp_debug"
    private const val KEY = "key"

    private val _key = MutableStateFlow<String?>(null)

    /**
     * The shared secret, or null when the surface is shut.
     *
     * **Empty means off, and off is where a fresh install starts.** There is no
     * default key — not a weak one, not "changeme", none. The only way in is for
     * a person to open Settings on the device and type one, which means the
     * surface cannot be left open by forgetting to close it. It was never open.
     */
    val key: StateFlow<String?> = _key.asStateFlow()

    fun load(context: Context) {
        _key.value = prefs(context).getString(KEY, null)?.takeIf { it.isNotBlank() }
    }

    /**
     * Things that are somebody's API key, not a debug key.
     *
     * The two fields sit on one Settings screen and nothing stopped a paste
     * landing in the wrong one — which happened, first time out. It matters
     * because the two are stored differently on purpose: provider keys go into
     * the Android Keystore, encrypted, and this one is plain SharedPreferences,
     * because it is a local handshake rather than a secret.
     *
     * **A field that cannot protect a secret should refuse to accept one.**
     */
    private val LOOKS_LIKE_A_SECRET = listOf("sk-", "sk-or-", "sk-ant-", "AIza", "gsk_")

    fun looksLikeApiKey(value: String) =
        LOOKS_LIKE_A_SECRET.any { value.trim().startsWith(it) }

    fun setKey(context: Context, value: String?) {
        if (value != null && looksLikeApiKey(value)) return
        val clean = value?.trim()?.takeIf { it.isNotBlank() }
        prefs(context).edit().apply {
            if (clean == null) remove(KEY) else putString(KEY, clean)
        }.apply()
        _key.value = clean
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
