package dev.ely.warp.ai

import android.util.Log
import dev.ely.warp.data.ConversationRepository

/**
 * Gives conversations their names.
 *
 * The one place that knows both halves of the answer: [ConversationRepository]
 * decides what a title is allowed to overwrite, [ProviderRegistry] decides
 * whether a model may be asked for one, and [AutoTitler] does the asking.
 * [ChatEngine] is told none of it — it only knows that something names things.
 */
class ConversationTitler(
    private val repository: ConversationRepository,
    private val registry: ProviderRegistry,
) : ChatEngine.Titler {

    /**
     * The name every conversation gets, immediately.
     *
     * Not a placeholder. The first thing someone types is what the conversation
     * is about, so trimmed it reads well on its own — and because it costs
     * nothing and cannot fail, it is what makes the model optional rather than
     * load-bearing.
     */
    override suspend fun nameNow(conversationId: String, question: String) {
        repository.suggestTitle(conversationId, AutoTitler.fallback(question))
    }

    /**
     * Ask a model for a better one — if the person has said it may.
     *
     * Every exit here is silent. Naming is a nicety, and there is no version of
     * "the model was rate limited" worth showing to somebody who just wanted to
     * ask a question. The name they already have reads fine.
     */
    override suspend fun refine(conversationId: String, question: String, answer: String) {
        val (provider, model) = registry.namingModel() ?: return

        val title = AutoTitler.title(
            provider = provider,
            model = model,
            question = question,
            answer = answer,
        ) ?: return

        // Through suggestTitle, never rename: the guard against overwriting a
        // name a person chose lives in the SQL WHERE clause, and going around it
        // here would reintroduce exactly the race it exists to prevent — someone
        // renaming a conversation while the model is still thinking of a name
        // for it.
        repository.suggestTitle(conversationId, title)
        Log.d(TAG, "named $conversationId via ${provider.id}/$model")
    }

    private companion object {
        const val TAG = "WarpTitler"
    }
}
