package com.kira3.ametrine.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.provider.OpenableColumns
import com.kira3.ametrine.data.memcard.CardState
import com.kira3.ametrine.data.memcard.FolderCard
import com.kira3.ametrine.data.memcard.Ps2Card
import com.kira3.ametrine.data.memcard.SaveData
import com.kira3.ametrine.data.memcard.SaveInfo
import com.kira3.ametrine.data.memcard.SaveStore
import com.kira3.ametrine.data.memcard.WriteResult
import com.kira3.ametrine.data.memcard.iconSysIconName
import com.kira3.ametrine.data.memcard.isSystemSave
import com.kira3.ametrine.data.memcard.parseSaveIcon
import com.kira3.ametrine.data.memcard.render
import com.kira3.ametrine.data.memcard.serialOfSave
import com.kira3.ametrine.emucore.EmulatorCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** A memory card in the core's card folder: a file ([sizeMb] is its capacity) or a folder of saves. */
data class MemoryCard(
    val name: String,
    val isFolder: Boolean,
    /** Capacity of a PS2 file card in MB; null for a folder card and for an PS1-size or unknown file. */
    val sizeMb: Int?,
    val bytes: Long,
    val modified: Long,
)

/** A save that has an icon, drawn flat; [cardName] is the card it is on. */
class SaveIconChoice(val cardName: String, val saveName: String, val title: String?, val icon: Bitmap)

enum class MemoryCardResult { Ok, Exists, Invalid, Failed, Full, Busy }

/** What a card holds, for its page: [saves] is empty and [state] says why unless it is formatted. */
class SaveListing(
    val state: CardState,
    val saves: List<SaveInfo>,
    val freeKilobytes: Int?,
    val capacityKilobytes: Int?,
)

/**
 * The cards the core reads and writes in `<dataDir>/memcards`. The core picks a card by file name,
 * so these are plain files and folders that the app lists, creates, copies in and out, renames and
 * deletes.
 */
