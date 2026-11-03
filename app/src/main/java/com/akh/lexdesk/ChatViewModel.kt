package com.akh.lexdesk

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.akh.lexdesk.api.ClaudeClient
import com.akh.lexdesk.api.GeminiClient
import com.akh.lexdesk.data.Attachment
import com.akh.lexdesk.data.ChatMessage
import com.akh.lexdesk.data.Conversation
import com.akh.lexdesk.data.ConversationStore
import com.akh.lexdesk.data.SettingsStore
import com.akh.lexdesk.ondevice.ModelManager
import com.akh.lexdesk.ondevice.OnDeviceEngine
import com.akh.lexdesk.util.AttachmentReader
import com.akh.lexdesk.util.DocxExporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class EngineKind { ON_DEVICE, GEMINI, CLAUDE }

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    val settings = SettingsStore(app)
    val models = ModelManager(app, settings)
    private val store = ConversationStore(app)
    private val claude = ClaudeClient()
    private val gemini = GeminiClient()
    private val onDevice = OnDeviceEngine(app, settings)

    var conversations by mutableStateOf<List<Conversation>>(emptyList())
        private set
    var current by mutableStateOf(Conversation())
        private set
    var input by mutableStateOf("")
    val pending = mutableStateListOf<Attachment>()
    var isStreaming by mutableStateOf(false)
        private set
    var isReadingFile by mutableStateOf(false)
        private set
    var isImportingModel by mutableStateOf(false)
        private set
    var notice by mutableStateOf<String?>(null)
        private set

    private var streamJob: Job? = null

    init {
        viewModelScope.launch { conversations = store.loadAll() }
    }

    /** True when no engine can answer yet, so Settings should open first. */
    fun needsSetup(): Boolean =
        settings.geminiKey.isBlank() && settings.apiKey.isBlank() && !models.isReady()

    fun send() {
        if (isStreaming || isReadingFile) return
        val text = input.trim()
        if (text.isEmpty() && pending.isEmpty()) return

        val primary = try {
            chooseEngine(current)
        } catch (e: IllegalStateException) {
            notice = e.message
            return
        }

        val userMessage = ChatMessage(role = "user", text = text, attachments = pending.toList())
        val history = current.messages + userMessage
        var working = current.copy(
            title = current.title.ifBlank { titleFor(userMessage) },
            messages = history + ChatMessage(role = "assistant", text = "", engine = labelFor(primary)),
            updatedAt = System.currentTimeMillis(),
        )
        current = working
        input = ""
        pending.clear()
        isStreaming = true

        streamJob = viewModelScope.launch {
            val reply = StringBuilder()

            suspend fun collectFrom(kind: EngineKind) {
                streamFrom(kind, history).collect { chunk ->
                    reply.append(chunk)
                    working = working.withLast { it.copy(text = reply.toString()) }
                    if (current.id == working.id) current = working
                }
            }

            try {
                try {
                    collectFrom(primary)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val canFallBack = reply.isEmpty() &&
                        primary == EngineKind.GEMINI &&
                        settings.engineMode == SettingsStore.MODE_AUTO &&
                        models.isReady()
                    if (!canFallBack) throw e
                    val geminiError = e.message?.lineSequence()?.firstOrNull().orEmpty()
                    notice = "Gemini unavailable, answering on-device instead. ($geminiError)"
                    working = working.withLast { it.copy(engine = labelFor(EngineKind.ON_DEVICE)) }
                    if (current.id == working.id) current = working
                    try {
                        collectFrom(EngineKind.ON_DEVICE)
                    } catch (e2: CancellationException) {
                        throw e2
                    } catch (e2: Exception) {
                        // Report both failures, so the Gemini problem isn't hidden behind the on-device one.
                        throw IllegalStateException("Gemini failed: $geminiError\n\nOn-device also failed: ${e2.message}")
                    }
                }
            } catch (e: CancellationException) {
                // Stopped by the user; keep whatever arrived.
            } catch (e: Exception) {
                notice = e.message ?: "Request failed."
            } finally {
                if (reply.isBlank()) {
                    // Nothing came back: undo the turn and put the question back for a retry.
                    working = working.copy(messages = working.messages.dropLast(2))
                    if (working.messages.isEmpty()) working = working.copy(title = "")
                    if (current.id == working.id && input.isEmpty()) {
                        input = userMessage.text
                        pending.addAll(userMessage.attachments)
                    }
                }
                if (current.id == working.id) current = working
                isStreaming = false
                withContext(NonCancellable) {
                    store.save(working)
                    conversations = store.loadAll()
                }
            }
        }
    }

    private fun chooseEngine(conversation: Conversation): EngineKind {
        val modelReady = models.isReady()
        val needModel = "The on-device model isn't downloaded yet. Open Settings > On-device model."
        if (conversation.privateMode) {
            check(modelReady) { "Private mode uses the on-device model. $needModel" }
            return EngineKind.ON_DEVICE
        }
        return when (settings.engineMode) {
            SettingsStore.MODE_ON_DEVICE -> {
                check(modelReady) { needModel }
                EngineKind.ON_DEVICE
            }
            SettingsStore.MODE_GEMINI -> {
                check(settings.geminiKey.isNotBlank()) { "Add your free Gemini API key in Settings first." }
                EngineKind.GEMINI
            }
            SettingsStore.MODE_CLAUDE -> {
                check(settings.apiKey.isNotBlank()) { "Add your Anthropic API key in Settings first." }
                EngineKind.CLAUDE
            }
            else -> when {
                settings.geminiKey.isNotBlank() && isOnline() -> EngineKind.GEMINI
                modelReady -> EngineKind.ON_DEVICE
                settings.geminiKey.isBlank() ->
                    throw IllegalStateException("Add a free Gemini key or download the on-device model in Settings.")
                else -> throw IllegalStateException("You're offline and the on-device model isn't downloaded yet.")
            }
        }
    }

    private fun streamFrom(kind: EngineKind, history: List<ChatMessage>): Flow<String> = when (kind) {
        EngineKind.ON_DEVICE -> onDevice.stream(
            models.file(),
            LegalPrompt.buildCompact(settings.standingInstructions),
            history,
        )
        EngineKind.GEMINI -> gemini.stream(
            settings.geminiKey,
            settings.geminiModel,
            LegalPrompt.build(settings.standingInstructions),
            history,
        )
        EngineKind.CLAUDE -> claude.stream(
            settings.apiKey,
            settings.model,
            LegalPrompt.build(settings.standingInstructions),
            history,
        )
    }

    fun labelFor(kind: EngineKind): String = when (kind) {
        EngineKind.ON_DEVICE -> "On-device · ${models.selected().shortName}"
        EngineKind.GEMINI -> "Gemini · ${settings.geminiModel}"
        EngineKind.CLAUDE -> "Claude · ${settings.model}"
    }

    /** Short description of what will answer the next message in this chat. */
    fun routeLabel(): String {
        if (current.privateMode) return "Private · on-device only"
        return when (settings.engineMode) {
            SettingsStore.MODE_ON_DEVICE -> "On-device only"
            SettingsStore.MODE_GEMINI -> "Gemini"
            SettingsStore.MODE_CLAUDE -> "Claude"
            else -> "Auto · Gemini online, on-device offline"
        }
    }

    fun togglePrivate() {
        if (isStreaming) return
        val turningOn = !current.privateMode
        current = current.copy(privateMode = turningOn)
        notice = if (turningOn) {
            if (models.isReady()) "Private mode on: this chat stays on the phone."
            else "Private mode on. Download the on-device model in Settings to use it."
        } else {
            "Private mode off: this chat may use Gemini when online."
        }
        val snapshot = current
        viewModelScope.launch {
            store.save(snapshot)
            conversations = store.loadAll()
        }
    }

    private fun isOnline(): Boolean {
        val cm = getApplication<Application>().getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun stop() {
        streamJob?.cancel()
    }

    fun newChat() {
        stop()
        current = Conversation()
        input = ""
        pending.clear()
    }

    fun open(conversation: Conversation) {
        stop()
        current = conversation
        input = ""
        pending.clear()
    }

    fun delete(conversation: Conversation) {
        if (current.id == conversation.id) newChat()
        viewModelScope.launch {
            store.delete(conversation.id)
            conversations = store.loadAll()
        }
    }

    fun addAttachment(uri: Uri) {
        viewModelScope.launch {
            isReadingFile = true
            try {
                pending.add(AttachmentReader.read(getApplication<Application>(), uri))
            } catch (e: Exception) {
                notice = e.message ?: "Could not read that file."
            } finally {
                isReadingFile = false
            }
        }
    }

    fun removeAttachment(index: Int) {
        if (index in pending.indices) pending.removeAt(index)
    }

    fun exportDocx(uri: Uri, text: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use {
                        DocxExporter.write(it, text)
                    } ?: throw IllegalStateException("Could not create the file.")
                }
                notice = "Saved as a Word document."
            } catch (e: Exception) {
                notice = e.message ?: "Could not save the Word document."
            }
        }
    }

    // ---- Settings and on-device model management ----

    fun saveSettings(
        engineMode: String,
        geminiKey: String,
        geminiModel: String,
        claudeKey: String,
        claudeModel: String,
        instructions: String,
        useGpu: Boolean,
    ) {
        settings.engineMode = engineMode
        settings.geminiKey = geminiKey
        settings.geminiModel = geminiModel
        settings.apiKey = claudeKey
        settings.model = claudeModel
        settings.standingInstructions = instructions
        if (settings.onDeviceGpu != useGpu) {
            settings.onDeviceGpu = useGpu
            viewModelScope.launch { onDevice.release() }
        }
        notice = "Settings saved."
    }

    fun selectOnDeviceModel(id: String) {
        if (settings.onDeviceModel == id) return
        settings.onDeviceModel = id
        viewModelScope.launch { onDevice.release() }
    }

    fun downloadModel() {
        try {
            models.startDownload()
            notice = "Download started. Keep the phone on Wi-Fi; progress also shows in notifications."
        } catch (e: Exception) {
            notice = e.message ?: "Could not start the download."
        }
    }

    fun cancelModelDownload() = models.cancelDownload()

    fun deleteModel() {
        viewModelScope.launch {
            onDevice.release()
            models.delete()
            notice = "On-device model deleted."
        }
    }

    fun importModel(uri: Uri) {
        viewModelScope.launch {
            isImportingModel = true
            try {
                onDevice.release()
                val imported = models.importFrom(uri)
                notice = "${imported.shortName} imported and selected."
            } catch (e: Exception) {
                notice = e.message ?: "Could not import the model."
            } finally {
                isImportingModel = false
            }
        }
    }

    fun clearNotice() {
        notice = null
    }

    override fun onCleared() {
        // Free the on-device model's memory when the app closes.
        @Suppress("OPT_IN_USAGE")
        kotlinx.coroutines.GlobalScope.launch { onDevice.release() }
    }

    private fun titleFor(message: ChatMessage): String {
        val firstLine = message.text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
        val base = firstLine ?: message.attachments.firstOrNull()?.name ?: "New chat"
        return if (base.length > 48) base.take(47).trimEnd() + "…" else base
    }

    private fun Conversation.withLast(change: (ChatMessage) -> ChatMessage): Conversation = copy(
        messages = messages.dropLast(1) + change(messages.last()),
        updatedAt = System.currentTimeMillis(),
    )
}
