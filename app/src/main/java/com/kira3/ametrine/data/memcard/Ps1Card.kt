package com.kira3.ametrine.data.memcard

import java.io.File
import java.text.Normalizer

/**
 * A PS1 memory card image: 128 KB in 16 blocks of 64 frames. Block 0 is the header and the
 * directory (one frame for each of the other 15 blocks); a save takes one or more blocks,
 * chained through the directory, and its first block starts with its title and icon.
 *
 * The layout follows the core's MemoryCardImage, so a card written here reads the same there.
 * Changes stay in memory until [save].
 */
class Ps1Card private constructor(private val image: ByteArray) : SaveStore {

    val state: CardState
        get() = if (image[0] == 'M'.code.toByte() && image[1] == 'C'.code.toByte()) CardState.Formatted else CardState.Unsupported

    override fun list(): List<SaveInfo> = entries().map { entry ->
        SaveInfo(entry.name, title(entry.firstBlock), entry.blocks.size * BLOCK_SIZE / 1024)
    }

    override fun read(name: String): SaveData? {
        val entry = entries().firstOrNull { it.name == name } ?: return null
        val data = ByteArray(entry.blocks.size * BLOCK_SIZE)
        entry.blocks.forEachIndexed { i, block -> image.copyInto(data, i * BLOCK_SIZE, block * BLOCK_SIZE, (block + 1) * BLOCK_SIZE) }
        return SaveData(entry.name, data)
    }

    override fun write(save: SaveData, replace: Boolean): WriteResult {
        if (state != CardState.Formatted || save.data.isEmpty()) return WriteResult.Failed
        val nameBytes = save.name.toByteArray(Charsets.US_ASCII)
        if (nameBytes.isEmpty() || nameBytes.size > FILE_NAME_LENGTH) return WriteResult.Failed
        val existing = entries().firstOrNull { it.name == save.name }
        if (existing != null && !replace) return WriteResult.Exists

        val needed = (save.data.size + BLOCK_SIZE - 1) / BLOCK_SIZE
        val free = freeBlocks()
        if (free.size + (existing?.blocks?.size ?: 0) < needed) return WriteResult.Full
        existing?.let { delete(it.name) }

        val blocks = freeBlocks().take(needed)
        blocks.forEachIndexed { i, block ->
            val dir = frameOffset(0, block)
            image.fill(0, dir, dir + FRAME_SIZE)
            when {
                i == 0 -> {
                    putU32(dir, STATE_FIRST)
                    putU32(dir + 4, save.data.size)
                    nameBytes.copyInto(image, dir + 0x0A)
                }
                i == blocks.size - 1 -> putU32(dir, STATE_LAST)
                else -> putU32(dir, STATE_MIDDLE)
            }
            putU16(dir + 8, if (i < blocks.size - 1) blocks[i + 1] - 1 else NO_NEXT)
            updateChecksum(dir)

            val start = i * BLOCK_SIZE
            val length = minOf(BLOCK_SIZE, save.data.size - start)
            save.data.copyInto(image, block * BLOCK_SIZE, start, start + length)
            image.fill(0, block * BLOCK_SIZE + length, (block + 1) * BLOCK_SIZE)
        }
        return WriteResult.Ok
    }

    /** Frees the save's blocks, as the core does when it deletes a file without clearing them. */
    override fun delete(name: String) {
        val entry = entries().firstOrNull { it.name == name } ?: return
        entry.blocks.forEachIndexed { i, block ->
            val dir = frameOffset(0, block)
            putU32(
                dir,
                when (i) {
                    0 -> STATE_DELETED_FIRST
                    entry.blocks.size - 1 -> STATE_DELETED_LAST
                    else -> STATE_DELETED_MIDDLE
                },
            )
            putU16(dir + 8, NO_NEXT)
            updateChecksum(dir)
        }
    }

    override fun freeKilobytes(): Int = freeBlocks().size * BLOCK_SIZE / 1024

    /** The first icon frame of the save starting at [name], as 16×16 ARGB pixels; null without one. */
    fun icon(name: String): IntArray? {
        val entry = entries().firstOrNull { it.name == name } ?: return null
        return iconOf(image, entry.firstBlock * BLOCK_SIZE)
    }

    fun save(file: File) = file.writeBytes(image)

    private class Entry(val name: String, val firstBlock: Int, val blocks: List<Int>)

    private fun entries(): List<Entry> {
        if (state != CardState.Formatted) return emptyList()
        val result = ArrayList<Entry>()
        for (block in 1 until BLOCKS) {
            val dir = frameOffset(0, block)
            if (u32(dir) != STATE_FIRST) continue
            var nameEnd = dir + 0x0A
            while (nameEnd < dir + 0x0A + FILE_NAME_LENGTH && image[nameEnd].toInt() != 0) nameEnd++
            val name = String(image, dir + 0x0A, nameEnd - dir - 0x0A, Charsets.US_ASCII)

            // Follow the chain; a broken one (a loop or a link off the card) is not a save.
            val blocks = arrayListOf(block)
            var next = u16(dir + 8)
            while (next != NO_NEXT) {
                val b = next + 1
                if (b !in 1 until BLOCKS || b in blocks) break
                blocks += b
                next = u16(frameOffset(0, b) + 8)
            }
            if (next != NO_NEXT) continue
            result += Entry(name, block, blocks)
        }
        return result
    }

