package com.kira3.scphx.data

import android.content.Context
import android.util.Log
import com.kira3.scphx.emucore.settings.EmulatorSettings
import com.kira3.scphx.emucore.settings.SettingsJson
import com.kira3.scphx.emucore.settings.SettingsOverrides
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** A game's settings: what it runs with, and which of those values it sets itself. */
data class GameSettings(val effective: EmulatorSettings, val overrides: SettingsOverrides)

/**
 * Emulator settings on disk: `settings/global.json`, and `settings/games/<serial>.json`
 * holding only the values a game overrides.
 *
 * Every read goes to the files, so the settings never live in a singleton shared between
 * activities (EmulationActivity may move to its own process). [changes] only tells readers
 * in this process when to read again.
 */
class SettingsRepository(context: Context) {
    private val root = File(context.applicationContext.filesDir, "settings")
    private val gamesDir = File(root, "games")
    private val writeMutex = Mutex()

    private val _changes = MutableStateFlow(0)

    /** Bumped after every write made through this repository. */
    val changes: Flow<Int> = _changes

    /** Tells readers to read again, for changes another process made (the game's :emu process). */
    fun reload() {
        _changes.value++
    }

    val global: Flow<EmulatorSettings> = _changes.map { loadGlobal() }

    fun game(serial: String): Flow<GameSettings> = _changes.map { loadGame(serial) }

    suspend fun loadGlobal(): EmulatorSettings = withContext(Dispatchers.IO) {
        SettingsJson.decode(read(globalFile()))
    }

    suspend fun loadOverrides(serial: String): SettingsOverrides = withContext(Dispatchers.IO) {
        SettingsOverrides(read(gameFile(serial)))
    }

    suspend fun loadGame(serial: String): GameSettings {
        val overrides = loadOverrides(serial)
        return GameSettings(SettingsJson.merge(loadGlobal(), overrides), overrides)
    }

    suspend fun updateGlobal(transform: (EmulatorSettings) -> EmulatorSettings) = writeMutex.withLock {
        val current = loadGlobal()
        val updated = transform(current)
        if (updated != current) {
            write(globalFile(), SettingsJson.format(SettingsJson.encode(updated)))
            _changes.value++
        }
    }

    /**
     * Edits what [serial] runs with. Every value [transform] changes becomes an override for
     * this game, even one that ends up equal to the global value.
     */
    suspend fun updateGame(serial: String, transform: (EmulatorSettings) -> EmulatorSettings) = writeMutex.withLock {
        val current = loadGame(serial)
        saveOverrides(serial, current.overrides.record(current.effective, transform(current.effective)))
    }

    /**
     * Edits the value at [path] where it is set for [serial]: in that game's settings if the
     * game overrides it, otherwise in the global settings. For quick toggles during play, which
     * should neither create an override nor be hidden by one.
     */
    suspend fun updateWhereSet(serial: String?, path: String, transform: (EmulatorSettings) -> EmulatorSettings) {
        if (serial != null && path in loadOverrides(serial)) updateGame(serial, transform) else updateGlobal(transform)
    }

    /** Makes the values at [paths] (dotted, e.g. "graphics.fxaa") follow the global settings again. */
    suspend fun resetGame(serial: String, paths: Collection<String>) = writeMutex.withLock {
        saveOverrides(serial, loadOverrides(serial).without(paths))
    }

    private suspend fun saveOverrides(serial: String, overrides: SettingsOverrides) {
        if (overrides == loadOverrides(serial)) return
        withContext(Dispatchers.IO) {
            val file = gameFile(serial)
            if (overrides.isEmpty()) file.delete() else write(file, SettingsJson.format(overrides.json))
        }
        _changes.value++
    }

    private fun globalFile() = File(root, "global.json")

    private fun gameFile(serial: String) = File(gamesDir, "${serial.replace(UNSAFE_CHARS, "_")}.json")

    private fun read(file: File) =
        SettingsJson.parse(if (file.isFile) runCatching { file.readText() }.getOrDefault("") else "")

    /** Writes through a temporary file so a crash mid-write cannot leave a truncated file. */
    private suspend fun write(file: File, text: String) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(text)
        if (!temp.renameTo(file)) {
            temp.delete()
            Log.e(TAG, "Could not write $file")
        }
    }

    private companion object {
        const val TAG = "SettingsRepository"
        val UNSAFE_CHARS = Regex("[^A-Za-z0-9._-]")
    }
}
