package com.akh.lexdesk.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/** Saves each chat as a JSON file in the app's private storage. Nothing leaves the phone. */
class ConversationStore(context: Context) {

    private val dir = File(context.filesDir, "conversations").apply { mkdirs() }
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun loadAll(): List<Conversation> = withContext(Dispatchers.IO) {
        dir.listFiles().orEmpty()
            .filter { it.extension == "json" }
            .mapNotNull { file ->
                runCatching { json.decodeFromString(Conversation.serializer(), file.readText()) }.getOrNull()
            }
            .sortedByDescending { it.updatedAt }
    }

    suspend fun save(conversation: Conversation): Unit = withContext(Dispatchers.IO) {
        if (conversation.messages.isEmpty()) return@withContext
        val tmp = File(dir, "${conversation.id}.tmp")
        tmp.writeText(json.encodeToString(Conversation.serializer(), conversation))
        tmp.renameTo(File(dir, "${conversation.id}.json"))
        Unit
    }

    suspend fun delete(id: String): Unit = withContext(Dispatchers.IO) {
        File(dir, "$id.json").delete()
        Unit
    }
}
