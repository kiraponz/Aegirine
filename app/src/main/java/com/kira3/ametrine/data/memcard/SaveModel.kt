package com.kira3.ametrine.data.memcard

/** One file of a save, with the metadata a PS2 card keeps for it. Times are epoch milliseconds. */
class SaveFile(
    val name: String,
    val data: ByteArray,
    val created: Long,
    val modified: Long,
    val mode: Int = DEFAULT_FILE_MODE,
    val attr: Int = 0,
)

/** A save: a folder in the card's root and the files in it. */
class SaveData(
    val name: String,
    val files: List<SaveFile>,
    val created: Long,
    val modified: Long,
    val mode: Int = DEFAULT_DIR_MODE,
    val attr: Int = 0,
)

/** What the list of a card's saves shows. [kilobytes] is the space it takes on the card. */
class SaveInfo(
    val name: String,
    /** The game's title for the save, from its icon.sys; null if it has none. */
    val title: String?,
    val kilobytes: Int,
    val files: Int,
    val modified: Long,
)

enum class WriteResult { Ok, Exists, Full, Failed }

/** What a card is, as far as this app can read it. */
enum class CardState { Blank, Formatted, Unsupported }

/** The saves on a card, whether it is a card file or a folder. */
interface SaveStore {
    fun list(): List<SaveInfo>
    fun read(name: String): SaveData?

    /** Adds [save], replacing a save of the same name only if [replace]. */
    fun write(save: SaveData, replace: Boolean): WriteResult

    fun delete(name: String)

    /** Free space in kilobytes, or null when there is no limit. */
    fun freeKilobytes(): Int?
}

const val DEFAULT_FILE_MODE = 0x8497
const val DEFAULT_DIR_MODE = 0x8427

/** Whether [name] is the BIOS's own system folder, which is not a game's save. */
fun isSystemSave(name: String): Boolean = name.startsWith("BIDATA-")

/**
 * The title in a save's icon.sys: Shift-JIS text at 0xC0, with the line break at byte
 * [lineBreak] (its offset at 6) shown as a space. Null if the file is not an icon.sys.
 */
internal fun iconSysTitle(iconSys: ByteArray): String? {
    if (iconSys.size < 0xC0 + 4 || String(iconSys, 0, 4, Charsets.ISO_8859_1) != "PS2D") return null
    val lineBreak = (iconSys[6].toInt() and 0xFF) or ((iconSys[7].toInt() and 0xFF) shl 8)
    var end = 0xC0
    while (end < minOf(iconSys.size, 0xC0 + 68) && iconSys[end].toInt() != 0) end++
    val raw = iconSys.copyOfRange(0xC0, end)
    val sjis = charset("Shift_JIS")
    val title = if (lineBreak in 1 until raw.size) {
        String(raw, 0, lineBreak, sjis) + " " + String(raw, lineBreak, raw.size - lineBreak, sjis)
    } else {
        String(raw, sjis)
    }
    // Titles are written in full-width letters; NFKC makes them ordinary ones.
    return java.text.Normalizer.normalize(title, java.text.Normalizer.Form.NFKC).trim().ifEmpty { null }
}
