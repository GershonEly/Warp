package dev.ely.warp.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Every AI Warp can reach, and which model is in use.
 *
 * The chat asks this for a provider and a model; it never learns which company
 * is behind them. That indirection is the whole point of the harness — adding a
 * provider is a new file here, not a change to any screen.
 */
class ProviderRegistry(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * The mock comes first deliberately: with no key it is the only one that
     * works, and it should be what a fresh install lands on.
     */
    val providers: List<AiProvider> = listOf(
        MockProvider(),
        AnthropicProvider(context),
        GoogleProvider(context),
        OpenAiProvider(context),
        OpenRouterProvider(context),
    )

    fun providerFor(id: String): AiProvider =
        providers.firstOrNull { it.id == id } ?: providers.first()

    fun hasKey(provider: AiProvider): Boolean =
        !provider.requiresKey || KeyVault.hasKey(context, provider.id)

    // ── the current choice ───────────────────────────────────────────────

    /**
     * The selected model, as provider + model + effort in one value.
     *
     * Stored as three fields rather than a serialised object so a future change
     * to [ModelChoice] cannot make saved settings unreadable.
     */
    var choice: ModelChoice
        get() {
            val providerId = prefs.getString(KEY_PROVIDER, null)
            val modelId = prefs.getString(KEY_MODEL, null)
            val effort = prefs.getString(KEY_EFFORT, null)
                ?.let { name -> runCatching { Effort.valueOf(name) }.getOrNull() }

            if (providerId == null || modelId == null) return defaultChoice
            val provider = providers.firstOrNull { it.id == providerId } ?: return defaultChoice

            return ModelChoice(
                providerId = provider.id,
                modelId = modelId,
                modelName = prefs.getString(KEY_MODEL_NAME, null) ?: modelId,
                effort = effort,
                badge = null,
            )
        }
        set(value) {
            prefs.edit()
                .putString(KEY_PROVIDER, value.providerId)
                .putString(KEY_MODEL, value.modelId)
                .putString(KEY_MODEL_NAME, value.modelName)
                .putString(KEY_EFFORT, value.effort?.name)
                .apply()
        }

    private val defaultChoice: ModelChoice
        get() = ModelChoice(
            providerId = "mock",
            modelId = "mock-fast",
            modelName = "Mock AI",
            effort = null,
            badge = null,
        )

    /** The provider behind the current choice. */
    val selected: AiProvider get() = providerFor(choice.providerId)

    /** True when the current choice can actually be used. */
    val ready: Boolean get() = hasKey(selected)

    // ── building the picker list ─────────────────────────────────────────

    /**
     * Every model from every provider, in one list.
     *
     * Providers without a key still appear — as a single greyed-out row saying
     * so — because a picker that hides them gives the user no way to discover
     * what Warp supports. Their real model names cannot be listed: fetching the
     * list is itself an authenticated call, and inventing names would mean
     * showing models that may not exist.
     *
     * Fetches run in parallel; one provider being slow or down must not hold up
     * the rest, so a failure becomes an unavailable row rather than an error.
     */
    suspend fun modelChoices(): List<ModelChoice> = withContext(Dispatchers.IO) {
        coroutineScope {
            providers.map { provider ->
                async { choicesFor(provider) }
            }.flatMap { it.await() }
        }
    }

    private suspend fun choicesFor(provider: AiProvider): List<ModelChoice> {
        val usable = hasKey(provider) || !provider.requiresKey

        // OpenRouter publishes its catalogue without a key, so it can be shown
        // even before one is added — the models are real, just not yet usable.
        val canList = usable || provider.id == "openrouter"
        if (!canList) return listOf(needsKeyRow(provider))

        val models = provider.listModels().getOrElse { error ->
            Log.w(TAG, "could not list models for ${provider.id}: ${error.message}")
            return listOf(needsKeyRow(provider, "unavailable"))
        }

        if (models.isEmpty()) return listOf(needsKeyRow(provider, "no models"))

        return models.flatMap { model ->
            val levels = if (provider.supportsEffort) EFFORT_LEVELS else listOf(null)
            levels.map { effort ->
                ModelChoice(
                    providerId = provider.id,
                    modelId = model.id,
                    modelName = model.displayName,
                    effort = effort,
                    badge = model.badge,
                    available = usable,
                )
            }
        }
    }

    private fun needsKeyRow(provider: AiProvider, reason: String = "needs a key") = ModelChoice(
        providerId = provider.id,
        modelId = "",
        modelName = "${provider.displayName} — $reason",
        effort = null,
        badge = null,
        available = false,
    )

    private companion object {
        const val TAG = "WarpRegistry"
        const val PREFS = "warp_ai"
        const val KEY_PROVIDER = "provider"
        const val KEY_MODEL = "model"
        const val KEY_MODEL_NAME = "model_name"
        const val KEY_EFFORT = "effort"

        /** Shown per model for providers that support it, as in the design. */
        val EFFORT_LEVELS = listOf(Effort.HIGH, Effort.MEDIUM, Effort.LOW)
    }
}
