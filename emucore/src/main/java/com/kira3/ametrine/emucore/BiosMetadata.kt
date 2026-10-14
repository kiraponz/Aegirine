package com.kira3.ametrine.emucore

/** What the core knows about a PS1 BIOS image. */
data class BiosMetadata(
    val region: BiosRegion,
    /** The core's name for the image, e.g. "SCPH-7000, 7500, 9000 (v4.0 08-18-97 J)", or "Unknown BIOS". */
    val description: String,
)

/** The console region a BIOS is for (the core's ConsoleRegion). */
enum class BiosRegion {
    Japan,
    Usa,
    Europe,

    /** An image the core's list does not know. It can still boot. */
    Unknown,
}
