package com.akh.lexdesk.util

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import com.akh.lexdesk.data.Attachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import kotlin.math.max

/** Turns a picked or shared file into something Claude can read. */
object AttachmentReader {

    const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    private const val MAX_PDF_BYTES = 22 * 1024 * 1024
    private const val MAX_IMAGE_SIDE = 1568

    suspend fun read(context: Context, uri: Uri): Attachment = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = displayName(resolver, uri) ?: "attachment"
        val type = resolver.getType(uri)?.takeIf { it != "application/octet-stream" } ?: guessType(name)
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("Could not open $name.")

        when {
            type == "application/pdf" -> {
                require(bytes.size <= MAX_PDF_BYTES) { "$name is over 22 MB. Split the PDF and attach the parts." }
                Attachment(name, Attachment.PDF, "application/pdf", base64(bytes))
            }
            type.startsWith("image/") ->
                Attachment(name, Attachment.IMAGE, "image/jpeg", base64(compressImage(bytes)))
            type == DOCX_MIME ->
                Attachment(name, Attachment.TEXT, "text/plain", docxText(bytes))
            type == "application/msword" ->
                throw IllegalArgumentException("Old .doc files are not supported. Save it as .docx or PDF and attach again.")
            type.startsWith("text/") ->
                Attachment(name, Attachment.TEXT, "text/plain", bytes.toString(Charsets.UTF_8))
            else -> throw IllegalArgumentException("Unsupported file type for $name. Use PDF, Word (.docx), an image or a text file.")
        }
    }

    private fun displayName(resolver: ContentResolver, uri: Uri): String? =
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment

    private fun guessType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "application/pdf"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "heic", "heif" -> "image/heic"
        "docx" -> DOCX_MIME
        "doc" -> "application/msword"
        "txt", "md" -> "text/plain"
        else -> "application/octet-stream"
    }

    private fun base64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    /** Downscales phone photos (often 10 MB+) and fixes rotation so Claude reads them reliably. */
    private fun compressImage(bytes: ByteArray): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_IMAGE_SIDE) sample *= 2

        val decoded = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: throw IllegalArgumentException("Could not read the image.")

        val rotation = runCatching { ExifInterface(ByteArrayInputStream(bytes)).rotationDegrees }.getOrDefault(0)
        val longest = max(decoded.width, decoded.height)
        val scale = if (longest > MAX_IMAGE_SIDE) MAX_IMAGE_SIDE.toFloat() / longest else 1f
        val matrix = Matrix().apply {
            if (scale < 1f) postScale(scale, scale)
            if (rotation != 0) postRotate(rotation.toFloat())
        }
        val result = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        return ByteArrayOutputStream().use { stream ->
            result.compress(Bitmap.CompressFormat.JPEG, 85, stream)
            stream.toByteArray()
        }
    }

    /** Extracts the plain text of a .docx, one line per paragraph. */
    private fun docxText(bytes: ByteArray): String {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "word/document.xml") {
                    val xml = zip.readBytes().toString(Charsets.UTF_8)
                    val text = xml
                        .replace("</w:p>", "\n")
                        .replace("<w:tab/>", "\t")
                        .replace(Regex("<w:br[^>]*/>"), "\n")
                        .replace(Regex("<[^>]+>"), "")
                    return unescapeXml(text).trim()
                }
                entry = zip.nextEntry
            }
        }
        throw IllegalArgumentException("Could not read the Word file.")
    }

    private fun unescapeXml(s: String): String = s
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}
