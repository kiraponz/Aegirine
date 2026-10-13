package com.kira3.scphx.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile

/** What the user and the player leave behind for one game; not part of the Room cache, so a rescan keeps it. */
@Serializable
data class GameRecord(
    /** Epoch millis of the last time the game was started or left, 0 for never. */
    val lastPlayed: Long = 0,
    val playSeconds: Long = 0,
    val favorite: Boolean = false,
    /** Replaces the GameDB name or file name when set. */
    val customTitle: String? = null,
    /** The CRC the core named the game's save states by, learned when the game last ran. */
    val crc: String? = null,
)

/**
 * Per-game records in `library/records.json`, keyed by [keyOf]. The :emu process adds play
 * time while the main process edits favorites and titles, so every write holds a file lock
 * and starts from what is on disk.
 */
class GameRecordRepository(context: Context) {
    private val dir = File(context.applicationContext.filesDir, "library")
    private val file = File(dir, "records.json")
    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), GameRecord.serializer())
    private val _changes = MutableStateFlow(0)

    /** All records; re-read on [refresh], because the other process writes without telling us. */
    val records: Flow<Map<String, GameRecord>> = _changes.map { withContext(Dispatchers.IO) { read() } }

    fun refresh() {
        _changes.value++
    }

    suspend fun update(key: String, transform: (GameRecord) -> GameRecord) = withContext(Dispatchers.IO) {
        updateBlocking(key, transform)
        _changes.value++
    }

    /** For the :emu process, which has to write while it is being stopped. */
    fun addPlayTimeBlocking(key: String, seconds: Long, now: Long = System.currentTimeMillis()) =
        updateBlocking(key) { it.copy(playSeconds = it.playSeconds + seconds, lastPlayed = now) }

    private fun updateBlocking(key: String, transform: (GameRecord) -> GameRecord) {
        dir.mkdirs()
        RandomAccessFile(File(dir, "records.lock"), "rw").use { lockFile ->
            lockFile.channel.lock().use {
                val all = read().toMutableMap()
                val updated = transform(all[key] ?: GameRecord())
                if (updated == GameRecord()) all.remove(key) else all[key] = updated
                val temp = File(dir, "records.json.tmp")
                temp.writeText(json.encodeToString(serializer, all))
                check(temp.renameTo(file)) { "Could not write ${file.name}" }
            }
        }
    }

    private fun read(): Map<String, GameRecord> {
        if (!file.isFile) return emptyMap()
        return runCatching { json.decodeFromString(serializer, file.readText()) }
            .onFailure { Log.w(TAG, "Could not read ${file.name}", it) }
            .getOrDefault(emptyMap())
    }

    companion object {
        private const val TAG = "GameRecordRepository"

        /** The serial identifies a game across folders; images without one fall back to their URI. */
        fun keyOf(serial: String?, uri: String): String = serial ?: uri
    }
}
