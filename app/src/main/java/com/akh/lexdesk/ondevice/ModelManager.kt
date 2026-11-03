package com.akh.lexdesk.ondevice

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.akh.lexdesk.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Downloads, imports and locates the on-device Gemma model files. */
class ModelManager(private val context: Context, private val settings: SettingsStore) {

    data class ModelInfo(
        val id: String,
        val shortName: String,
        val label: String,
        val fileName: String,
        val url: String,
        val approxBytes: Long,
    )

    sealed interface Status {
        data object NotDownloaded : Status
        data class Downloading(val done: Long, val total: Long) : Status
        data object Ready : Status
        data class Failed(val reason: String) : Status
    }

    private val downloads = context.getSystemService(DownloadManager::class.java)

    private val dir: File
        get() = (context.getExternalFilesDir(MODELS_DIR) ?: File(context.filesDir, MODELS_DIR)).apply { mkdirs() }

    fun selected(): ModelInfo = byId(settings.onDeviceModel)

    fun byId(id: String): ModelInfo = MODELS.firstOrNull { it.id == id } ?: MODELS.first()

    fun file(model: ModelInfo = selected()): File = File(dir, model.fileName)

    private fun partFile(model: ModelInfo): File = File(dir, model.fileName + ".part")

    fun isReady(model: ModelInfo = selected()): Boolean =
        file(model).let { it.exists() && it.length() > MIN_VALID_BYTES }

    fun status(model: ModelInfo = selected()): Status {
        if (isReady(model)) return Status.Ready
        val id = settings.downloadId
        if (id <= 0 || settings.downloadModel != model.id) return Status.NotDownloaded

        downloads.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (!c.moveToFirst()) return Status.NotDownloaded
            val state = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            return when (state) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    finishDownload(model)
                    if (isReady(model)) Status.Ready else Status.Failed("The downloaded file is incomplete. Try again.")
                }
                DownloadManager.STATUS_FAILED -> {
                    val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    Status.Failed("Download failed (code $reason). Try again on Wi-Fi, or use Import file.")
                }
                else -> Status.Downloading(done.coerceAtLeast(0), if (total > 0) total else model.approxBytes)
            }
        }
        return Status.NotDownloaded
    }

    fun startDownload(model: ModelInfo = selected()) {
        cancelDownload()
        partFile(model).delete()
        val request = DownloadManager.Request(Uri.parse(model.url))
            .setTitle("LexDesk: ${model.shortName}")
            .setDescription("On-device legal model")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, MODELS_DIR, model.fileName + ".part")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
        settings.downloadId = downloads.enqueue(request)
        settings.downloadModel = model.id
    }

    fun cancelDownload() {
        val id = settings.downloadId
        if (id > 0) runCatching { downloads.remove(id) }
        settings.downloadId = -1L
        MODELS.forEach { partFile(it).delete() }
    }

    fun delete(model: ModelInfo = selected()) {
        if (settings.downloadModel == model.id) cancelDownload()
        file(model).delete()
        partFile(model).delete()
    }

    /**
     * Copies a model file the advocate downloaded manually (for example from a computer).
     * The file name decides which slot it goes into, and wrong variants are rejected.
     * Returns the model it was imported as.
     */
    suspend fun importFrom(uri: Uri): ModelInfo = withContext(Dispatchers.IO) {
        val name = displayName(uri).orEmpty()
        val model = MODELS.firstOrNull { it.fileName.equals(name, ignoreCase = true) }
            ?: throw IllegalArgumentException(importHelp(name))
        val part = partFile(model)
        context.contentResolver.openInputStream(uri)?.use { input ->
            part.outputStream().use { output -> input.copyTo(output, bufferSize = 1 shl 20) }
        } ?: throw IllegalArgumentException("Could not open the selected file.")
        require(part.length() > MIN_VALID_BYTES) {
            part.delete()
            "That file is too small to be a model. Pick the .litertlm file."
        }
        file(model).delete()
        part.renameTo(file(model))
        settings.onDeviceModel = model.id
        model
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    private fun importHelp(name: String): String {
        val lower = name.lowercase()
        val why = when {
            !lower.endsWith(".litertlm") -> "it is not a .litertlm model file"
            listOf("gpu", "qualcomm", "mediatek", "tensor", "npu", "web", "sm8").any { it in lower } ->
                "it is a GPU, NPU or web build that this app can't load"
            else -> "the name doesn't match a supported model"
        }
        return "Can't import \"$name\": $why. Use exactly ${MODELS.joinToString(" or ") { it.fileName }} " +
            "from huggingface.co/litert-community."
    }

    private fun finishDownload(model: ModelInfo) {
        val part = partFile(model)
        if (part.exists()) {
            file(model).delete()
            part.renameTo(file(model))
        }
        settings.downloadId = -1L
    }

    companion object {
        private const val MODELS_DIR = "models"
        private const val MIN_VALID_BYTES = 100L * 1024 * 1024

        val MODELS = listOf(
            ModelInfo(
                id = "e2b",
                shortName = "Gemma 4 E2B",
                label = "Gemma 4 E2B (2.6 GB, recommended, most reliable)",
                fileName = "gemma-4-E2B-it.litertlm",
                url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
                approxBytes = 2_580_000_000L,
            ),
            ModelInfo(
                id = "e4b",
                shortName = "Gemma 4 E4B",
                label = "Gemma 4 E4B (3.7 GB, better answers, experimental)",
                fileName = "gemma-4-E4B-it.litertlm",
                url = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
                approxBytes = 3_650_000_000L,
            ),
        )
    }
}
