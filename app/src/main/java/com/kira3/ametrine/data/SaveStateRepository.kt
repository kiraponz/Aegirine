package com.kira3.ametrine.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.kira3.ametrine.emucore.EmulatorCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** A numbered save-state slot of a game, a file in the core's state folder. */
data class SaveStateFile(
    val serial: String,
    val slot: Int,
    val name: String,
    val bytes: Long,
    val modified: Long,
)

/**
 * A save-state file picked to import. [serial] is the game its header names (null for a game
 * without one) and [slot] the slot its file name gives, if it still does.
 */
data class SaveStateImport(val uri: Uri, val name: String, val serial: String?, val slot: Int?)

enum class SaveStateResult { Ok, Invalid, Failed, Busy }

/**
 * The numbered save states in the core's state folder, named by the core `<serial>_<slot>.sav`.
 * A state's header holds the game's serial and a screenshot, so an imported file goes to the
 * game it was saved from whatever its name; only the slot comes from the name.
 */
class SaveStateRepository(context: Context, private val core: EmulatorCore) {
    private val appContext = context.applicationContext
    private val changes = MutableStateFlow(0)

    val states: Flow<List<SaveStateFile>> = changes.map { withContext(Dispatchers.IO) { list() } }

    private fun list(): List<SaveStateFile> =
        core.saveStatesDir.listFiles().orEmpty().mapNotNull { file ->
            val match = NAME.matchEntire(file.name) ?: return@mapNotNull null
            val slot = match.groupValues[2].toInt().takeIf { it in EmulatorCore.SAVE_STATE_SLOTS } ?: return@mapNotNull null
            SaveStateFile(match.groupValues[1], slot, file.name, file.length(), file.lastModified())
        }

    /** The screenshot in a state file, as PNG bytes; null if it has none. Blocking I/O. */
    fun thumbnail(state: SaveStateFile): ByteArray? =
        core.readSaveState(File(core.saveStatesDir, state.name))?.screenshot

    suspend fun export(state: SaveStateFile, uri: Uri): SaveStateResult = withContext(Dispatchers.IO) {
        if (isGameProcessRunning(appContext)) return@withContext SaveStateResult.Busy
        try {
            val output = appContext.contentResolver.openOutputStream(uri, "wt") ?: return@withContext SaveStateResult.Failed
            output.use { out -> File(core.saveStatesDir, state.name).inputStream().use { it.copyTo(out) } }
            SaveStateResult.Ok
        } catch (e: IOException) {
            Log.e(TAG, "Exporting a save state failed", e)
            SaveStateResult.Failed
        }
    }

    /** Reads the game from the header of the file at [uri], and the slot from its name; null if it is not a state. */
    suspend fun inspect(uri: Uri): SaveStateImport? = withContext(Dispatchers.IO) {
        val name = displayName(uri) ?: return@withContext null
        val temp = File(appContext.cacheDir, "inspect.sav")
        try {
            if (!copy(uri, temp)) return@withContext null
            val header = core.readSaveState(temp) ?: return@withContext null
            val slot = LOOSE_SLOT.find(name)?.groupValues?.get(1)?.toInt()?.takeIf { it in EmulatorCore.SAVE_STATE_SLOTS }
            SaveStateImport(uri, name, header.serial.ifEmpty { null }, slot)
        } catch (e: IOException) {
            Log.w(TAG, "Could not read $uri", e)
            null
        } finally {
            temp.delete()
        }
    }

    /** Puts the file in [slot] of the game with [serial]. */
    suspend fun import(uri: Uri, serial: String, slot: Int): SaveStateResult = withContext(Dispatchers.IO) {
        if (isGameProcessRunning(appContext)) return@withContext SaveStateResult.Busy
        val target = core.saveStateFile(serial, slot)
        val temp = File(core.saveStatesDir, target.name + ".importing")
        try {
            core.saveStatesDir.mkdirs()
            if (!copy(uri, temp)) return@withContext SaveStateResult.Failed
            if (core.readSaveState(temp) == null) {
                temp.delete()
                return@withContext SaveStateResult.Invalid
            }
            target.delete()
            if (!temp.renameTo(target)) {
                temp.delete()
                return@withContext SaveStateResult.Failed
            }
            changes.value++
            SaveStateResult.Ok
        } catch (e: IOException) {
            Log.e(TAG, "Importing a save state failed", e)
            temp.delete()
            SaveStateResult.Failed
        }
    }

    suspend fun delete(state: SaveStateFile): SaveStateResult = withContext(Dispatchers.IO) {
        if (isGameProcessRunning(appContext)) return@withContext SaveStateResult.Busy
        File(core.saveStatesDir, state.name).delete()
        changes.value++
        SaveStateResult.Ok
    }

    fun refresh() {
        changes.value++
    }

    private fun copy(uri: Uri, to: File): Boolean {
        val input = appContext.contentResolver.openInputStream(uri) ?: return false
        input.use { stream -> to.outputStream().use { stream.copyTo(it) } }
        return true
    }

    private fun displayName(uri: Uri): String? =
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    companion object {
        private const val TAG = "SaveStateRepository"
        private val NAME = Regex("^(.+)_(\\d{1,2})\\.sav$")
        private val LOOSE_SLOT = Regex("_(\\d{1,2})(?!\\d)[^_]*$")
    }
}
