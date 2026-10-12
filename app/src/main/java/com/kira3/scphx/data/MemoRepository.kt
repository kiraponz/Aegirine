package com.kira3.scphx.data

import android.content.Context
import android.util.Log
import com.kira3.scphx.data.memo.Memo
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

/** A game's memos on disk: `memos/<serial>.json`. Read from the file each time, like the cheats. */
class MemoRepository(context: Context) {
    private val dir = File(context.applicationContext.filesDir, "memos")
    private val writeMutex = Mutex()
    private val _changes = MutableStateFlow(0)
    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Memo.serializer())

    fun memos(serial: String): Flow<List<Memo>> = _changes.map { load(serial) }

    suspend fun load(serial: String): List<Memo> = withContext(Dispatchers.IO) {
        val file = fileFor(serial)
        if (!file.isFile) return@withContext emptyList()
        runCatching { json.decodeFromString(serializer, file.readText()) }
            .onFailure { Log.w(TAG, "Could not read ${file.name}", it) }
            .getOrDefault(emptyList())
    }

    suspend fun update(serial: String, transform: (List<Memo>) -> List<Memo>) = writeMutex.withLock {
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
        const val TAG = "MemoRepository"
    }
}
