package com.kira3.scphx.emucore

/** A filled save-state slot of the running game. */
class SaveStateInfo(
    val slot: Int,
    /** When it was saved, in milliseconds since the epoch. */
    val savedAt: Long,
    /** The PNG of the frame it was saved on, if the state has one. */
    val screenshot: ByteArray?,
)
