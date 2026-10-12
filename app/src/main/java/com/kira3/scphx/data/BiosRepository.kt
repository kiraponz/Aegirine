package com.kira3.scphx.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.kira3.scphx.emucore.BiosMetadata
import com.kira3.scphx.emucore.EmulatorCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

data class Bios(val fileName: String, val metadata: BiosMetadata)

/**
 * The selected BIOS. The core opens the BIOS with plain file APIs, so a file picked
 * through SAF is copied into [EmulatorCore.biosDir] and selected by name.
 */
class BiosRepository(
    context: Context,
    private val core: EmulatorCore,
    private val preferences: AppPreferences,
) {
    private val contentResolver = context.applicationContext.contentResolver

    /** The selected BIOS, or null if none is selected or its file is gone or invalid. */
    val selected: Flow<Bios?> = preferences.biosFileName.map { name ->
        name?.let { withContext(Dispatchers.IO) { readInstalled(it) } }
    }

    /**
     * Copies the BIOS at [uri] into the BIOS folder and selects it.
     * Returns null if the file is not a PS2 BIOS.
     */
    suspend fun import(uri: Uri): Bios? = withContext(Dispatchers.IO) {
        core.initialize()
        val metadata = core.readBiosMetadata(uri) ?: return@withContext null
        val name = displayName(uri)?.let(::sanitizeFileName) ?: "bios.bin"
        val target = File(core.biosDir, name)
        val temp = File(core.biosDir, "$name.tmp")
        contentResolver.openInputStream(uri)?.use { input ->
            temp.outputStream().use { input.copyTo(it) }
        } ?: return@withContext null
        if (!temp.renameTo(target)) {
            temp.delete()
            return@withContext null
        }
        preferences.setBiosFileName(name)
        Bios(name, metadata)
    }

    /** Every valid BIOS in the BIOS folder, by file name. */
    suspend fun installed(): List<Bios> = withContext(Dispatchers.IO) {
        core.biosDir.listFiles().orEmpty()
            .filter { it.isFile && !it.name.endsWith(".tmp") }
            .sortedBy { it.name.lowercase() }
            .mapNotNull { readInstalled(it.name) }
    }

    private suspend fun readInstalled(name: String): Bios? {
        val file = File(core.biosDir, name)
        if (!file.isFile) return null
        core.initialize()
        val metadata = core.readBiosMetadata(Uri.fromFile(file)) ?: return null
        return Bios(name, metadata)
    }

    private fun displayName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    private fun sanitizeFileName(name: String): String? =
        name.replace('/', '_').trim().takeIf { it.isNotEmpty() && it != "." && it != ".." }
}
