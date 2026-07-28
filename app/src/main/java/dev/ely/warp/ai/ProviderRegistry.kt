package dev.ely.warp.ai

import android.content.Context

/**
 * Which AI Warp is talking to, and which model.
 *
 * The chat never touches this beyond asking for the current provider — that is
 * the point of the harness. Swapping from the mock to Claude is a change here,
 * not in any screen.
 */
class ProviderRegistry(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Every provider Warp knows about.
     *
     * The mock comes first deliberately: with no key, it is the only one that
     * works, and it should be what a fresh install lands on.
     */
    val providers: List<AiProvider> = listOf(
        MockProvider(),
        AnthropicProvider(context),
    )

    /** The provider in use. Falls back to the mock if the stored id is stale. */
    var selected: AiProvider
        get() {
            val id = prefs.getString(KEY_PROVIDER, null)
            return providers.firstOrNull { it.id == id } ?: providers.first()
        }
        set(value) {
            prefs.edit().putString(KEY_PROVIDER, value.id).apply()
        }

    /** The chosen model for [provider], or a sensible default. */
    fun modelFor(provider: AiProvider): String =
        prefs.getString(modelKey(provider), null) ?: defaultModel(provider)

    fun setModelFor(provider: AiProvider, model: String) {
        prefs.edit().putString(modelKey(provider), model).apply()
    }

    var effort: Effort
        get() = runCatching {
            Effort.valueOf(prefs.getString(KEY_EFFORT, null) ?: Effort.LOW.name)
        }.getOrDefault(Effort.LOW)
        set(value) {
            prefs.edit().putString(KEY_EFFORT, value.name).apply()
        }

    fun hasKey(provider: AiProvider): Boolean =
        !provider.requiresKey || KeyVault.hasKey(context, provider.id)

    /** True when the selected provider can actually be used right now. */
    val ready: Boolean get() = hasKey(selected)

    private fun modelKey(provider: AiProvider) = "$KEY_MODEL_PREFIX${provider.id}"

    private fun defaultModel(provider: AiProvider) = when (provider.id) {
        // Claude Opus 5 — the current flagship. Users can pick another in
        // Settings once their key loads the live model list.
        "anthropic" -> "claude-opus-5"
        else -> "mock-fast"
    }

    private companion object {
        const val PREFS = "warp_ai"
        const val KEY_PROVIDER = "provider"
        const val KEY_EFFORT = "effort"
        const val KEY_MODEL_PREFIX = "model_"
    }
}
