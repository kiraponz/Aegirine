package com.kira3.scphx.data

import android.content.Context
import android.util.Log
import com.kira3.scphx.emucore.Cheat
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
 * A game's cheats on disk: `cheats/<serial>.json`. Like the settings, every read goes to the
 * file, because the game's :emu process reads and edits the same files.
 */
class CheatRepository(context: Context) {
    private val dir = File(context.applicationContext.filesDir, "cheats")
    private val writeMutex = Mutex()
    private val _changes = MutableStateFlow(0)
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Cheat.serializer())

    fun cheats(serial: String): Flow<List<Cheat>> = _changes.map { load(serial) }

    suspend fun load(serial: String): List<Cheat> = withContext(Dispatchers.IO) {
        val file = fileFor(serial)
        if (!file.isFile) return@withContext emptyList()
        runCatching { json.decodeFromString(serializer, file.readText()) }
            .onFailure { Log.w(TAG, "Could not read ${file.name}", it) }
            .getOrDefault(emptyList())
    }

    suspend fun update(serial: String, transform: (List<Cheat>) -> List<Cheat>) = writeMutex.withLock {
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

    private companion object {
        const val TAG = "CheatRepository"
    }
}
