package com.kira3.ametrine.data

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.StringRes
import com.kira3.ametrine.R
import com.kira3.ametrine.emucore.EmulatorCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** What a backup can hold. Each part is a folder (or a file) of the app's data, saved under [id] in the zip. */
enum class BackupPart(val id: String, @StringRes val title: Int, @StringRes val summary: Int) {
    Settings("settings", R.string.backup_part_settings, R.string.backup_part_settings_summary),
    Library("library", R.string.backup_part_library, R.string.backup_part_library_summary),
    Cheats("cheats", R.string.backup_part_cheats, R.string.backup_part_cheats_summary),
    Macros("macros", R.string.backup_part_macros, R.string.backup_part_macros_summary),
    Memos("memos", R.string.backup_part_memos, R.string.backup_part_memos_summary),
    MemoryCards("memcards", R.string.backup_part_memcards, R.string.backup_part_memcards_summary),
    SaveStates("sstates", R.string.backup_part_states, R.string.backup_part_states_summary),
}

/** One part as found on the device or in a backup file. */
data class BackupPartInfo(val part: BackupPart, val files: Int, val bytes: Long)

/** What a backup file holds. */
data class BackupContents(val createdAt: Long, val parts: List<BackupPartInfo>)

enum class BackupResult { Ok, Invalid, Failed, Busy }

/**
 * Backs the app's own data up into one zip and restores it. The zip holds `manifest.json` and one
 * folder per [BackupPart]. Not in it: the BIOS files, the game library's folders (SAF permissions
 * belong to the device), the Gemini API key and the custom driver.
 *
 * Restoring puts the backup's files over the ones on the device and leaves the others alone,
 * except for memory cards, where a card in the backup replaces the whole card of that name.
 * Nothing runs while a game is, because the game's process owns the cards and the states.
 */
