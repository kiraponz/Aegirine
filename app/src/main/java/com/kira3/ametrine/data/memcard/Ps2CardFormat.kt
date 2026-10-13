package com.kira3.ametrine.data.memcard

/** The superblock of a freshly formatted card, as the PS2 BIOS writes it. */
internal object Ps2CardFormat {
    private const val MAGIC = "Sony PS2 Memory Card Format "

    /**
     * The superblock for a card of [clusters] clusters (8192 for 8 MB), padded with ones to [length]
     * bytes (512 for the page on a card, 8192 for the file a folder card keeps).
     */
    fun superblock(clusters: Int, length: Int): ByteArray {
        val allocOffset = clusters / 256 + 9
        val allocEnd = clusters - 16 - allocOffset
        val sb = ByteArray(length) { 0xFF.toByte() }
        MAGIC.toByteArray(Charsets.ISO_8859_1).copyInto(sb, 0)
        for (i in MAGIC.length until 0x28) sb[i] = 0
        "1.2.0.0".toByteArray(Charsets.ISO_8859_1).copyInto(sb, MAGIC.length)
        putShort(sb, 0x28, 512)
        putShort(sb, 0x2A, 2)
        putShort(sb, 0x2C, 16)
        putShort(sb, 0x2E, 0xFF00)
        putInt(sb, 0x30, clusters)
        putInt(sb, 0x34, allocOffset)
        putInt(sb, 0x38, allocEnd)
        putInt(sb, 0x3C, 0)
        putInt(sb, 0x40, clusters / 8 - 1)
        putInt(sb, 0x44, clusters / 8 - 2)
        for (i in 0x48 until 0x50) sb[i] = 0
        putInt(sb, 0x50, 8)
        for (i in 1 until 32) putInt(sb, 0x50 + i * 4, 0)
        for (i in 0 until 32) putInt(sb, 0xD0 + i * 4, -1)
        sb[0x150] = 2
        sb[0x151] = 0x2B
        putShort(sb, 0x152, 0)
        putInt(sb, 0x154, 1024)
        putInt(sb, 0x158, 256)
        putInt(sb, 0x15C, 8)
        putInt(sb, 0x160, -1)
        for (i in 0x164 until 0x170) sb[i] = 0
        putInt(sb, 0x170, (allocEnd / 1000) * 1000 + 1)
        for (i in 0x174 until 0x17C) sb[i] = 0
        putInt(sb, 0x17C, -1)
        return sb
    }

    private fun putShort(b: ByteArray, o: Int, v: Int) {
        b[o] = v.toByte()
        b[o + 1] = (v shr 8).toByte()
    }

    private fun putInt(b: ByteArray, o: Int, v: Int) {
        putShort(b, o, v)
        putShort(b, o + 2, v shr 16)
    }
}
