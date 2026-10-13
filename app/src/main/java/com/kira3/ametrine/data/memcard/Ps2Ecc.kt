package com.kira3.ametrine.data.memcard

/**
 * The error-correcting code a PS2 memory card keeps for each page: 3 bytes for each 128 bytes of
 * data (4 per page), then 4 unused bytes, filling the 16-byte spare area of a 528-byte page.
 * Ported from mymc (ps2mc_ecc.py, public domain), as the core's MemoryCardFile.cpp does.
 */
internal object Ps2Ecc {
    private val COLUMN_PARITY_MASK = intArrayOf(
        0, 7, 22, 17, 37, 34, 51, 52, 52, 51, 34, 37, 17, 22, 7, 0,
        67, 68, 85, 82, 102, 97, 112, 119, 119, 112, 97, 102, 82, 85, 68, 67,
        82, 85, 68, 67, 119, 112, 97, 102, 102, 97, 112, 119, 67, 68, 85, 82,
        17, 22, 7, 0, 52, 51, 34, 37, 37, 34, 51, 52, 0, 7, 22, 17,
        97, 102, 119, 112, 68, 67, 82, 85, 85, 82, 67, 68, 112, 119, 102, 97,
        34, 37, 52, 51, 7, 0, 17, 22, 22, 17, 0, 7, 51, 52, 37, 34,
        51, 52, 37, 34, 22, 17, 0, 7, 7, 0, 17, 22, 34, 37, 52, 51,
        112, 119, 102, 97, 85, 82, 67, 68, 68, 67, 82, 85, 97, 102, 119, 112,
        112, 119, 102, 97, 85, 82, 67, 68, 68, 67, 82, 85, 97, 102, 119, 112,
        51, 52, 37, 34, 22, 17, 0, 7, 7, 0, 17, 22, 34, 37, 52, 51,
        34, 37, 52, 51, 7, 0, 17, 22, 22, 17, 0, 7, 51, 52, 37, 34,
        97, 102, 119, 112, 68, 67, 82, 85, 85, 82, 67, 68, 112, 119, 102, 97,
        17, 22, 7, 0, 52, 51, 34, 37, 37, 34, 51, 52, 0, 7, 22, 17,
        82, 85, 68, 67, 119, 112, 97, 102, 102, 97, 112, 119, 67, 68, 85, 82,
        67, 68, 85, 82, 102, 97, 112, 119, 119, 112, 97, 102, 82, 85, 68, 67,
        0, 7, 22, 17, 37, 34, 51, 52, 52, 51, 34, 37, 17, 22, 7, 0,
    )

    /** The 24-bit code of the 128 bytes of [buf] at [offset], in the low three bytes. */
    fun chunk(buf: ByteArray, offset: Int): Int {
        var column = 0x77
        var line0 = 0x7F
        var line1 = 0x7F
        for (i in 0 until 128) {
            val b = buf[offset + i].toInt() and 0xFF
            column = column xor COLUMN_PARITY_MASK[b]
            if (Integer.bitCount(b) and 1 != 0) {
                line0 = line0 xor (i.inv() and 0xFF)
                line1 = line1 xor i
            }
        }
        // 7 bits each: the bit the core's code leaves in line0 is not in what the BIOS writes.
        return column or ((line0 and 0x7F) shl 8) or (line1 shl 16)
    }

    /** Writes the 16 spare bytes for the 512 bytes of [data] at [offset] to [out] at [outOffset]. */
    fun spare(data: ByteArray, offset: Int, out: ByteArray, outOffset: Int) {
        for (j in 0 until 4) {
            val code = chunk(data, offset + j * 128)
            out[outOffset + j * 3] = code.toByte()
            out[outOffset + j * 3 + 1] = (code shr 8).toByte()
            out[outOffset + j * 3 + 2] = (code shr 16).toByte()
        }
        for (k in 12 until 16) out[outOffset + k] = 0
    }
}
