package dev.ely.warp.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How the app looks, where that is a choice rather than a rule.
 *
 * One shared instance and a flow, for the same reason [Identity] has both: a
 * switch in Settings has to change the screen behind it, and preferences are not
 * observable.
 */
class Appearance private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("warp_appearance", Context.MODE_PRIVATE)

    private val _ambient = MutableStateFlow(prefs.getBoolean(KEY_AMBIENT, true))

    /**
     * Whether the screen carries an ambient wash at all.
     *
     * **On by default**, unlike the naming setting — and the difference is worth
     * stating. Naming spends the person's money, so it asks first. This spends
     * nothing and can be seen the moment the app opens, which makes it the kind
     * of default someone can disagree with *by looking at it* rather than by
     * reading a bill.
     *
     * It exists as a switch at all because atmosphere is the one thing in this
     * design that could reasonably annoy somebody, and a thing that cannot be
     * turned off had better be one nobody wants to turn off.
     */
    val ambient: StateFlow<Boolean> = _ambient.asStateFlow()

    fun setAmbient(on: Boolean) {
        prefs.edit().putBoolean(KEY_AMBIENT, on).apply()
        _ambient.value = on
    }

    companion object {
        private const val KEY_AMBIENT = "ambient_glow"

        @Volatile
        private var instance: Appearance? = null

        fun get(context: Context): Appearance =
            instance ?: synchronized(this) {
                instance ?: Appearance(context).also { instance = it }
            }
    }
}
