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
import java.net.URLEncoder

/**
 * Google's Gemini models.
 *
 * The odd one out: the key goes in the query string rather than a header, the
 * model id is part of the URL path, and messages are "contents" with "parts"
 * instead of a flat string.
 */
class GoogleProvider(private val context: Context) : AiProvider {

    override val id = "google"
    override val displayName = "Google (Gemini)"
    override val requiresKey = true

    private fun key(): String? = KeyVault.load(context, id)

    // ── models ───────────────────────────────────────────────────────────

    override suspend fun listModels(): Result<List<AiModel>> = withContext(Dispatchers.IO) {
        val key = key() ?: return@withContext Result.failure(AiException(AiError.NoKey))

        runCatching {
            val connection = ProviderHttp.open("$BASE_URL/models?key=${key.urlEncoded()}", "GET")
            if (connection.responseCode != 200) {
                throw AiException(
                    ProviderHttp.errorFor(connection.responseCode, connection.errorBody())
                )
            }

            val models = JSONObject(connection.inputStream.bufferedReader().readText())
                .optJSONArray("models") ?: JSONArray()

            buildList {
                for (i in 0 until models.length()) {
                    val m = models.getJSONObject(i)

                    // Only models that can hold a conversation are useful here;
                    // the list also contains embedding models.
                    val methods = m.optJSONArray("supportedGenerationMethods")
                    val canChat = (0 until (methods?.length() ?: 0)).any {
                        methods!!.optString(it) == "generateContent"
                    }
                    if (!canChat) continue

                    // Names come back as "models/gemini-…"; the bare id is what
                    // the request path wants.
                    val fullName = m.optString("name").ifBlank { continue }
                    val modelId = fullName.removePrefix("models/")

                    add(
                        AiModel(
                            id = modelId,
                            displayName = m.optString("displayName").ifBlank { modelId },
                            contextTokens = m.optInt("inputTokenLimit").takeIf { it > 0 },
                            badge = if ("flash" in modelId.lowercase()) ModelBadge.FAST else null,
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

        // alt=sse asks for Server-Sent Events; without it the response is a
        // single JSON array that only arrives once the answer is complete.
        val url = "$BASE_URL/models/${request.model}:streamGenerateContent" +
            "?alt=sse&key=${key.urlEncoded()}"

        val connection = try {
            ProviderHttp.open(url, "POST").apply {
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

                for (event in ProviderHttp.sseEvents(reader)) {
                    currentCoroutineContext().ensureActive()

                    val candidate = event.optJSONArray("candidates")?.optJSONObject(0) ?: continue

                    candidate.textOrNull("finishReason")
                        ?.let { stopReason = it }

                    val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: continue
                    for (i in 0 until parts.length()) {
                        val part = parts.getJSONObject(i)

                        part.textOrNull("text")
                            ?.let { emit(AiEvent.TextDelta(it)) }

                        part.optJSONObject("functionCall")?.let { call ->
                            emit(
                                AiEvent.ToolCallRequested(
                                    ToolCall(
                                        id = java.util.UUID.randomUUID().toString(),
                                        name = call.textOrNull("name").orEmpty(),
                                        argumentsJson = call.optJSONObject("args")?.toString() ?: "{}",
                                    )
                                )
                            )
                        }
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
        val contents = JSONArray()
        request.messages
            // A turn that only called tools still has to be sent, or the model
            // is asked to carry on from a conversation it never had.
            .filter { (it.text.isNotBlank() || it.toolCalls.isNotEmpty()) && it.role != Role.SYSTEM }
            .forEach { message ->
                if (message.role == Role.USER || message.toolCalls.isEmpty()) {
                    contents.put(
                        JSONObject()
                            // Gemini calls the assistant "model", not "assistant".
                            .put("role", if (message.role == Role.USER) "user" else "model")
                            .put("parts", JSONArray().put(JSONObject().put("text", message.text)))
                    )
                    return@forEach
                }

                // A third arrangement of the same facts: parts rather than
                // blocks, `functionResponse` rather than a tool role, and the
                // result matched by *name* — Gemini's function calls carry no id,
                // which is why the one made up at parse time is never sent back.
                val parts = JSONArray()
                if (message.text.isNotBlank()) {
                    parts.put(JSONObject().put("text", message.text))
                }
                message.toolCalls.forEach { call ->
                    parts.put(
                        JSONObject().put(
                            "functionCall",
                            JSONObject()
                                .put("name", call.name)
                                .put(
                                    "args",
                                    runCatching { JSONObject(call.argumentsJson) }
                                        .getOrDefault(JSONObject())
                                )
                        )
                    )
                }
                contents.put(JSONObject().put("role", "model").put("parts", parts))

                val results = JSONArray()
                message.toolCalls.forEach { call ->
                    results.put(
                        JSONObject().put(
                            "functionResponse",
                            JSONObject()
                                .put("name", call.name)
                                .put(
                                    "response",
                                    JSONObject().put(
                                        "result",
                                        call.body?.takeIf { it.isNotBlank() }
                                            ?: call.result
                                            ?: "no output",
                                    )
                                )
                        )
                    )
                }
                contents.put(JSONObject().put("role", "user").put("parts", results))
            }

        return JSONObject().apply {
            put("contents", contents)

            request.systemPrompt?.let { prompt ->
                put(
                    "systemInstruction",
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                )
            }

            put("generationConfig", JSONObject().put("maxOutputTokens", MAX_TOKENS))

            if (request.tools.isNotEmpty()) {
                val declarations = JSONArray()
                request.tools.forEach { spec ->
                    declarations.put(
                        JSONObject()
                            .put("name", spec.name)
                            .put("description", spec.description)
                            .put("parameters", JSONObject(spec.parametersJson))
                    )
                }
                put(
                    "tools",
                    JSONArray().put(JSONObject().put("functionDeclarations", declarations))
                )
            }
        }
    }

    private fun String.urlEncoded(): String = URLEncoder.encode(this, "UTF-8")

    private fun wrap(e: Throwable): Throwable =
        if (e is AiException) e else AiException(ProviderHttp.asAiError(e))

    private companion object {
        const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
        const val MAX_TOKENS = 16_000
    }
}
