package com.kira3.ametrine.data.memcard

/**
 * A save as it is stored on a PS1 card: its file name in the card's directory and the blocks it
 * fills (8 KB each, the first holding its title and icon).
 */
class SaveData(
    val name: String,
    val data: ByteArray,
)

/** What the list of a card's saves shows. [kilobytes] is the space it takes on the card. */
class SaveInfo(
    val name: String,
    /** The game's title for the save; null if it has none. */
    val title: String?,
    val kilobytes: Int,
)

enum class WriteResult { Ok, Exists, Full, Failed }

/** What a card is, as far as this app can read it. */
enum class CardState { Formatted, Unsupported }

/** The saves on a card. */
interface SaveStore {
    fun list(): List<SaveInfo>
    fun read(name: String): SaveData?

    /** Adds [save], replacing a save of the same name only if [replace]. */
    fun write(save: SaveData, replace: Boolean): WriteResult

    fun delete(name: String)

    /** Free space in kilobytes. */
    fun freeKilobytes(): Int
}

/** The serial of the game a save belongs to ("SLPS-03430" for "BISLPS-03430FF1"), from its name. */
fun serialOfSave(name: String): String? =
    SAVE_SERIAL.find(name)?.let { "${it.groupValues[1]}-${it.groupValues[2]}" }

private val SAVE_SERIAL = Regex("^B.([A-Z]{4})[-_](\\d{5})")
