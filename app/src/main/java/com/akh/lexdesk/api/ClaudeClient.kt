package com.akh.lexdesk.api

import com.akh.lexdesk.data.Attachment
import com.akh.lexdesk.data.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class ClaudeException(message: String) : Exception(message)

/** Calls the Claude Messages API directly from the phone and streams the reply. */
class ClaudeClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.MINUTES)
        .readTimeout(5, TimeUnit.MINUTES)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /** Emits the reply text in pieces as it is generated. Cancelling the collector cancels the request. */
    fun stream(
        apiKey: String,
        model: String,
        system: String,
        messages: List<ChatMessage>,
        maxTokens: Int = 16000,
    ): Flow<String> = callbackFlow {
        val body = buildBody(model, system, messages, maxTokens).toString()
        val request = Request.Builder()
            .url(API_URL)
            .header("x-api-key", apiKey)
            .header("anthropic-version", API_VERSION)
            .header("content-type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val call = http.newCall(request)

        launch(Dispatchers.IO) {
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        throw ClaudeException(errorMessage(response.code, response.body?.string().orEmpty()))
                    }
                    val source = response.body?.source() ?: throw ClaudeException("Empty response from the server.")
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val payload = line.substring(5).trim()
                        if (payload.isEmpty()) continue
                        val event = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: continue

                        when (event["type"]?.jsonPrimitive?.contentOrNull) {
                            "content_block_delta" -> {
                                val delta = event["delta"]?.jsonObject
                                if (delta?.get("type")?.jsonPrimitive?.contentOrNull == "text_delta") {
                                    delta?.get("text")?.jsonPrimitive?.contentOrNull?.let { send(it) }
                                }
                            }
                            "message_delta" -> {
                                val stop = event["delta"]?.jsonObject?.get("stop_reason")?.jsonPrimitive?.contentOrNull
                                if (stop == "max_tokens") send("\n\n[Output limit reached. Ask me to continue.]")
                            }
                            "error" -> throw ClaudeException(
                                event["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                                    ?: "The server reported an error."
                            )
                            "message_stop" -> break
                        }
                    }
                }
                close()
            } catch (e: Throwable) {
                if (call.isCanceled()) close() else close(e)
            }
        }

        awaitClose { call.cancel() }
    }

    private fun buildBody(model: String, system: String, messages: List<ChatMessage>, maxTokens: Int): JsonObject {
        val usable = messages.filter { it.text.isNotBlank() || it.attachments.isNotEmpty() }
        return buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            put("stream", true)
            putJsonArray("system") {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", system)
                    putJsonObject("cache_control") { put("type", "ephemeral") }
                })
            }
            putJsonArray("messages") {
                usable.forEachIndexed { index, m ->
                    val blocks = buildList {
                        m.attachments.forEach { add(attachmentBlock(it)) }
                        if (m.text.isNotBlank()) add(buildJsonObject {
                            put("type", "text")
                            put("text", m.text)
                        })
                    }
                    // Cache the conversation so far, so long PDFs are not billed in full on every follow-up.
                    val finalBlocks = if (index == usable.lastIndex && blocks.isNotEmpty()) {
                        blocks.dropLast(1) + JsonObject(blocks.last() + ("cache_control" to ephemeral()))
                    } else blocks

                    add(buildJsonObject {
                        put("role", m.role)
                        putJsonArray("content") { finalBlocks.forEach { add(it) } }
                    })
                }
            }
        }
    }

    private fun ephemeral(): JsonElement = buildJsonObject { put("type", "ephemeral") }

    private fun attachmentBlock(a: Attachment): JsonObject = when (a.kind) {
        Attachment.PDF -> buildJsonObject {
            put("type", "document")
            put("title", a.name)
            putJsonObject("source") {
                put("type", "base64")
                put("media_type", "application/pdf")
                put("data", a.data)
            }
        }
        Attachment.IMAGE -> buildJsonObject {
            put("type", "image")
            putJsonObject("source") {
                put("type", "base64")
                put("media_type", a.mediaType)
                put("data", a.data)
            }
        }
        else -> buildJsonObject {
            put("type", "document")
            put("title", a.name)
            putJsonObject("source") {
                put("type", "text")
                put("media_type", "text/plain")
                put("data", a.data)
            }
        }
    }

    private fun errorMessage(code: Int, body: String): String {
        val serverMessage = runCatching {
            json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        val hint = when (code) {
            401 -> "API key rejected. Check the key in Settings."
            403 -> "This API key is not permitted to make that request."
            404 -> "Model not found. Check the model ID in Settings."
            413 -> "The attachments are too large for one request."
            429 -> "Rate limit reached. Wait a minute and try again."
            500, 502, 503, 529 -> "Claude is temporarily unavailable or overloaded. Try again shortly."
            else -> null
        }
        return listOfNotNull(hint, serverMessage).joinToString("\n").ifBlank { "Request failed (HTTP $code)." }
    }

    private companion object {
        const val API_URL = "https://api.anthropic.com/v1/messages"
        const val API_VERSION = "2023-06-01"
    }
}
