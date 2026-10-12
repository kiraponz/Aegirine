package com.kira3.scphx.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.kira3.scphx.emucore.VulkanDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/** An installed Vulkan driver pack. [id] is its folder name and what the settings store. */
data class GpuDriver(
    val id: String,
    val name: String,
    val description: String,
    val vendor: String,
    val author: String,
    val version: String,
    val libraryName: String,
    val dir: File,
) {
    fun toVulkanDriver() = VulkanDriver(dir, libraryName)
}

/**
 * Custom Vulkan drivers (Mesa Turnip and the like) installed from adrenotools zip packs:
 * `meta.json` plus the driver library at the root, the format Yuzu, Strato and ARMSX2 use.
 *
 * Each pack is extracted to `files/drivers/<id>/`. The folder must be in internal storage:
 * Android refuses to load a library from shared or external storage.
 */
class DriverRepository(context: Context) {
    private val contentResolver = context.applicationContext.contentResolver
    private val root = File(context.applicationContext.filesDir, "drivers")
    private val changes = MutableStateFlow(0)

    val installed: Flow<List<GpuDriver>> = changes.map { list() }

    suspend fun list(): List<GpuDriver> = withContext(Dispatchers.IO) {
        root.listFiles { file -> file.isDirectory && !file.name.endsWith(TEMP_SUFFIX) }.orEmpty()
            .mapNotNull(::read)
            .sortedBy { it.name.lowercase() }
    }

    /**
     * Installs the zip pack at [uri], replacing an installed pack of the same file name.
     * Returns null if it is not a driver pack.
     */
    suspend fun install(uri: Uri): GpuDriver? = withContext(Dispatchers.IO) {
        val fileName = displayName(uri) ?: "driver.zip"
        val id = fileName.substringBeforeLast('.').replace(UNSAFE_CHARS, "_").lowercase().ifEmpty { "driver" }
        val target = File(root, id)
        val temp = File(root, id + TEMP_SUFFIX).apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            val opened = contentResolver.openInputStream(uri)?.use { extract(it, temp) } != null
            if (!opened || !ensureMeta(temp, id)) {
                temp.deleteRecursively()
                return@withContext null
            }
            target.deleteRecursively()
            if (!temp.renameTo(target)) {
                Log.e(TAG, "Could not move $temp to $target")
                temp.deleteRecursively()
                return@withContext null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Installing $fileName failed", e)
            temp.deleteRecursively()
            return@withContext null
        }
        changes.value++
        read(target)
    }

    suspend fun delete(driver: GpuDriver) {
        withContext(Dispatchers.IO) { driver.dir.deleteRecursively() }
        changes.value++
    }

    /** Extracts every file of the zip into [dir], flattened: packs keep their files at the root. */
    private fun extract(input: java.io.InputStream, dir: File) {
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                // Only the last path segment is used, so no entry can write outside dir.
                val name = entry.name.substringAfterLast('/')
                if (entry.isDirectory || name.isEmpty() || name == "." || name == "..") continue
                File(dir, name).outputStream().use { zip.copyTo(it) }
            }
        }
    }

    /**
     * True if [dir] holds a pack: a meta.json naming a library that is there. Some packs ship
     * only the library, so a minimal meta.json is written for a lone .so.
     */
    private fun ensureMeta(dir: File, id: String): Boolean {
        val meta = File(dir, META)
        if (!meta.isFile) {
            val library = dir.listFiles { file -> file.isFile && file.name.endsWith(".so") }?.singleOrNull() ?: return false
            meta.writeText(JSONObject().put("name", id).put("libraryName", library.name).toString())
        }
        val libraryName = runCatching { JSONObject(meta.readText()).optString("libraryName") }.getOrNull()
            ?.ifEmpty { DEFAULT_LIBRARY } ?: return false
        return File(dir, libraryName).isFile
    }

    /** The pack in [dir], or null if it is incomplete. */
    private fun read(dir: File): GpuDriver? {
        val json = runCatching { JSONObject(File(dir, META).readText()) }.getOrNull() ?: return null
        val libraryName = json.optString("libraryName").ifEmpty { DEFAULT_LIBRARY }
        if (!File(dir, libraryName).isFile) return null
        return GpuDriver(
            id = dir.name,
            name = json.optString("name").ifEmpty { dir.name },
            description = json.optString("description"),
            vendor = json.optString("vendor"),
            author = json.optString("author"),
            version = json.optString("driverVersion").ifEmpty { json.optString("packageVersion") },
            libraryName = libraryName,
            dir = dir,
        )
    }

    private fun displayName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    private companion object {
        const val TAG = "DriverRepository"
        const val META = "meta.json"
        const val TEMP_SUFFIX = ".tmp"

        /** What every Turnip pack calls its library when meta.json leaves it out. */
        const val DEFAULT_LIBRARY = "libvulkan_freedreno.so"
        val UNSAFE_CHARS = Regex("[^A-Za-z0-9._-]")
    }
}