class MemoryCardRepository(
    context: Context,
    private val core: EmulatorCore,
) {
    private val appContext = context.applicationContext
    private val contentResolver = appContext.contentResolver
    private val changes = MutableStateFlow(0)

    val cards: Flow<List<MemoryCard>> = changes.map { list() }

    suspend fun list(): List<MemoryCard> = withContext(Dispatchers.IO) {
        core.memoryCardsDir.listFiles().orEmpty()
            .filter { hasCardExtension(it.name) }
            .mapNotNull(::read)
            .sortedBy { it.name.lowercase() }
    }

    /** Creates an empty card named [name] ([sizeMb] 8, 16, 32 or 64 for a file card). */
    suspend fun create(name: String, folder: Boolean, sizeMb: Int): MemoryCardResult = withContext(Dispatchers.IO) {
        val cardName = cardName(name) ?: return@withContext MemoryCardResult.Invalid
        if (File(core.memoryCardsDir, cardName).exists()) return@withContext MemoryCardResult.Exists
        core.initialize()
        core.memoryCardsDir.mkdirs()
        val ok = core.createMemoryCard(cardName, folder, sizeMb)
        changed()
        if (ok) MemoryCardResult.Ok else MemoryCardResult.Failed
    }

    suspend fun rename(card: MemoryCard, newName: String): MemoryCardResult = withContext(Dispatchers.IO) {
        if (gameRunning()) return@withContext MemoryCardResult.Busy
        val cardName = cardName(newName) ?: return@withContext MemoryCardResult.Invalid
        if (cardName == card.name) return@withContext MemoryCardResult.Ok
        val target = File(core.memoryCardsDir, cardName)
        if (target.exists()) return@withContext MemoryCardResult.Exists
        val ok = File(core.memoryCardsDir, card.name).renameTo(target)
        changed()
        if (ok) MemoryCardResult.Ok else MemoryCardResult.Failed
    }

    suspend fun delete(card: MemoryCard): MemoryCardResult = withContext(Dispatchers.IO) {
        if (gameRunning()) return@withContext MemoryCardResult.Busy
        File(core.memoryCardsDir, card.name).deleteRecursively()
        changed()
        MemoryCardResult.Ok
    }

    /**
     * Erases every save on [card] and leaves it formatted. A file card is rebuilt as a copy of the
     * same size, which replaces the card only when it is done.
     */
    suspend fun format(card: MemoryCard): MemoryCardResult = withContext(Dispatchers.IO) {
        if (gameRunning()) return@withContext MemoryCardResult.Busy
        val original = File(core.memoryCardsDir, card.name)
        runCatching {
            if (card.isFolder) {
                original.listFiles().orEmpty()
                    .filter { it.isDirectory && !it.name.startsWith("_pcsx2_") }
                    .forEach { it.deleteRecursively() }
                MemoryCardResult.Ok
            } else {
                val temp = File(core.memoryCardsDir, "${card.name}$TEMP_SUFFIX")
                try {
                    val erased = ByteArray(1 shl 16) { 0xFF.toByte() }
                    temp.outputStream().use { out ->
                        var left = original.length()
                        while (left > 0) {
                            val n = minOf(left, erased.size.toLong()).toInt()
                            out.write(erased, 0, n)
                            left -= n
                        }
                    }
                    val formatted = Ps2Card(temp, writable = true).use { store ->
                        (store.state == CardState.Blank).also { if (it) store.formatErased() }
                    }
                    if (formatted && temp.renameTo(original)) MemoryCardResult.Ok else MemoryCardResult.Failed
                } finally {
                    temp.delete()
                }
            }
        }.getOrDefault(MemoryCardResult.Failed).also { changed() }
    }

    /** The file name to offer when exporting [card]: a folder card is saved as a zip. */
    fun exportName(card: MemoryCard): String = if (card.isFolder) "${card.name}.zip" else card.name

    /** Copies [card] to [uri]. */
    suspend fun export(card: MemoryCard, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val source = File(core.memoryCardsDir, card.name)
        runCatching {
            val output = contentResolver.openOutputStream(uri, "wt") ?: return@runCatching false
            output.use {
                if (card.isFolder) zip(source, it) else source.inputStream().use { input -> input.copyTo(it) }
            }
            true
        }.getOrDefault(false)
    }

    /**
     * Copies the card file at [uri] (or, for a zip, the folder card it holds) into the card
     * folder under its own name, or the next free one. Invalid if it is not a memory card.
     */
    suspend fun import(uri: Uri): MemoryCardResult = withContext(Dispatchers.IO) {
        val fileName = displayName(uri)?.let(::sanitize) ?: return@withContext MemoryCardResult.Invalid
        core.memoryCardsDir.mkdirs()
        val result = runCatching {
            if (fileName.endsWith(".zip", ignoreCase = true)) importFolder(uri, fileName.dropLast(4))
            else importFile(uri, fileName)
        }.getOrDefault(MemoryCardResult.Failed)
        changed()
        result
    }

    private fun importFile(uri: Uri, fileName: String): MemoryCardResult {
        val name = cardName(fileName) ?: return MemoryCardResult.Invalid
        val temp = File(core.memoryCardsDir, "$name$TEMP_SUFFIX").apply { delete() }
        val input = contentResolver.openInputStream(uri) ?: return MemoryCardResult.Failed
        input.use { temp.outputStream().use { out -> it.copyTo(out) } }
        if (!looksLikeCard(temp)) {
            temp.delete()
            return MemoryCardResult.Invalid
        }
        return if (temp.renameTo(freeFile(name))) MemoryCardResult.Ok else MemoryCardResult.Failed
    }

    private fun importFolder(uri: Uri, baseName: String): MemoryCardResult {
        val name = cardName(baseName) ?: return MemoryCardResult.Invalid
        val temp = File(core.memoryCardsDir, "$name$TEMP_SUFFIX").apply { deleteRecursively() }
        temp.mkdirs()
        val input = contentResolver.openInputStream(uri) ?: return MemoryCardResult.Failed
        input.use { unzip(it, temp) }
        if (!File(temp, FOLDER_MARKER).isFile) {
            temp.deleteRecursively()
            return MemoryCardResult.Invalid
        }
        return if (temp.renameTo(freeFile(name))) MemoryCardResult.Ok else MemoryCardResult.Failed
    }

    // ---- Saves on a card ----

    /** What [card] holds. Null if it cannot be read. */
    suspend fun listSaves(card: MemoryCard): SaveListing? = withContext(Dispatchers.IO) {
        runCatching {
            if (card.isFolder) {
                val store = FolderCard(File(core.memoryCardsDir, card.name))
                SaveListing(CardState.Formatted, store.list(), null, null)
            } else {
                Ps2Card(File(core.memoryCardsDir, card.name)).use { c ->
                    SaveListing(c.state, c.list(), c.freeKilobytes(), c.capacityKilobytes().takeIf { it > 0 })
                }
            }
        }.getOrNull()
    }

    /**
     * The icons of the saves on every card, drawn [size] pixels wide. With a [serial], only the
     * saves of that game. Saves without a readable icon are left out.
     */
    suspend fun saveIcons(size: Int, fill: Float, serial: String? = null): List<SaveIconChoice> = withContext(Dispatchers.IO) {
        list().flatMap { card ->
            runCatching {
                withStore(card, writable = false) { store ->
                    store.list()
                        .filter { !isSystemSave(it.name) && (serial == null || serialOfSave(it.name) == serial) }
                        .mapNotNull { info ->
                            val bitmap = store.read(info.name)?.let { iconOf(it, size, fill) } ?: return@mapNotNull null
                            SaveIconChoice(card.name, info.name, info.title, bitmap)
                        }
                }
            }.onFailure { Log.w(TAG, "Reading the saves of ${card.name} failed", it) }.getOrNull().orEmpty()
        }
    }

    /** The icon of one save, or null if it cannot be read. */
    suspend fun saveIcon(cardName: String, saveName: String, size: Int, fill: Float): Bitmap? = withContext(Dispatchers.IO) {
        val card = list().firstOrNull { it.name == cardName } ?: return@withContext null
        runCatching {
            withStore(card, writable = false) { store -> store.read(saveName)?.let { iconOf(it, size, fill) } }
        }.getOrNull()
    }

    private fun iconOf(save: SaveData, size: Int, fill: Float): Bitmap? {
        val iconSys = save.files.firstOrNull { it.name.equals("icon.sys", ignoreCase = true) } ?: return null
        val name = iconSysIconName(iconSys.data) ?: return null
        val file = save.files.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return null
        val model = parseSaveIcon(file.data)
        if (model == null) Log.w(TAG, "Could not read the icon $name of ${save.name}")
        return model?.render(size, fill)
    }

    /** The names among [names] that [target] already has a save for. */
    suspend fun conflicts(target: MemoryCard, names: List<String>): List<String> {
        val existing = listSaves(target)?.saves?.map { it.name }.orEmpty().toSet()
        return names.filter { it in existing }
    }

    /**
     * Copies the saves [names] from [source] to [target] (replacing saves of the same name), and
     * removes them from [source] too if [move]. Nothing changes on a card unless all of it worked.
     */
    suspend fun transfer(source: MemoryCard, target: MemoryCard, names: List<String>, move: Boolean): MemoryCardResult =
        withContext(Dispatchers.IO) {
            if (gameRunning()) return@withContext MemoryCardResult.Busy
            if (source.name == target.name) return@withContext MemoryCardResult.Invalid
            runCatching {
                val saves = withStore(source, writable = false) { store ->
                    names.map { store.read(it) ?: return@withStore null }
                } ?: return@runCatching MemoryCardResult.Failed
                val written = withCommittedStore(target, writable = true) { store ->
                    for (save in saves) {
                        when (store.write(save, replace = true)) {
                            WriteResult.Ok -> Unit
                            WriteResult.Full -> return@withCommittedStore MemoryCardResult.Full to false
                            else -> return@withCommittedStore MemoryCardResult.Failed to false
                        }
                    }
                    MemoryCardResult.Ok to true
                } ?: return@runCatching MemoryCardResult.Failed
                if (written == MemoryCardResult.Ok && move) {
                    withCommittedStore(source, writable = true) { store ->
                        names.forEach(store::delete)
                        Unit to true
                    }
                }
                written
            }.getOrDefault(MemoryCardResult.Failed).also { changed() }
        }

    suspend fun deleteSaves(card: MemoryCard, names: List<String>): MemoryCardResult = withContext(Dispatchers.IO) {
        if (gameRunning()) return@withContext MemoryCardResult.Busy
        runCatching {
            withCommittedStore(card, writable = true) { store ->
                names.forEach(store::delete)
                Unit to true
            }
            MemoryCardResult.Ok
        }.getOrDefault(MemoryCardResult.Failed).also { changed() }
    }

    /**
     * Runs [block] on the saves of [card]. For a card file opened [writable] that is a copy of it,
     * which replaces the card only if the block says to keep its changes (the second of its pair).
     */
    private fun <T> withStore(card: MemoryCard, writable: Boolean, block: (SaveStore) -> T?): T? =
        withCommittedStore(card, writable) { store -> block(store)?.let { it to true } }

    private fun <T> withCommittedStore(card: MemoryCard, writable: Boolean, block: (SaveStore) -> Pair<T, Boolean>?): T? {
        val original = File(core.memoryCardsDir, card.name)
        if (card.isFolder) return block(FolderCard(original))?.first
        if (!writable) return Ps2Card(original).use { block(it)?.first }
        val temp = File(core.memoryCardsDir, "${card.name}$TEMP_SUFFIX")
        original.copyTo(temp, overwrite = true)
        var keep = false
        val result = try {
            Ps2Card(temp, writable = true).use { store ->
                block(store)?.also { keep = it.second }?.first
            }
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
        if (keep && temp.renameTo(original)) return result
        temp.delete()
        return if (keep) null else result
    }

    /** Whether a game is running in the emulation process, which holds the cards open. */
    private fun gameRunning(): Boolean = isGameProcessRunning(appContext)

    /** Lists the cards again, after something else changed the folder. */
    fun refresh() = changed()

    private fun changed() {
        changes.value += 1
    }

    private fun read(file: File): MemoryCard? {
        if (file.isDirectory) {
            if (!File(file, FOLDER_MARKER).isFile) return null
            return MemoryCard(file.name, true, null, file.walkTopDown().filter { it.isFile }.sumOf { it.length() }, file.lastModified())
        }
        if (!file.isFile || file.length() < PS1_SIZE) return null
        return MemoryCard(file.name, false, ps2SizeMb(file.length()), file.length(), file.lastModified())
    }

    /** Name with a card extension (".ps2" if it has none), or null if it is empty or unusable. */
    private fun cardName(name: String): String? {
        val clean = sanitize(name) ?: return null
        return if (hasCardExtension(clean)) clean else "$clean.ps2"
    }

    private fun sanitize(name: String): String? =
        name.replace('/', '_').replace('\\', '_').trim().takeIf { it.isNotEmpty() && it != "." && it != ".." && !it.startsWith(".") }

    private fun hasCardExtension(name: String): Boolean =
        CARD_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) } && !name.endsWith(TEMP_SUFFIX)

    /** [name] in the card folder, or "base (2).ext" and so on if it is taken. */
    private fun freeFile(name: String): File {
        var file = File(core.memoryCardsDir, name)
        if (!file.exists()) return file
        val base = name.substringBeforeLast('.')
        val ext = name.substring(base.length)
        var n = 2
        while (true) {
            file = File(core.memoryCardsDir, "$base ($n)$ext")
            if (!file.exists()) return file
            n++
        }
    }

    /** A PS2 card of a known size that is formatted or blank, or a PS1-size card. */
    private fun looksLikeCard(file: File): Boolean {
        val size = file.length()
        if (size == PS1_SIZE) return true
        if (ps2SizeMb(size) == null) return false
        val head = ByteArray(FORMAT_MAGIC.size)
        file.inputStream().use { if (it.read(head) != head.size) return false }
        return head.contentEquals(FORMAT_MAGIC) || head.all { it == 0xFF.toByte() }
    }

    private fun zip(folder: File, out: java.io.OutputStream) {
        ZipOutputStream(out).use { zip ->
            folder.walkTopDown().filter { it.isFile }.forEach { file ->
                zip.putNextEntry(ZipEntry(file.relativeTo(folder).path.replace(File.separatorChar, '/')))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun unzip(input: java.io.InputStream, target: File) {
        val root = target.canonicalFile
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val file = File(target, entry.name).canonicalFile
                // An entry must stay inside the card folder.
                if (!file.path.startsWith(root.path + File.separator)) continue
                if (entry.isDirectory) {
                    file.mkdirs()
                } else {
                    file.parentFile?.mkdirs()
                    file.outputStream().use { zip.copyTo(it) }
                }
            }
        }
    }

    private fun displayName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    private companion object {
        val CARD_EXTENSIONS = listOf(".ps2", ".mcr", ".mcd", ".bin", ".mc2")
        const val TAG = "MemoryCardRepository"
        const val TEMP_SUFFIX = ".tmp"
        const val FOLDER_MARKER = "_pcsx2_superblock"
        const val PS1_SIZE = 131072L
        const val MB_SIZE = 1024L * 528 * 2
        val FORMAT_MAGIC = "Sony PS2 Memory Card Format ".toByteArray()

        /** 8, 16, 32 or 64 for a PS2 card's file size (with or without ECC), else null. */
        fun ps2SizeMb(bytes: Long): Int? = listOf(8, 16, 32, 64).firstOrNull {
            bytes == it * MB_SIZE || bytes == it * 1024L * 1024
        }
    }
}
