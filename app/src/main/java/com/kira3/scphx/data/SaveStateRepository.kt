package com.kira3.scphx.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.kira3.scphx.emucore.EmulatorCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/** A numbered save-state slot of a game, a file in the core's state folder. */
data class SaveStateFile(
    val serial: String,
    val crc: String,
    val slot: Int,
    val name: String,
    val bytes: Long,
    val modified: Long,
)

/** A save-state file picked to import: whose it is, and the slot its name says. */
data class SaveStateImport(val uri: Uri, val serial: String, val crc: String, val slot: Int, val replaces: Boolean)

enum class SaveStateResult { Ok, Invalid, Failed, Busy }

/**
 * The numbered save states in `<dataDir>/sstates`. A state file is named by the core
 * `<serial> (<CRC>).<slot>.p2s` and is a zip (the machine's memory plus a screenshot). Exporting copies
 * the file out under that name, and importing reads the game and slot from the name again, so a
 * file renamed on the way in is not accepted.
 */
class SaveStateRepository(context: Context, private val core: EmulatorCore) {
    private val appContext = context.applicationContext
    private val changes = MutableStateFlow(0)

    val states: Flow<List<SaveStateFile>> = changes.map { withContext(Dispatchers.IO) { list() } }

    private fun list(): List<SaveStateFile> =
        core.saveStatesDir.listFiles().orEmpty().mapNotNull { file ->
            val match = NAME.matchEntire(file.name) ?: return@mapNotNull null
            SaveStateFile(
                match.groupValues[1], match.groupValues[2], match.groupValues[3].toInt(),
                file.name, file.length(), file.lastModified(),
            )
        }

    /** The screenshot inside a state file, as PNG bytes; null if it has none. Blocking I/O. */
    fun thumbnail(state: SaveStateFile): ByteArray? = try {
        ZipFile(File(core.saveStatesDir, state.name)).use { zip ->
            zip.getEntry(SCREENSHOT)?.let { entry -> zip.getInputStream(entry).use { it.readBytes() } }
        }
    } catch (e: IOException) {
        null
    }

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

    /** Reads the name of the file at [uri] and what it would replace; null if it is not a state file of the core's naming. */
    suspend fun inspect(uri: Uri): SaveStateImport? = withContext(Dispatchers.IO) {
        val name = displayName(uri) ?: return@withContext null
        val match = NAME.matchEntire(name) ?: return@withContext null
        val slot = match.groupValues[3].toInt()
        if (slot !in EmulatorCore.SAVE_STATE_SLOTS) return@withContext null
        val serial = match.groupValues[1]
        val crc = match.groupValues[2]
        SaveStateImport(uri, serial, crc, slot, File(core.saveStatesDir, fileName(serial, crc, slot)).exists())
    }

    /** Puts the file in [slot] of the game, which can be another slot than its name says. */
    suspend fun import(import: SaveStateImport, slot: Int): SaveStateResult = withContext(Dispatchers.IO) {
        if (isGameProcessRunning(appContext)) return@withContext SaveStateResult.Busy
        val target = File(core.saveStatesDir, fileName(import.serial, import.crc, slot))
        val temp = File(core.saveStatesDir, target.name + ".importing")
        try {
            core.saveStatesDir.mkdirs()
            val input = appContext.contentResolver.openInputStream(import.uri) ?: return@withContext SaveStateResult.Failed
            input.use { stream -> temp.outputStream().use { stream.copyTo(it) } }
            // A state is a zip with the screenshot in it; anything else is not one.
            val valid = try {
                ZipFile(temp).use { it.getEntry(VERSION_ENTRY) != null }
            } catch (e: IOException) {
                false
            }
            if (!valid) {
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

    private fun fileName(serial: String, crc: String, slot: Int) = "$serial ($crc).%02d.p2s".format(slot)

    private fun displayName(uri: Uri): String? =
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    companion object {
        private const val TAG = "SaveStateRepository"
        private const val SCREENSHOT = "Screenshot.png"
        private const val VERSION_ENTRY = "PCSX2 Savestate Version.id"
        private val NAME = Regex("^(.+) \\(([0-9A-Fa-f]{8})\\)\\.(\\d{2})\\.p2s$")
    }
}
