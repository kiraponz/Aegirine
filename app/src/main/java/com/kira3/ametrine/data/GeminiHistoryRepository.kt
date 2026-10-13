package com.kira3.ametrine.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * A game's conversations with Gemini on disk: `gemini/<serial>.json`, newest first, at most
 * [MAX_CHATS] of them. Read from the file each time, like the memos.
 */
class GeminiHistoryRepository(context: Context) {
    private val dir = File(context.applicationContext.filesDir, "gemini")
    private val writeMutex = Mutex()
    private val _changes = MutableStateFlow(0)
    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }
    private val serializer = ListSerializer(GeminiChat.serializer())

    fun chats(serial: String): Flow<List<GeminiChat>> = _changes.map { load(serial) }

    suspend fun load(serial: String): List<GeminiChat> = withContext(Dispatchers.IO) {
        val file = fileFor(serial)
        if (!file.isFile) return@withContext emptyList()
        runCatching { json.decodeFromString(serializer, file.readText()) }
            .onFailure { Log.w(TAG, "Could not read ${file.name}", it) }
            .getOrDefault(emptyList())
    }

    /** Adds [chat], or replaces the one with its id, and keeps the newest [MAX_CHATS]. */
    suspend fun save(serial: String, chat: GeminiChat) = update(serial) { list ->
        (list.filterNot { it.id == chat.id } + chat).sortedByDescending { it.updatedAt }.take(MAX_CHATS)
    }

    suspend fun delete(serial: String, id: String) = update(serial) { list -> list.filterNot { it.id == id } }

    private suspend fun update(serial: String, transform: (List<GeminiChat>) -> List<GeminiChat>) = writeMutex.withLock {
        val updated = transform(load(serial))
        withContext(Dispatchers.IO) {
            val file = fileFor(serial)
            if (updated.isEmpty()) {
                file.delete()
            } else {
                dir.mkdirs()
                val temp = File(dir, file.name + ".tmp")
                temp.writeText(json.encodeToString(serializer, updated))
                check(temp.renameTo(file)) { "Could not write ${file.name}" }
            }
        }
        _changes.value++
    }

    private fun fileFor(serial: String) = File(dir, "${serial.replace(Regex("[^A-Za-z0-9._-]"), "_")}.json")

    companion object {
        const val MAX_CHATS = 50
        private const val TAG = "GeminiHistory"
    }
}