class BackupRepository(
    context: Context,
    private val core: EmulatorCore,
    private val settings: SettingsRepository,
    private val records: GameRecordRepository,
    private val memoryCards: MemoryCardRepository,
) {
    private val appContext = context.applicationContext
    private val filesDir = appContext.filesDir

    /** The folder of [part] on the device. */
    private fun root(part: BackupPart): File = when (part) {
        BackupPart.Settings -> File(filesDir, "settings")
        BackupPart.Library -> File(filesDir, "library")
        BackupPart.Cheats -> File(filesDir, "cheats")
        BackupPart.Macros -> File(filesDir, "macros")
        BackupPart.Memos -> File(filesDir, "memos")
        BackupPart.MemoryCards -> core.memoryCardsDir
        BackupPart.SaveStates -> core.saveStatesDir
    }

    /** Whether [relative] (a path inside the part's folder) goes into a backup. */
    private fun included(part: BackupPart, relative: String): Boolean = when (part) {
        // The lock file is the :emu process's; the rest of the folder is the play records.
        BackupPart.Library -> relative == "records.json"
        // The numbered slots only: the resume state belongs to a game left half-way, the autosaves to rewind.
        BackupPart.SaveStates -> relative.endsWith(".p2s") && ".resume." !in relative && ".autosave" !in relative
        else -> true
    }

    /** The pad layouts are part of the settings. */
    private fun extraRoots(part: BackupPart): List<Pair<String, File>> = when (part) {
        BackupPart.Settings -> listOf("padlayouts" to File(filesDir, "padlayouts"))
        else -> emptyList()
    }

    /** What the device holds, per part. Parts that hold nothing are left out. */
    suspend fun available(): List<BackupPartInfo> = withContext(Dispatchers.IO) {
        BackupPart.entries.mapNotNull { part ->
            val files = filesOf(part)
            if (files.isEmpty()) null else BackupPartInfo(part, files.size, files.sumOf { it.second.length() })
        }
    }

    /** The files of [part] as (path in the part's folder, file). */
    private fun filesOf(part: BackupPart): List<Pair<String, File>> {
        val out = ArrayList<Pair<String, File>>()
        fun collect(base: File, prefix: String) {
            base.walkTopDown().filter { it.isFile }.forEach { file ->
                val relative = prefix + file.relativeTo(base).invariantSeparatorsPath
                if (prefix.isNotEmpty() || included(part, relative)) out += relative to file
            }
        }
        collect(root(part), "")
        extraRoots(part).forEach { (name, dir) -> collect(dir, "$name/") }
        return out
    }

    suspend fun export(uri: Uri, parts: Set<BackupPart>): BackupResult = withContext(Dispatchers.IO) {
        if (isGameProcessRunning(appContext)) return@withContext BackupResult.Busy
        try {
            val output = appContext.contentResolver.openOutputStream(uri, "wt") ?: return@withContext BackupResult.Failed
            ZipOutputStream(output.buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(MANIFEST))
                zip.write("""{"app":"ametrine","version":1,"createdAt":${System.currentTimeMillis()}}""".toByteArray())
                zip.closeEntry()
                for (part in BackupPart.entries.filter { it in parts }) {
                    for ((relative, file) in filesOf(part)) {
                        zip.putNextEntry(ZipEntry("${part.id}/$relative").apply { time = file.lastModified() })
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
            BackupResult.Ok
        } catch (e: IOException) {
            Log.e(TAG, "Backup failed", e)
            BackupResult.Failed
        }
    }

    /** What the backup at [uri] holds; null if it is not one of ours. */
    suspend fun inspect(uri: Uri): BackupContents? = withContext(Dispatchers.IO) {
        try {
            val input = appContext.contentResolver.openInputStream(uri) ?: return@withContext null
            ZipInputStream(input.buffered()).use { zip ->
                var createdAt = 0L
                var manifest = false
                val files = HashMap<BackupPart, Int>()
                val bytes = HashMap<BackupPart, Long>()
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    if (entry.name == MANIFEST) {
                        manifest = true
                        val text = zip.readBytes().decodeToString()
                        createdAt = Regex("\"createdAt\":(\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                        continue
                    }
                    val part = partOf(entry.name) ?: continue
                    files[part] = (files[part] ?: 0) + 1
                    bytes[part] = (bytes[part] ?: 0L) + zip.countBytes()
                }
                if (!manifest) return@withContext null
                BackupContents(
                    createdAt,
                    BackupPart.entries.filter { it in files }.map { BackupPartInfo(it, files.getValue(it), bytes.getValue(it)) },
                )
            }
        } catch (e: IOException) {
            Log.e(TAG, "Reading the backup failed", e)
            null
        }
    }

    suspend fun restore(uri: Uri, parts: Set<BackupPart>): BackupResult = withContext(Dispatchers.IO) {
        if (isGameProcessRunning(appContext)) return@withContext BackupResult.Busy
        try {
            val input = appContext.contentResolver.openInputStream(uri) ?: return@withContext BackupResult.Failed
            val clearedCards = HashSet<String>()
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val part = partOf(entry.name)?.takeIf { it in parts } ?: continue
                    val relative = entry.name.substringAfter('/')
                    if (!isPadLayout(part, relative) && !included(part, relative)) continue
                    val target = targetOf(part, relative) ?: return@withContext BackupResult.Invalid
                    if (part == BackupPart.MemoryCards) {
                        // A card in the backup replaces the card of that name, whole.
                        val card = relative.substringBefore('/')
                        if (clearedCards.add(card)) File(core.memoryCardsDir, card).deleteRecursively()
                    }
                    target.parentFile?.mkdirs()
                    val temp = File(target.parentFile, target.name + ".restoring")
                    temp.outputStream().use { zip.copyTo(it) }
                    target.delete()
                    if (!temp.renameTo(target)) {
                        temp.delete()
                        return@withContext BackupResult.Failed
                    }
                }
            }
            settings.reload()
            records.refresh()
            memoryCards.refresh()
            BackupResult.Ok
        } catch (e: IOException) {
            Log.e(TAG, "Restore failed", e)
            BackupResult.Failed
        }
    }

    private fun partOf(entryName: String): BackupPart? {
        val id = entryName.substringBefore('/', "")
        return BackupPart.entries.firstOrNull { it.id == id }
    }

    /** Where [relative] in [part] goes on the device; null for a path that would leave the folder. */
    private fun targetOf(part: BackupPart, relative: String): File? {
        val base = if (isPadLayout(part, relative)) filesDir else root(part)
        val target = File(base, relative)
        return target.takeIf { it.canonicalPath.startsWith(base.canonicalPath + File.separator) }
    }

    /** The pad layouts are in the settings part, in a folder of their own. */
    private fun isPadLayout(part: BackupPart, relative: String) =
        part == BackupPart.Settings && relative.startsWith("padlayouts/")

    private fun ZipInputStream.countBytes(): Long {
        var total = 0L
        val buffer = ByteArray(8192)
        while (true) {
            val n = read(buffer)
            if (n < 0) return total
            total += n
        }
    }

    companion object {
        private const val TAG = "BackupRepository"
        private const val MANIFEST = "manifest.json"
    }
}
