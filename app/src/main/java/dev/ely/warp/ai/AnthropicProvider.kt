package dev.ely.warp.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

/**
 * Talks to Claude via the Messages API.
 *
 * Written against raw HTTP on purpose. Warp already ships a 172 MB toolchain,
 * and the API surface we need — one streaming POST and one GET — is small
 * enough that a networking library would cost more than it saves.
 *
 * The key never appears in a log line here. It is read from [KeyVault] at the
 * moment of the request and goes into a header, nowhere else.
 */
class AnthropicProvider(
    private val context: Context,
    private val keyOverride: String? = null,
) : AiProvider {

    override val id = "anthropic"
    override val displayName = "Anthropic (Claude)"
    override val requiresKey = true

    // Claude takes an explicit effort level, so each model appears once per
    // level in the picker.
    override val supportsEffort = true

    private fun key(): String? =
        keyOverride ?: KeyVault.load(context, id)

    // ── models ───────────────────────────────────────────────────────────

    override suspend fun listModels(): Result<List<AiModel>> = withContext(Dispatchers.IO) {
        val key = key() ?: return@withContext Result.failure(AiException(AiError.NoKey))

        runCatching {
            val connection = open("$BASE_URL/models", key, method = "GET")
            val code = connection.responseCode
            if (code != 200) {
                throw AiException(errorFor(code, connection.readError()))
            }

            val body = JSONObject(connection.inputStream.bufferedReader().readText())
            val data = body.optJSONArray("data") ?: JSONArray()
            buildList {
                for (i in 0 until data.length()) {
                    val m = data.getJSONObject(i)
                    val modelId = m.getString("id")
                    add(
                        AiModel(
                            id = modelId,
                            displayName = m.optString("display_name", modelId),
                            contextTokens = m.optInt("max_input_tokens").takeIf { it > 0 },
                            // Haiku is the quick one; the larger models think.
                            badge = if ("haiku" in modelId.lowercase()) {
                                ModelBadge.FAST
                            } else {
                                ModelBadge.THINKING
                            },
                        )
                    )
                }
            }
        }.recoverCatching { throw asAiException(it) }
    }

    override suspend fun testConnection(): Result<String> {
        val models = listModels()
        return models.fold(
            onSuccess = { Result.success("Connected · ${it.size} models available") },
            onFailure = { Result.failure(it) },
        )
    }

    // ── the conversation ─────────────────────────────────────────────────

    override fun stream(request: AiRequest): Flow<AiEvent> = flow {
        val key = key()
        if (key == null) {
            emit(AiEvent.Failed(AiError.NoKey))
            return@flow
        }

        val connection = try {
            open("$BASE_URL/messages", key, method = "POST").apply {
                doOutput = true
                setRequestProperty("Accept", "text/event-stream")
                outputStream.bufferedWriter().use { it.write(buildBody(request).toString()) }
            }
        } catch (e: Exception) {
            emit(AiEvent.Failed(asAiError(e)))
            return@flow
        }

        val code = try {
            connection.responseCode
        } catch (e: Exception) {
            emit(AiEvent.Failed(asAiError(e)))
            return@flow
        }

        if (code != 200) {
            val detail = connection.readError()
            Log.w(TAG, "request failed: HTTP $code")
            emit(AiEvent.Failed(errorFor(code, detail)))
            return@flow
        }

        try {
            connection.inputStream.bufferedReader().use { reader ->
                var stopReason = "end_turn"
                // Tool calls arrive as a series of JSON fragments that have to
                // be stitched back together before they can be parsed.
                var toolName: String? = null
                var toolId: String? = null
                val toolArgs = StringBuilder()

                for (event in sseEvents(reader)) {
                    currentCoroutineContext().ensureActive()

                    when (event.optString("type")) {
                        "content_block_start" -> {
                            val block = event.optJSONObject("content_block")
                            if (block?.optString("type") == "tool_use") {
                                toolId = block.textOrNull("id").orEmpty()
                                toolName = block.textOrNull("name").orEmpty()
                                toolArgs.setLength(0)
                            }
                        }

                        "content_block_delta" -> {
                            val delta = event.optJSONObject("delta") ?: continue
                            when (delta.optString("type")) {
                                "text_delta" ->
                                    delta.textOrNull("text")
                                        ?.let { emit(AiEvent.TextDelta(it)) }
                                // Claude streams reasoning as its own delta
                                // type, and only when thinking was asked for in
                                // the request — which Warp does not do yet, so
                                // this is here for the day it does.
                                "thinking_delta" ->
                                    delta.textOrNull("thinking")
                                        ?.let { emit(AiEvent.ReasoningDelta(it)) }
                                "input_json_delta" ->
                                    delta.textOrNull("partial_json")?.let { toolArgs.append(it) }
                            }
                        }

                        "content_block_stop" -> {
                            val name = toolName
                            if (name != null) {
                                emit(
                                    AiEvent.ToolCallRequested(
                                        ToolCall(
                                            id = toolId ?: UUID.randomUUID().toString(),
                                            name = name,
                                            argumentsJson = toolArgs.toString().ifBlank { "{}" },
                                        )
                                    )
                                )
                                toolName = null
                                toolId = null
                                toolArgs.setLength(0)
                            }
                        }

                        "message_delta" ->
                            event.optJSONObject("delta")
                                ?.optString("stop_reason")
                                ?.takeIf { it.isNotEmpty() }
                                ?.let { stopReason = it }

                        "error" -> {
                            val message = event.optJSONObject("error")?.optString("message")
                            emit(AiEvent.Failed(AiError.Server(message ?: "stream error")))
                            return@use
                        }

                        "message_stop" -> {
                            emit(AiEvent.Completed(stopReason))
                            return@use
                        }
                    }
                }
                // Stream ended without message_stop — treat as complete rather
                // than as an error; the text we got is still valid.
                emit(AiEvent.Completed(stopReason))
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            emit(AiEvent.Failed(asAiError(e)))
        } finally {
            runCatching { connection.disconnect() }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Parse Server-Sent Events into JSON objects.
     *
     * The format is simple: `data: {...}` lines, blank line between events.
     * `event:` lines repeat the type that is already inside the JSON, so they
     * are ignored.
     */
    private fun sseEvents(reader: BufferedReader): Sequence<JSONObject> = sequence {
        while (true) {
            val line = reader.readLine() ?: break
            if (!line.startsWith("data:")) continue
            val payload = line.removePrefix("data:").trim()
            if (payload.isEmpty() || payload == "[DONE]") continue
            val parsed = runCatching { JSONObject(payload) }.getOrNull()
            if (parsed != null) yield(parsed)
        }
    }

    // ── request body ─────────────────────────────────────────────────────

    /**
     * A plain string, or blocks when there is a picture — §5h.
     *
     * This API takes an image as a base64 `source` block rather than a data
     * URL, which is the one real difference from the OpenAI dialect and the
     * reason the two mappings are written out separately instead of shared.
     * Text attachments never appear here: they are already in the text.
     */
    private fun contentFor(message: ChatMessage): Any {
        val text = Attachments.textFor(message)
        val images = Attachments.imagesFor(message)
        if (images.isEmpty()) return text

        val blocks = JSONArray()
        images.forEach { image ->
            blocks.put(
                JSONObject()
                    .put("type", "image")
                    .put(
                        "source",
                        JSONObject()
                            .put("type", "base64")
                            .put("media_type", image.mimeType)
                            .put("data", image.base64),
                    )
            )
        }
        if (text.isNotBlank()) {
            blocks.put(JSONObject().put("type", "text").put("text", text))
        }
        return blocks
    }

    private fun buildBody(request: AiRequest): JSONObject {
        val messages = JSONArray()
        request.messages
            // The API rejects empty content, and a placeholder assistant turn
            // carries no meaning to the model. A message with tool calls and no
            // text is not empty, though — dropping it would delete the half of
            // the conversation where anything actually happened.
            // An attachment is content too — a message that is only a screenshot
            // is exactly how somebody shows you a bug, and this used to drop it.
            .filter {
                (it.text.isNotBlank() || it.toolCalls.isNotEmpty() || Attachments.any(it)) &&
                    it.role != Role.SYSTEM
            }
            .forEach { message ->
                if (message.role == Role.USER || message.toolCalls.isEmpty()) {
                    messages.put(
                        JSONObject()
                            .put("role", if (message.role == Role.USER) "user" else "assistant")
                            .put("content", contentFor(message))
                    )
                    return@forEach
                }

                // An assistant turn that used tools is two API messages: what it
                // said and asked for, then what came back. The results go in a
                // *user* turn — that is the shape the API requires, and getting
                // it wrong is a 400 rather than a bad answer, which is at least
                // a failure you can see.
                val content = JSONArray()
                if (message.text.isNotBlank()) {
                    content.put(JSONObject().put("type", "text").put("text", message.text))
                }
                message.toolCalls.forEach { call ->
                    content.put(
                        JSONObject()
                            .put("type", "tool_use")
                            .put("id", call.id)
                            .put("name", call.name)
                            .put(
                                "input",
                                runCatching { JSONObject(call.argumentsJson) }
                                    .getOrDefault(JSONObject())
                            )
                    )
                }
                messages.put(JSONObject().put("role", "assistant").put("content", content))

                val results = JSONArray()
                message.toolCalls.forEach { call ->
                    results.put(
                        JSONObject()
                            .put("type", "tool_result")
                            .put("tool_use_id", call.id)
                            .put("is_error", call.status == ToolCall.Status.FAILED)
                            // The body, falling back to the summary. The model
                            // needs the file, not the sentence "112 lines"; every
                            // block must carry something, because a tool_use with
                            // no matching result is rejected outright.
                            .put(
                                "content",
                                call.forModel?.takeIf { it.isNotBlank() } ?: "no output",
                            )
                    )
                }
                messages.put(JSONObject().put("role", "user").put("content", results))
            }

        return JSONObject().apply {
            put("model", request.model)
            put("max_tokens", MAX_TOKENS)
            put("stream", true)
            put("messages", messages)
            request.systemPrompt?.let { put("system", it) }

            // Effort controls how hard the model thinks. Note there is no
            // temperature here on purpose: current Claude models reject
            // temperature, top_p and top_k outright.
            put("output_config", JSONObject().put("effort", request.effort.apiValue))

            if (request.tools.isNotEmpty()) {
                val tools = JSONArray()
                request.tools.forEach { spec ->
                    tools.put(
                        JSONObject()
                            .put("name", spec.name)
                            .put("description", spec.description)
                            .put("input_schema", JSONObject(spec.parametersJson))
                    )
                }
                put("tools", tools)
            }
        }
    }

    // ── plumbing ─────────────────────────────────────────────────────────

    private fun open(url: String, key: String, method: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpsURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 30_000
        // Generous: a long answer can pause between tokens, and cutting the
        // stream mid-reply is worse than waiting.
        connection.readTimeout = 300_000
        connection.setRequestProperty("x-api-key", key)
        connection.setRequestProperty("anthropic-version", API_VERSION)
        connection.setRequestProperty("Content-Type", "application/json")
        return connection
    }

    private fun HttpURLConnection.readError(): String =
        runCatching { errorStream?.bufferedReader()?.readText().orEmpty() }.getOrDefault("")

    private fun errorFor(code: Int, body: String): AiError {
        val message = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message")
        }.getOrNull().orEmpty()

        return when (code) {
            // Split for the reason in ProviderHttp.errorFor — §5o. A refused
            // request and a rejected key are fixed in different places.
            401 -> AiError.BadKey
            402 -> AiError.OutOfCredits("")
            403 -> AiError.Refused("")
            429 -> AiError.RateLimited
            in 500..599 -> AiError.Server(message.ifBlank { "HTTP $code" })
            else -> AiError.Unknown(message.ifBlank { "HTTP $code" })
        }
    }

    private fun asAiError(e: Throwable): AiError = when {
        e is AiException -> e.error
        e is UnknownHostException -> AiError.Offline
        e is IOException -> AiError.Offline
        else -> AiError.Unknown("${e.javaClass.simpleName}: ${e.message}")
    }

    private fun asAiException(e: Throwable): Throwable =
        if (e is AiException) e else AiException(asAiError(e))

    private companion object {
        const val TAG = "WarpAnthropic"
        const val BASE_URL = "https://api.anthropic.com/v1"

        /** Wire version of the Messages API. Not the model version. */
        const val API_VERSION = "2023-06-01"

        const val MAX_TOKENS = 16_000
    }
}

/** Carries an [AiError] through Kotlin's Result plumbing. */
class AiException(val error: AiError) : Exception(error.message)

/** Each provider names effort differently; this is Anthropic's spelling. */
private val Effort.apiValue: String
    get() = when (this) {
        Effort.LOW -> "low"
        Effort.MEDIUM -> "medium"
        Effort.HIGH -> "high"
        Effort.MAX -> "max"
    }
