package com.akh.lexdesk.ondevice

import android.content.Context
import android.util.Base64
import com.akh.lexdesk.data.Attachment
import com.akh.lexdesk.data.ChatMessage
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper

/**
 * The phone's model has a much smaller working memory than a cloud model, so the
 * conversation and documents are packed into one compact prompt and trimmed to fit.
 */
object OnDevicePrompt {

    private const val MAX_DOC_CHARS = 12_000
    private const val MAX_HISTORY_CHARS = 4_000

    fun build(context: Context, history: List<ChatMessage>): String {
        val current = history.last()

        val transcript = history.dropLast(1)
            .filter { it.text.isNotBlank() }
            .joinToString("\n\n") { m ->
                (if (m.role == "user") "Advocate: " else "LexDesk: ") + m.text.trim()
            }
            .takeLast(MAX_HISTORY_CHARS)

        val documents = current.attachments.joinToString("\n\n") { a ->
            when (a.kind) {
                Attachment.TEXT -> "Document: ${a.name}\n${a.data}"
                Attachment.PDF -> "Document: ${a.name}\n${pdfText(context, a)}"
                else -> throw IllegalArgumentException(
                    "Photos can't be read by the on-device model. Turn off Private mode so Gemini can read it."
                )
            }
        }
        val trimmedDocs = if (documents.length > MAX_DOC_CHARS) {
            documents.take(MAX_DOC_CHARS) +
                "\n[Document truncated: only the first part could be read on-device. Say so in your answer.]"
        } else documents

        return buildString {
            if (transcript.isNotBlank()) {
                append("Earlier in this conversation:\n").append(transcript.trim()).append("\n\n")
            }
            if (trimmedDocs.isNotBlank()) {
                append("Attached material:\n").append(trimmedDocs).append("\n\n")
            }
            append("Current request:\n")
            append(current.text.ifBlank { "Review the attached material and set out the key legal points." })
        }
    }

    private fun pdfText(context: Context, a: Attachment): String {
        PDFBoxResourceLoader.init(context.applicationContext)
        val bytes = Base64.decode(a.data, Base64.NO_WRAP)
        PDDocument.load(bytes).use { doc ->
            val text = PDFTextStripper().getText(doc).trim()
            if (text.length < 50) {
                throw IllegalArgumentException(
                    "${a.name} looks like a scanned PDF. Scanned pages need Gemini; turn off Private mode."
                )
            }
            return text
        }
    }
}
