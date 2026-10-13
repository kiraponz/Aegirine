package com.kira3.ametrine.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * How one control of the touch pad differs from the built-in layout: [x] and [y] move it by that
 * fraction of the pad's width and height, [scale] sizes it around its own center.
 */
@Serializable
data class ControlEdit(val x: Float = 0f, val y: Float = 0f, val scale: Float = 1f)

/**
 * The edits of a pad layout, kept apart for landscape and portrait. Keys are control ids (see TouchPadLayout.kt).
 * [controller] is for the function buttons while the touch pad is hidden (a controller is in use), for either orientation.
 */
@Serializable
data class PadLayoutEdits(
    val landscape: Map<String, ControlEdit> = emptyMap(),
    val portrait: Map<String, ControlEdit> = emptyMap(),
    val controller: Map<String, ControlEdit> = emptyMap(),
) {
    fun of(landscape: Boolean, controller: Boolean = false): Map<String, ControlEdit> = when {
        controller -> this.controller
        landscape -> this.landscape
        else -> portrait
    }

    fun with(landscape: Boolean, edits: Map<String, ControlEdit>, controller: Boolean = false): PadLayoutEdits = when {
        controller -> copy(controller = edits)
        landscape -> copy(landscape = edits)
        else -> copy(portrait = edits)
    }

    val isEmpty: Boolean get() = landscape.isEmpty() && portrait.isEmpty() && this.controller.isEmpty()
}

/**
 * The touch pad layouts on disk: `padlayouts/global.json` for every game, and `padlayouts/<serial>.json`
 * for a game that has its own. Read from the file each time, like the macros.
 */
class PadLayoutRepository(context: Context) {
    private val dir = File(context.applicationContext.filesDir, "padlayouts")
    private val writeMutex = Mutex()
    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }

    /** The layout [serial]'s game plays with: its own if it has one, else the global one. */
    suspend fun load(serial: String?): PadLayoutEdits =
        (serial?.let { read(fileFor(it)) }) ?: read(globalFile) ?: PadLayoutEdits()

    /** Stores [edits] for the game [serial], or for every game when it is null. An empty layout removes the file. */
    suspend fun save(serial: String?, edits: PadLayoutEdits) = writeMutex.withLock {
        write(serial?.let(::fileFor) ?: globalFile, edits)
    }

    /** Makes [serial]'s game follow the global layout again. */
    suspend fun clearGame(serial: String) = writeMutex.withLock {
        withContext(Dispatchers.IO) { fileFor(serial).delete() }
        Unit
    }

    private suspend fun read(file: File): PadLayoutEdits? = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext null
        runCatching { json.decodeFromString(PadLayoutEdits.serializer(), file.readText()) }
            .onFailure { Log.w(TAG, "Could not read ${file.name}", it) }
            .getOrNull()
    }

    private suspend fun write(file: File, edits: PadLayoutEdits) = withContext(Dispatchers.IO) {
        if (edits.isEmpty) {
            file.delete()
        } else {
            dir.mkdirs()
            val temp = File(dir, file.name + ".tmp")
            temp.writeText(json.encodeToString(PadLayoutEdits.serializer(), edits))
            check(temp.renameTo(file)) { "Could not write ${file.name}" }
        }
        Unit
    }

    private val globalFile get() = File(dir, "global.json")

    private fun fileFor(serial: String) = File(dir, "${serial.replace(Regex("[^A-Za-z0-9._-]"), "_")}.json")

    private companion object {
        const val TAG = "PadLayoutRepository"
    }
}
