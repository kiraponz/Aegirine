package com.kira3.ametrine.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import android.provider.OpenableColumns
import com.kira3.ametrine.data.memcard.CardState
import com.kira3.ametrine.data.memcard.Ps1Card
import com.kira3.ametrine.data.memcard.SaveInfo
import com.kira3.ametrine.data.memcard.WriteResult
import com.kira3.ametrine.data.memcard.serialOfSave
import com.kira3.ametrine.emucore.EmulatorCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

/** A PS1 memory card file (128 KB) in the core's card folder. */
data class MemoryCard(
    val name: String,
    val bytes: Long,
    val modified: Long,
)

/** A save that has an icon; [cardName] is the card it is on. */
class SaveIconChoice(val cardName: String, val saveName: String, val title: String?, val icon: Bitmap)

enum class MemoryCardResult { Ok, Exists, Invalid, Failed, Full, Busy }

/** What a card holds, for its page: [saves] is empty and [state] says why unless it is formatted. */
class SaveListing(
    val state: CardState,
    val saves: List<SaveInfo>,
    val freeKilobytes: Int,
    val capacityKilobytes: Int,
)

/**
 * The cards the core reads and writes in `<dataDir>/memcards`. The core picks a card by file name
 * (a game's own card is named after its title, the shared one is set in the settings), so these
 * are plain files that the app lists, creates, copies in and out, renames and deletes.
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

    /** Creates an empty, formatted card named [name]. */
    suspend fun create(name: String): MemoryCardResult = withContext(Dispatchers.IO) {
        val cardName = cardName(name) ?: return@withContext MemoryCardResult.Invalid
        val file = File(core.memoryCardsDir, cardName)
        if (file.exists()) return@withContext MemoryCardResult.Exists
        core.memoryCardsDir.mkdirs()
        val ok = runCatching { Ps1Card.formatted().save(file) }.isSuccess
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
        File(core.memoryCardsDir, card.name).delete()
        changed()
        MemoryCardResult.Ok
    }

    /** Erases every save on [card] and leaves it formatted. */
    suspend fun format(card: MemoryCard): MemoryCardResult = withContext(Dispatchers.IO) {
        if (gameRunning()) return@withContext MemoryCardResult.Busy
        val ok = runCatching { replace(File(core.memoryCardsDir, card.name), Ps1Card.formatted()) }.getOrDefault(false)
        changed()
        if (ok) MemoryCardResult.Ok else MemoryCardResult.Failed
    }

    fun exportName(card: MemoryCard): String = card.name

    /** Copies [card] to [uri]. */
    suspend fun export(card: MemoryCard, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val source = File(core.memoryCardsDir, card.name)
        runCatching {
            val output = contentResolver.openOutputStream(uri, "wt") ?: return@runCatching false
            output.use { source.inputStream().use { input -> input.copyTo(it) } }
            true
        }.getOrDefault(false)
    }

    /**
     * Copies the card file at [uri] into the card folder under its own name, or the next free
     * one. Invalid if it is not a 128 KB PS1 card.
     */
    suspend fun import(uri: Uri): MemoryCardResult = withContext(Dispatchers.IO) {
        val fileName = displayName(uri)?.let(::sanitize) ?: return@withContext MemoryCardResult.Invalid
        core.memoryCardsDir.mkdirs()
        val result = runCatching { importFile(uri, fileName) }.getOrDefault(MemoryCardResult.Failed)
        changed()
        result
    }

    private fun importFile(uri: Uri, fileName: String): MemoryCardResult {
        val name = cardName(fileName) ?: return MemoryCardResult.Invalid
        val temp = File(core.memoryCardsDir, "$name$TEMP_SUFFIX").apply { delete() }
        val input = contentResolver.openInputStream(uri) ?: return MemoryCardResult.Failed
        input.use { temp.outputStream().use { out -> it.copyTo(out) } }
        if (Ps1Card.open(temp)?.state != CardState.Formatted) {
            temp.delete()
            return MemoryCardResult.Invalid
        }
        return if (temp.renameTo(freeFile(name))) MemoryCardResult.Ok else MemoryCardResult.Failed
    }

    // ---- Saves on a card ----

    /** What [card] holds. Null if it cannot be read. */
    suspend fun listSaves(card: MemoryCard): SaveListing? = withContext(Dispatchers.IO) {
        runCatching {
            val c = open(card) ?: return@runCatching null
            SaveListing(c.state, c.list(), c.freeKilobytes(), CAPACITY_KILOBYTES)
        }.getOrNull()
    }

    /**
     * The icons of the saves on every card, drawn [size] pixels wide with the icon filling [fill]
     * of it. With a [serial], only the saves of that game. Saves without an icon are left out.
     */
    suspend fun saveIcons(size: Int, fill: Float, serial: String? = null): List<SaveIconChoice> = withContext(Dispatchers.IO) {
        list().flatMap { card ->
            runCatching {
                val c = open(card) ?: return@runCatching emptyList()
                c.list()
                    .filter { serial == null || serialOfSave(it.name) == serial }
                    .mapNotNull { info ->
                        val pixels = c.icon(info.name) ?: return@mapNotNull null
                        SaveIconChoice(card.name, info.name, info.title, iconBitmap(pixels, size, fill))
                    }
            }.onFailure { Log.w(TAG, "Reading the saves of ${card.name} failed", it) }.getOrNull().orEmpty()
        }
    }

    /** The icon of one save, or null if it cannot be read. */
    suspend fun saveIcon(cardName: String, saveName: String, size: Int, fill: Float): Bitmap? = withContext(Dispatchers.IO) {
        val card = list().firstOrNull { it.name == cardName } ?: return@withContext null
        runCatching { open(card)?.icon(saveName)?.let { iconBitmap(it, size, fill) } }.getOrNull()
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
                val from = open(source) ?: return@runCatching MemoryCardResult.Failed
                val to = open(target) ?: return@runCatching MemoryCardResult.Failed
                val saves = names.map { from.read(it) ?: return@runCatching MemoryCardResult.Failed }
                for (save in saves) {
                    when (to.write(save, replace = true)) {
                        WriteResult.Ok -> Unit
                        WriteResult.Full -> return@runCatching MemoryCardResult.Full
                        else -> return@runCatching MemoryCardResult.Failed
                    }
                }
                if (!replace(File(core.memoryCardsDir, target.name), to)) return@runCatching MemoryCardResult.Failed
                if (move) {
                    names.forEach(from::delete)
                    replace(File(core.memoryCardsDir, source.name), from)
                }
                MemoryCardResult.Ok
            }.getOrDefault(MemoryCardResult.Failed).also { changed() }
        }

    suspend fun deleteSaves(card: MemoryCard, names: List<String>): MemoryCardResult = withContext(Dispatchers.IO) {
        if (gameRunning()) return@withContext MemoryCardResult.Busy
        runCatching {
            val c = open(card) ?: return@runCatching MemoryCardResult.Failed
            names.forEach(c::delete)
            if (replace(File(core.memoryCardsDir, card.name), c)) MemoryCardResult.Ok else MemoryCardResult.Failed
        }.getOrDefault(MemoryCardResult.Failed).also { changed() }
    }

    private fun open(card: MemoryCard): Ps1Card? = Ps1Card.open(File(core.memoryCardsDir, card.name))

    /** Writes [card] over [file] through a temporary copy, so a failure leaves the old card whole. */
    private fun replace(file: File, card: Ps1Card): Boolean {
        val temp = File(file.parentFile, "${file.name}$TEMP_SUFFIX")
        card.save(temp)
        if (temp.renameTo(file)) return true
        temp.delete()
        return false
    }

    /** Whether a game is running in the emulation process, which holds the cards open. */
    private fun gameRunning(): Boolean = isGameProcessRunning(appContext)

    /** Lists the cards again, after something else changed the folder. */
    fun refresh() = changed()

    private fun changed() {
        changes.value += 1
    }

    private fun read(file: File): MemoryCard? {
        if (!file.isFile || file.length() != Ps1Card.SIZE.toLong()) return null
        return MemoryCard(file.name, file.length(), file.lastModified())
    }

    /** Name with a card extension (".mcd" if it has none), or null if it is empty or unusable. */
    private fun cardName(name: String): String? {
        val clean = sanitize(name) ?: return null
        return if (hasCardExtension(clean)) clean else "$clean.mcd"
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

    /** The 16×16 [pixels] scaled up without smoothing, centered in a [size] square. */
    private fun iconBitmap(pixels: IntArray, size: Int, fill: Float): Bitmap {
        val icon = Bitmap.createBitmap(pixels, 16, 16, Bitmap.Config.ARGB_8888)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val side = (size * fill).toInt().coerceIn(1, size)
        val offset = (size - side) / 2
        Canvas(out).drawBitmap(icon, null, Rect(offset, offset, offset + side, offset + side), Paint().apply { isFilterBitmap = false })
        icon.recycle()
        return out
    }

    private fun displayName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    private companion object {
        /** The extensions of raw PS1 card images. */
        val CARD_EXTENSIONS = listOf(".mcd", ".mcr", ".mc", ".srm", ".mem", ".vm1")
        const val TAG = "MemoryCardRepository"
        const val TEMP_SUFFIX = ".tmp"
        const val CAPACITY_KILOBYTES = 15 * 8
    }
}
