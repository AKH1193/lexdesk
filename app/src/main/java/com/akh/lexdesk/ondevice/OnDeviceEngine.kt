package com.akh.lexdesk.ondevice

import android.content.Context
import com.akh.lexdesk.data.ChatMessage
import com.akh.lexdesk.data.SettingsStore
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * Runs Gemma on the phone with LiteRT-LM. Nothing leaves the device.
 * The engine takes several seconds to load, so it is kept in memory between questions.
 * All engine work happens on one dedicated thread, as LiteRT-LM recommends.
 */
class OnDeviceEngine(private val context: Context, private val settings: SettingsStore) {

    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private var engine: Engine? = null
    private var loadedPath: String? = null
    private var loadedGpu: Boolean = false

    fun stream(modelFile: File, system: String, history: List<ChatMessage>): Flow<String> = flow {
        if (!modelFile.exists()) {
            throw IllegalStateException("The on-device model isn't downloaded yet. Open Settings > On-device model.")
        }
        val prompt = OnDevicePrompt.build(context, history)
        val useGpu = settings.onDeviceGpu
        var emitted = false
        try {
            generate(modelFile.path, useGpu, system, prompt) { emitted = true; emit(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!useGpu || emitted) throw e
            // The GPU path failed on this phone: switch to CPU permanently and retry once.
            settings.onDeviceGpu = false
            generate(modelFile.path, false, system, prompt) { emit(it) }
        }
    }.flowOn(dispatcher)

    private suspend fun generate(
        path: String,
        gpu: Boolean,
        system: String,
        prompt: String,
        onChunk: suspend (String) -> Unit,
    ) {
        val engine = ensureEngine(path, gpu)
        val conversation = engine.createConversation(
            ConversationConfig(systemInstruction = Contents.of(system))
        )
        try {
            conversation.sendMessageAsync(prompt).collect { message -> onChunk(message.toString()) }
        } finally {
            runCatching { conversation.cancelProcess() }
            runCatching { conversation.close() }
        }
    }

    private suspend fun ensureEngine(path: String, gpu: Boolean): Engine {
        val existing = engine
        if (existing != null && loadedPath == path && loadedGpu == gpu) return existing
        closeEngine()
        val config = EngineConfig(
            modelPath = path,
            backend = if (gpu) Backend.GPU() else Backend.CPU(),
            cacheDir = context.cacheDir.path,
        )
        val created = Engine(config)
        try {
            created.initialize()
        } catch (e: Exception) {
            runCatching { created.close() }
            throw friendly(e, path)
        }
        engine = created
        loadedPath = path
        loadedGpu = gpu
        return created
    }

    /** Turns the runtime's internal errors into an instruction the advocate can act on. */
    private fun friendly(e: Exception, path: String): Exception {
        val raw = e.message.orEmpty()
        val incompatible = "NOT_FOUND" in raw || "not found in the model" in raw ||
            "INVALID_ARGUMENT" in raw || "Failed to create engine" in raw
        if (!incompatible) return e
        val name = File(path).name
        return IllegalStateException(
            "The on-device model file ($name) can't be loaded on this phone. In Settings, tap Delete model, " +
                "select Gemma 4 E2B and download it again. (Detail: ${raw.lineSequence().firstOrNull().orEmpty().take(120)})"
        )
    }

    private fun closeEngine() {
        runCatching { engine?.close() }
        engine = null
        loadedPath = null
    }

    /** Frees the model from memory (for example after deleting or switching models). */
    suspend fun release() = withContext(dispatcher) { closeEngine() }
}
