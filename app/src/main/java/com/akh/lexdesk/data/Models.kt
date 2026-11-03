package com.akh.lexdesk.data

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class Attachment(
    val name: String,
    /** One of [PDF], [IMAGE], [TEXT]. */
    val kind: String,
    val mediaType: String,
    /** Base64 for PDF and images; plain text for text and Word files. */
    val data: String,
) {
    companion object {
        const val PDF = "pdf"
        const val IMAGE = "image"
        const val TEXT = "text"
    }
}

@Serializable
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    /** "user" or "assistant". */
    val role: String,
    val text: String,
    val attachments: List<Attachment> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    /** Which engine wrote an assistant reply, e.g. "Gemini · gemini-3.8-flash". */
    val engine: String = "",
)

@Serializable
data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** When true, this chat is answered only by the on-device model. */
    val privateMode: Boolean = false,
)
