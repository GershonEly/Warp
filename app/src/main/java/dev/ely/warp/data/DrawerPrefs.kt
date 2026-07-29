package dev.ely.warp.data

import android.content.Context

/**
 * What the drawer remembers between visits.
 *
 * Not in the database, because none of it is data — it is where you had things
 * folded when you last looked. Putting it in Room would mean a migration every
 * time the drawer grew a new piece of state, to protect information nobody would
 * mind losing.
 */
class DrawerPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("warp_drawer", Context.MODE_PRIVATE)

    /**
     * Folders the person has folded shut.
     *
     * Stored as the set of *collapsed* ids rather than expanded ones, so a
     * folder created after this was last written starts open. A new folder you
     * just made and cannot see is a bug report waiting to happen, and the
     * inverse — a new folder that starts open — costs one tap.
     */
    var collapsed: Set<String>
        get() = prefs.getStringSet(KEY_COLLAPSED, emptySet()).orEmpty()
        set(value) {
            // A copy, because SharedPreferences keeps the very set it was given
            // and mutating it later changes what is stored without a write.
            prefs.edit().putStringSet(KEY_COLLAPSED, value.toSet()).apply()
        }

    fun toggle(folderId: String) {
        collapsed = if (folderId in collapsed) collapsed - folderId else collapsed + folderId
    }

    private companion object {
        const val KEY_COLLAPSED = "collapsed_folders"
    }
}
