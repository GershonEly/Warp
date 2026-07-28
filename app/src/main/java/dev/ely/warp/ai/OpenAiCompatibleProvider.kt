package dev.ely.warp.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import dev.ely.warp.ai.ProviderHttp.errorBody
import dev.ely.warp.ai.ProviderHttp.writeJson
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Chat Completions — the format OpenAI defined and much of the industry copied.
 *
 * OpenRouter speaks it too, which is why this is one class with two
 * configurations rather than two nearly identical files.
 */
abstract class OpenAiCompatibleProvider(
    private val context: Context,
    private val baseUrl: String,
) : AiProvider {

    /** Extra headers a particular service wants. OpenRouter asks for attribution. */
    protected open val extraHeaders: Map<String, String> = emptyMap()

    /** Some services list models without authentication; OpenRouter does. */
    protected open val modelsNeedKey: Boolean = true

    private fun key(): String? = KeyVault.load(context, id)

    private fun headers(key: String?): Map<String, String> = buildMap {
        if (key != null) put("Authorization", "Bearer $key")
        putAll(extraHeaders)
    }

    // ── models ───────────────────────────────────────────────────────────

    override suspend fun listModels(): Result<List<AiModel>> = withContext(Dispatchers.IO) {
        val key = key()
        if (key == null && modelsNeedKey) {
            return@withContext Result.failure(AiException(AiError.NoKey))
        }

        runCatching {
            val connection = ProviderHttp.open("$baseUrl/models", "GET", headers(key))
            if (connection.responseCode != 200) {
                throw AiException(ProviderHttp.errorFor(connection.responseCode, connection.errorBody()))
            }

            val data = JSONObject(connection.inputStream.bufferedReader().readText())
                .optJSONArray("data") ?: JSONArray()

            buildList {
                for (i in 0 until data.length()) {
                    val m = data.getJSONObject(i)
                    val modelId = m.optString("id").ifBlank { continue }
                    add(
                        AiModel(
                            id = modelId,
                            displayName = m.optString("name").ifBlank { modelId },
                            contextTokens = m.optInt("context_length").takeIf { it > 0 },
                            badge = badgeFor(modelId),
                        )
                    )
                }
            }.sortedBy { it.displayName.lowercase() }
        }.recoverCatching { throw wrap(it) }
    }

    override suspend fun testConnection(): Result<String> =
        listModels().fold(
            onSuccess = { Result.success("Connected · ${it.size} models available") },
            onFailure = { Result.failure(it) },
        )

    // ── the conversation ─────────────────────────────────────────────────

    override fun stream(request: AiRequest): Flow<AiEvent> = flow {
        val key = key()
        if (key == null) {
            emit(AiEvent.Failed(AiError.NoKey))
            return@flow
        }

        val connection = try {
            ProviderHttp.open("$baseUrl/chat/completions", "POST", headers(key)).apply {
                setRequestProperty("Accept", "text/event-stream")
                writeJson(buildBody(request))
            }
        } catch (e: Exception) {
            emit(AiEvent.Failed(ProviderHttp.asAiError(e)))
            return@flow
        }

        val code = try {
            connection.responseCode
        } catch (e: Exception) {
            emit(AiEvent.Failed(ProviderHttp.asAiError(e)))
            return@flow
        }

        if (code != 200) {
            emit(AiEvent.Failed(ProviderHttp.errorFor(code, connection.errorBody())))
            return@flow
        }

        try {
            connection.inputStream.bufferedReader().use { reader ->
                var stopReason = "end_turn"
                // Tool calls stream as fragments across many events, keyed by
                // their position in the array rather than by id.
                val toolNames = mutableMapOf<Int, String>()
                val toolIds = mutableMapOf<Int, String>()
                val toolArgs = mutableMapOf<Int, StringBuilder>()

                for (event in ProviderHttp.sseEvents(reader)) {
                    currentCoroutineContext().ensureActive()

                    val choice = event.optJSONArray("choices")?.optJSONObject(0) ?: continue
                    val delta = choice.optJSONObject("delta")

                    delta?.optString("content")
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { emit(AiEvent.TextDelta(it)) }

                    delta?.optJSONArray("tool_calls")?.let { calls ->
                        for (i in 0 until calls.length()) {
                            val call = calls.getJSONObject(i)
                            val index = call.optInt("index", i)
                            call.optString("id").takeIf { it.isNotEmpty() }
                                ?.let { toolIds[index] = it }
                            val fn = call.optJSONObject("function")
                            fn?.optString("name")?.takeIf { it.isNotEmpty() }
                                ?.let { toolNames[index] = it }
                            fn?.optString("arguments")?.takeIf { it.isNotEmpty() }
                                ?.let { toolArgs.getOrPut(index) { StringBuilder() }.append(it) }
                        }
                    }

                    choice.optString("finish_reason").takeIf { it.isNotEmpty() && it != "null" }
                        ?.let { reason ->
                            stopReason = reason
                            // Tool calls are only complete once the turn ends.
                            toolNames.forEach { (index, name) ->
                                emit(
                                    AiEvent.ToolCallRequested(
                                        ToolCall(
                                            id = toolIds[index] ?: UUID.randomUUID().toString(),
                                            name = name,
                                            argumentsJson = toolArgs[index]?.toString()
                                                ?.ifBlank { "{}" } ?: "{}",
                                        )
                                    )
                                )
                            }
                            toolNames.clear(); toolIds.clear(); toolArgs.clear()
                        }
                }
                emit(AiEvent.Completed(stopReason))
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            emit(AiEvent.Failed(ProviderHttp.asAiError(e)))
        } finally {
            runCatching { connection.disconnect() }
        }
    }.flowOn(Dispatchers.IO)

    private fun buildBody(request: AiRequest): JSONObject {
        val messages = JSONArray()

        // This format carries the system prompt as the first message rather
        // than as its own field.
        request.systemPrompt?.let {
            messages.put(JSONObject().put("role", "system").put("content", it))
        }

        request.messages
            .filter { it.text.isNotBlank() && it.role != Role.SYSTEM }
            .forEach { message ->
                messages.put(
                    JSONObject()
                        .put("role", if (message.role == Role.USER) "user" else "assistant")
                        .put("content", message.text)
                )
            }

        return JSONObject().apply {
            put("model", request.model)
            put("messages", messages)
            put("stream", true)
            put("max_tokens", MAX_TOKENS)

            if (request.tools.isNotEmpty()) {
                val tools = JSONArray()
                request.tools.forEach { spec ->
                    tools.put(
                        JSONObject()
                            .put("type", "function")
                            .put(
                                "function",
                                JSONObject()
                                    .put("name", spec.name)
                                    .put("description", spec.description)
                                    .put("parameters", JSONObject(spec.parametersJson))
                            )
                    )
                }
                put("tools", tools)
            }
        }
    }

    /** A rough hint from the model's name; these services do not report it. */
    private fun badgeFor(modelId: String): ModelBadge? {
        val id = modelId.lowercase()
        return when {
            "mini" in id || "flash" in id || "haiku" in id || "turbo" in id -> ModelBadge.FAST
            "o1" in id || "o3" in id || "reason" in id || "think" in id -> ModelBadge.THINKING
            else -> null
        }
    }

    private fun wrap(e: Throwable): Throwable =
        if (e is AiException) e else AiException(ProviderHttp.asAiError(e))

    private companion object {
        const val MAX_TOKENS = 16_000
    }
}

/** OpenAI's own service. */
class OpenAiProvider(context: Context) :
    OpenAiCompatibleProvider(context, "https://api.openai.com/v1") {
    override val id = "openai"
    override val displayName = "OpenAI (GPT)"
    override val requiresKey = true
}

/**
 * One key, many models from many companies.
 *
 * Its model list is public, so the picker can show what is on offer before a
 * key exists — the only provider where that is possible.
 */
class OpenRouterProvider(context: Context) :
    OpenAiCompatibleProvider(context, "https://openrouter.ai/api/v1") {
    override val id = "openrouter"
    override val displayName = "OpenRouter (many models)"
    override val requiresKey = true
    override val modelsNeedKey = false
    override val extraHeaders = mapOf(
        // OpenRouter uses these for attribution in its dashboards.
        "HTTP-Referer" to "https://github.com/GershonEly/Warp",
        "X-Title" to "Warp",
    )
}
