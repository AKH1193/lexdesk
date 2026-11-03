package com.akh.lexdesk.api

import com.akh.lexdesk.data.Attachment
import com.akh.lexdesk.data.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
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

/** Calls Google's Gemini API (free tier works) and streams the reply. */
class GeminiClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.MINUTES)
        .readTimeout(5, TimeUnit.MINUTES)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    fun stream(
        apiKey: String,
        model: String,
        system: String,
        messages: List<ChatMessage>,
        maxTokens: Int = 16384,
    ): Flow<String> = callbackFlow {
        val body = buildBody(system, messages, maxTokens).toString()
        require(body.length < MAX_REQUEST_CHARS) {
            "The attachments are too large for Gemini in one message. Split the PDF and attach the parts."
        }
        val request = Request.Builder()
            .url("$BASE_URL/$model:streamGenerateContent?alt=sse")
            .header("x-goog-api-key", apiKey)
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
                    val source = response.body?.source() ?: throw ClaudeException("Empty response from Gemini.")
                    var produced = false
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val payload = line.substring(5).trim()
                        if (payload.isEmpty()) continue
                        val chunk = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: continue

                        chunk["error"]?.jsonObject?.let { err ->
                            throw ClaudeException(err["message"]?.jsonPrimitive?.contentOrNull ?: "Gemini reported an error.")
                        }
                        chunk["promptFeedback"]?.jsonObject?.get("blockReason")?.jsonPrimitive?.contentOrNull?.let {
                            throw ClaudeException("Gemini declined this request ($it). Try rephrasing, or use Private mode.")
                        }

                        val candidate = chunk["candidates"]?.jsonArray?.firstOrNull()?.jsonObject ?: continue
                        candidate["content"]?.jsonObject?.get("parts")?.jsonArray?.forEach { part ->
                            val p = part.jsonObject
                            val isThought = p["thought"]?.jsonPrimitive?.booleanOrNull == true
                            val text = p["text"]?.jsonPrimitive?.contentOrNull
                            if (!isThought && !text.isNullOrEmpty()) {
                                produced = true
                                send(text)
                            }
                        }
                        when (val reason = candidate["finishReason"]?.jsonPrimitive?.contentOrNull) {
                            null, "STOP", "FINISH_REASON_UNSPECIFIED" -> Unit
                            "MAX_TOKENS" -> send("\n\n[Output limit reached. Ask me to continue.]")
                            else -> if (!produced) {
                                throw ClaudeException("Gemini stopped without answering ($reason). Try rephrasing, or use Private mode.")
                            }
                        }
                    }
                }
                close()
            } catch (e: Throwable) {
                when {
                    call.isCanceled() -> close()
                    e is java.net.UnknownHostException -> close(
                        ClaudeException(
                            "Can't reach Google's servers from this network. Check that LexDesk is allowed Wi-Fi and " +
                                "mobile data (App info > Data usage) and that Private DNS is Automatic, or try mobile data."
                        )
                    )
                    else -> close(e)
                }
            }
        }

        awaitClose { call.cancel() }
    }

    private fun buildBody(system: String, messages: List<ChatMessage>, maxTokens: Int): JsonObject {
        val usable = messages.filter { it.text.isNotBlank() || it.attachments.isNotEmpty() }
        return buildJsonObject {
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { addJsonObject { put("text", system) } }
            }
            putJsonArray("contents") {
                usable.forEach { m ->
                    addJsonObject {
                        put("role", if (m.role == "assistant") "model" else "user")
                        putJsonArray("parts") {
                            m.attachments.forEach { a ->
                                if (a.kind == Attachment.TEXT) {
                                    addJsonObject { put("text", "Attached document: ${a.name}\n\n${a.data}") }
                                } else {
                                    addJsonObject {
                                        putJsonObject("inlineData") {
                                            put("mimeType", a.mediaType)
                                            put("data", a.data)
                                        }
                                    }
                                }
                            }
                            if (m.text.isNotBlank()) addJsonObject { put("text", m.text) }
                        }
                    }
                }
            }
            putJsonObject("generationConfig") {
                put("maxOutputTokens", maxTokens)
            }
        }
    }

    private fun errorMessage(code: Int, body: String): String {
        val serverMessage = runCatching {
            json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        val hint = when (code) {
            400 -> if (serverMessage?.contains("API key", ignoreCase = true) == true) "Gemini API key rejected. Check it in Settings." else null
            401, 403 -> "Gemini API key rejected or not permitted. Check it in Settings."
            404 -> "Gemini model not found. Check the Gemini model ID in Settings."
            429 -> "Gemini free-tier limit reached. Wait a while, or use the on-device model."
            500, 502, 503, 504 -> "Gemini is temporarily unavailable. Try again shortly."
            else -> null
        }
        return listOfNotNull(hint, serverMessage).joinToString("\n").ifBlank { "Gemini request failed (HTTP $code)." }
    }

    private companion object {
        const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        const val MAX_REQUEST_CHARS = 19_000_000
    }
}
