package com.kira3.scphx.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A game image found in the game folder, cached so the library opens without a scan. */
@Entity(tableName = "games")
data class GameEntity(
    /** The SAF document URI. */
    @PrimaryKey val uri: String,
    val fileName: String,
    val sizeBytes: Long,
    val lastModified: Long,
    /** E.g. "SLUS-20312". Null until read, and for images the core can't read it from. */
    val serial: String? = null,
    /** False until the core has tried to read [serial]. */
    val serialRead: Boolean = false,
)
