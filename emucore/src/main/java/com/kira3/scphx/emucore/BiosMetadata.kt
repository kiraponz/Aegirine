package com.kira3.scphx.emucore

/** What the core reads out of a BIOS image. */
data class BiosMetadata(
    val versionMajor: Int,
    val versionMinor: Int,
    val region: BiosRegion,
    val description: String,
    val zone: String,
) {
    /** "v2.30" form. */
    val versionString: String get() = "v%d.%02d".format(versionMajor, versionMinor)
}

/** ROMVER region codes, as BiosTools.cpp reports them. */
enum class BiosRegion(internal val code: Int) {
    Japan(0),
    Usa(1),
    Europe(2),
    Asia(4),
    China(6),
    T10k(8),
    Test(9),
    Free(10),
    Unknown(-1);

    internal companion object {
        fun fromCode(code: Int): BiosRegion = entries.firstOrNull { it.code == code } ?: Unknown
    }
}
