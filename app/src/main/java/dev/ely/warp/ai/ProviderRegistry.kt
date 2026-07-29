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

    // ── who names conversations ──────────────────────────────────────────

    /**
     * Which model, if any, is allowed to name a conversation.
     *
     * Stored as a mode plus an optional model, rather than as a nullable model
     * where null means off. "Off" and "not chosen yet" are different answers and
     * a single nullable field cannot tell them apart — which matters the moment
     * someone picks a model, switches to Off, and comes back expecting their
     * choice to still be there.
     */
    var naming: Naming
        get() = when (prefs.getString(KEY_NAMING_MODE, null)) {
            MODE_AUTOMATIC -> Naming.Automatic
            MODE_MODEL -> {
                val providerId = prefs.getString(KEY_NAMING_PROVIDER, null)
                val modelId = prefs.getString(KEY_NAMING_MODEL, null)
                if (providerId == null || modelId == null) {
                    // The mode said a model and the model is gone. Off is the
                    // only safe reading: guessing one would spend someone's
                    // credits on a choice they did not make.
                    Naming.Off
                } else {
                    Naming.Specific(
                        providerId = providerId,
                        modelId = modelId,
                        modelName = prefs.getString(KEY_NAMING_MODEL_NAME, null) ?: modelId,
                    )
                }
            }
            // Includes null, which is a fresh install. See the plan: Off is the
            // default because the key, and the balance it draws on, belong to
            // the person — and "it only costs a little" is a judgement only the
            // account holder is entitled to make.
            else -> Naming.Off
        }
        set(value) {
            val editor = prefs.edit()
            when (value) {
                Naming.Off -> editor.putString(KEY_NAMING_MODE, MODE_OFF)
                Naming.Automatic -> editor.putString(KEY_NAMING_MODE, MODE_AUTOMATIC)
                is Naming.Specific -> editor
                    .putString(KEY_NAMING_MODE, MODE_MODEL)
                    .putString(KEY_NAMING_PROVIDER, value.providerId)
                    .putString(KEY_NAMING_MODEL, value.modelId)
                    .putString(KEY_NAMING_MODEL_NAME, value.modelName)
            }
            editor.apply()
        }

    /**
     * The provider and model that should name a conversation, or null for none.
     *
     * Returns null for [Naming.Off], and also whenever the answer would be a
     * provider with no key — a naming call that is going to fail is worse than
     * no naming call, because it costs the same latency to find out.
     */
    suspend fun namingModel(): Pair<AiProvider, String>? = when (val mode = naming) {
        Naming.Off -> null

        is Naming.Specific -> providerFor(mode.providerId)
            .takeIf { hasKey(it) }
            ?.let { it to mode.modelId }

        Naming.Automatic -> selected.takeIf { hasKey(it) }?.let { provider ->
            provider to (cheapestModelFor(provider) ?: choice.modelId)
        }
    }

    /**
     * The cheapest model a provider offers, by name.
     *
     * Warp does not hardcode model lists — it asks each provider what it has —
     * so this cannot be a constant. It matches the small-model families each
     * company is known for, and returns null when nothing matches so the caller
     * falls back to the conversation's own model. A provider that names its
     * cheap model something unguessable costs slightly more than it should,
     * which is a far better failure than a crash or a wrong guess.
     *
     * Cached for the process: the answer changes when a provider ships a new
     * model, not between two conversations, and naming must not pay for a model
     * list every time someone starts a chat.
     */
    private suspend fun cheapestModelFor(provider: AiProvider): String? {
        cheapestCache[provider.id]?.let { return it.value }

        val models = provider.listModels().getOrNull().orEmpty()
        val match = CHEAP_MARKERS.firstNotNullOfOrNull { marker ->
            models.firstOrNull { marker in "${it.id} ${it.displayName}".lowercase() }?.id
        }

        cheapestCache[provider.id] = Cached(match)
        return match
    }

    private class Cached(val value: String?)

    private val cheapestCache = mutableMapOf<String, Cached>()

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

        // A provider can offer hundreds of models — OpenRouter resells most of
        // the industry. Showing all of them buries the handful anyone actually
        // wants, so the short list is capped and the rest sit behind "show all".
        val shortlist = models
            .filter { looksMainstream(it.id) }
            .take(SHORTLIST_PER_PROVIDER)
            .map { it.id }
            .toSet()

        return models.flatMap { model ->
            val levels = if (provider.supportsEffort) EFFORT_LEVELS else listOf(null)
            levels.map { effort ->
                ModelChoice(
                    providerId = provider.id,
                    modelId = model.id,
                    modelName = model.displayName,
                    effort = effort,
                    badge = model.badge,
                    // The mock is the no-key default, so it stays visible at
                    // the top rather than hidden behind a folder of its own.
                    group = if (provider.requiresKey) {
                        familyOf(model.id, model.displayName)
                    } else {
                        TOP_LEVEL
                    },
                    available = usable,
                    recommended = model.id in shortlist,
                )
            }
        }
    }

    /**
     * Which folder a model belongs in.
     *
     * Grouped by model family rather than by provider, because OpenRouter
     * resells other companies' models — grouping by provider would scatter
     * Claude across two folders and leave one folder holding everything else.
     *
     * Matched on the family name, never on a version, so a new release lands in
     * the right folder without Warp being updated.
     */
    private fun familyOf(modelId: String, displayName: String): String {
        val text = "$modelId $displayName".lowercase()
        return FAMILIES.firstOrNull { (_, markers) -> markers.any { it in text } }
            ?.first
            ?: "Other"
    }

    /**
     * A rough "is this one of the well-known models" test.
     *
     * Matches model *families* rather than versions, so a new release shows up
     * without Warp needing an update — the opposite mistake to hardcoding a
     * list of exact ids that goes stale within weeks.
     */
    private fun looksMainstream(modelId: String): Boolean {
        val id = modelId.lowercase()
        // Dated snapshots and previews duplicate their own family; the plain
        // alias is the one worth showing.
        if (PREVIEW_MARKERS.any { it in id }) return false
        return MAINSTREAM_FAMILIES.any { it in id }
    }

    private fun needsKeyRow(provider: AiProvider, reason: String = "needs a key") = ModelChoice(
        providerId = provider.id,
        modelId = "",
        modelName = "${provider.displayName} — $reason",
        effort = null,
        badge = null,
        // Locked providers sit at the top level, not buried inside a folder —
        // otherwise the one signal that Warp supports them is a folder deep.
        group = TOP_LEVEL,
        available = false,
    )

    companion object {
        /**
         * Rows shown outside the folders: the mock, and the notice for a
         * provider that still needs a key.
         *
         * Membership is by kind, never by availability — a model the user
         * cannot use yet still belongs in its family's folder.
         */
        const val TOP_LEVEL = "__top__"

        private const val TAG = "WarpRegistry"
        private const val PREFS = "warp_ai"
        private const val KEY_PROVIDER = "provider"
        private const val KEY_MODEL = "model"
        private const val KEY_MODEL_NAME = "model_name"
        private const val KEY_EFFORT = "effort"
        private const val KEY_NAMING_MODE = "naming_mode"
        private const val KEY_NAMING_PROVIDER = "naming_provider"
        private const val KEY_NAMING_MODEL = "naming_model"
        private const val KEY_NAMING_MODEL_NAME = "naming_model_name"

        private const val MODE_OFF = "off"
        private const val MODE_AUTOMATIC = "auto"
        private const val MODE_MODEL = "model"

        /**
         * What a cheap model tends to be called.
         *
         * Families, never versions, for the same reason as everything else in
         * this file: a new small model should be found without Warp shipping an
         * update. Ordered, so the first match wins.
         */
        private val CHEAP_MARKERS = listOf(
            "haiku",   // Anthropic
            "flash",   // Google
            "mini",    // OpenAI
            "nano",
            "small",
            "lite",
        )

        /** Shown per model for providers that support it, as in the design. */
        private val EFFORT_LEVELS = listOf(Effort.HIGH, Effort.MEDIUM, Effort.LOW)

        /** How many models each provider contributes to the short list. */
        private const val SHORTLIST_PER_PROVIDER = 6

        /**
         * Folders, in display order. First match wins, so more specific
         * markers must come before broader ones — "gpt" would otherwise
         * swallow "gpt-oss" style names that belong elsewhere.
         */
        private val FAMILIES: List<Pair<String, List<String>>> = listOf(
            "Claude" to listOf("claude", "opus", "sonnet", "haiku"),
            "Gemini" to listOf("gemini", "gemma"),
            "GPT" to listOf("gpt", "chatgpt", "o1-", "o3-", "o4-"),
            "Grok" to listOf("grok"),
            "Llama" to listOf("llama"),
            "DeepSeek" to listOf("deepseek"),
            "Qwen" to listOf("qwen"),
            "Mistral" to listOf("mistral", "mixtral", "magistral"),
        )

        /** Families worth offering up front. Deliberately version-free. */
        private val MAINSTREAM_FAMILIES = listOf(
            "opus", "sonnet", "haiku",   // Anthropic
            "gemini",                     // Google
            "gpt", "o1", "o3", "o4",      // OpenAI
            "grok", "llama", "mistral", "deepseek", "qwen",
        )

        /**
         * Variants that repeat a family they belong to. Excluding them keeps
         * one row per model instead of six near-identical ones.
         */
        private val PREVIEW_MARKERS = listOf(
            "preview", "-exp", "experimental", "-latest", "nightly", "beta",
            ":free", "-0125", "-0613", "-1106", "-2024", "-2025", "-2026",
        )
    }
}