    private fun freeBlocks(): List<Int> =
        (1 until BLOCKS).filter { u32(frameOffset(0, it)) in STATE_FREE..STATE_DELETED_LAST }

    private fun title(block: Int): String? {
        val start = block * BLOCK_SIZE
        if (image[start] != 'S'.code.toByte() || image[start + 1] != 'C'.code.toByte()) return null
        var end = start + 4
        while (end < start + 4 + TITLE_LENGTH && image[end].toInt() != 0) end++
        val raw = String(image, start + 4, end - start - 4, charset("Shift_JIS"))
        // Titles are written in full-width letters; NFKC makes them ordinary ones.
        return Normalizer.normalize(raw, Normalizer.Form.NFKC).trim().ifEmpty { null }
    }

    private fun frameOffset(block: Int, frame: Int) = block * BLOCK_SIZE + frame * FRAME_SIZE

    private fun u16(at: Int) = (image[at].toInt() and 0xFF) or ((image[at + 1].toInt() and 0xFF) shl 8)

    private fun u32(at: Int) = u16(at) or (u16(at + 2) shl 16)

    private fun putU16(at: Int, value: Int) {
        image[at] = value.toByte()
        image[at + 1] = (value shr 8).toByte()
    }

    private fun putU32(at: Int, value: Int) {
        putU16(at, value)
        putU16(at + 2, value shr 16)
    }

    private fun updateChecksum(frame: Int) {
        var sum = 0
        for (i in 0 until FRAME_SIZE - 1) sum = sum xor image[frame + i].toInt()
        image[frame + FRAME_SIZE - 1] = sum.toByte()
    }

    companion object {
        const val SIZE = 128 * 1024
        private const val BLOCKS = 16
        private const val BLOCK_SIZE = 8192
        private const val FRAME_SIZE = 128
        private const val FILE_NAME_LENGTH = 20
        private const val TITLE_LENGTH = 64
        private const val NO_NEXT = 0xFFFF

        private const val STATE_FIRST = 0x51
        private const val STATE_MIDDLE = 0x52
        private const val STATE_LAST = 0x53
        private const val STATE_FREE = 0xA0
        private const val STATE_DELETED_FIRST = 0xA1
        private const val STATE_DELETED_MIDDLE = 0xA2
        private const val STATE_DELETED_LAST = 0xA3

        /** Reads the card in [file], or null if it is not a 128 KB card image. */
        fun open(file: File): Ps1Card? = if (file.length() == SIZE.toLong()) Ps1Card(file.readBytes()) else null

        /** A card as the core formats it, with no saves. */
        fun formatted(): Ps1Card {
            val card = Ps1Card(ByteArray(SIZE) { 0xFF.toByte() })
            with(card) {
                image.fill(0, 0, FRAME_SIZE)
                image[0] = 'M'.code.toByte()
                image[1] = 'C'.code.toByte()
                updateChecksum(0)
                for (frame in 1 until 16) {
                    val at = frameOffset(0, frame)
                    image.fill(0, at, at + FRAME_SIZE)
                    putU32(at, STATE_FREE)
                    putU16(at + 8, NO_NEXT)
                    updateChecksum(at)
                }
                // The broken sector list: none.
                for (frame in 16 until 36) {
                    val at = frameOffset(0, frame)
                    image.fill(0, at, at + FRAME_SIZE)
                    putU32(at, -1)
                    putU16(at + 8, NO_NEXT)
                    updateChecksum(at)
                }
                image.fill(0, frameOffset(0, 36), frameOffset(0, 63))
                // The write test frame repeats the header.
                image.copyInto(image, frameOffset(0, 63), 0, FRAME_SIZE)
            }
            return card
        }

        /**
         * The first icon frame of the save whose first block starts at [start] in [data], as 16×16
         * ARGB pixels, or null if the block has no title frame. Color 0 is transparent.
         */
        fun iconOf(data: ByteArray, start: Int): IntArray? {
            if (data.size < start + 2 * FRAME_SIZE) return null
            if (data[start] != 'S'.code.toByte() || data[start + 1] != 'C'.code.toByte()) return null
            if ((data[start + 2].toInt() and 0xFF) !in 0x11..0x13) return null
            val palette = IntArray(16) { i ->
                val at = start + 0x60 + i * 2
                val color = (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8)
                if (color == 0) 0 else rgb555ToArgb(color)
            }
            val pixels = IntArray(16 * 16)
            val bitmap = start + FRAME_SIZE
            for (i in 0 until 128) {
                val b = data[bitmap + i].toInt() and 0xFF
                pixels[i * 2] = palette[b and 0xF]
                pixels[i * 2 + 1] = palette[b shr 4]
            }
            return pixels
        }

        private fun rgb555ToArgb(color: Int): Int {
            fun expand(v: Int) = (v shl 3) or (v shr 2)
            val r = expand(color and 0x1F)
            val g = expand((color shr 5) and 0x1F)
            val b = expand((color shr 10) and 0x1F)
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}
