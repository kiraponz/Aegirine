package com.kira3.ametrine.emucore

/** A filled save-state slot of the running game, or a rewind point. */
class SaveStateInfo(
    val slot: Int,
    /** When it was saved, in milliseconds since the epoch. */
    val savedAt: Long,
    /** The PNG of the frame it was saved on, if the state has one. */
    val screenshot: ByteArray?,
)

/** What the header of a save-state file says about it. */
class SaveStateHeader(
    /** The serial of the game it was saved from; empty for a game without one. */
    val serial: String,
    /** The PNG of the frame it was saved on, if it has one. */
    val screenshot: ByteArray?,
)
