package com.kira3.ametrine.data.memcard

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Ps1CardTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun formattedCardIsEmpty() {
        val card = Ps1Card.formatted()
        assertEquals(CardState.Formatted, card.state)
        assertEquals(emptyList<SaveInfo>(), card.list())
        assertEquals(15 * 8, card.freeKilobytes())
    }

    @Test
    fun savesRoundTripThroughAFile() {
        val card = Ps1Card.formatted()
        val save = save("BISLPS-03430FF1", blocks = 2, title = "ＦＦ１")
        assertEquals(WriteResult.Ok, card.write(save, replace = false))

        val file = temp.newFile("card.mcd")
        card.save(file)
        val reopened = Ps1Card.open(file)!!
        val info = reopened.list().single()
        assertEquals("BISLPS-03430FF1", info.name)
        assertEquals("FF1", info.title)
        assertEquals(16, info.kilobytes)
        assertArrayEquals(save.data, reopened.read(info.name)!!.data)
        assertEquals((15 - 2) * 8, reopened.freeKilobytes())
        assertEquals("SLPS-03430", serialOfSave(info.name))
    }

    @Test
    fun deleteFreesTheBlocks() {
        val card = Ps1Card.formatted()
        card.write(save("BASLUS-00067A", blocks = 3), replace = false)
        card.delete("BASLUS-00067A")
        assertEquals(emptyList<SaveInfo>(), card.list())
        assertEquals(15 * 8, card.freeKilobytes())
    }

    @Test
    fun aFullCardRefuses() {
        val card = Ps1Card.formatted()
        assertEquals(WriteResult.Ok, card.write(save("BISLPS-00001A", blocks = 14), replace = false))
        assertEquals(WriteResult.Full, card.write(save("BISLPS-00002B", blocks = 2), replace = false))
        assertEquals(WriteResult.Exists, card.write(save("BISLPS-00001A", blocks = 1), replace = false))
        // Replacing frees the old save's blocks first.
        assertEquals(WriteResult.Ok, card.write(save("BISLPS-00001A", blocks = 15), replace = true))
    }

    @Test
    fun iconUsesThePalette() {
        val card = Ps1Card.formatted()
        card.write(save("BISLPS-03430FF1", blocks = 1), replace = false)
        val pixels = card.icon("BISLPS-03430FF1")!!
        // Index 1 is pure red (RGB555 0x001F); index 0 is transparent.
        assertEquals(0xFFFF0000.toInt(), pixels[0])
        assertEquals(0, pixels[1])
        assertNull(card.icon("missing"))
    }

    @Test
    fun otherSizesAreNotCards() {
        val file = temp.newFile("small.mcd").apply { writeBytes(ByteArray(1000)) }
        assertNull(Ps1Card.open(file))
    }

    /** A save of [blocks] blocks with a title frame and a one-frame icon (left pixel red, right transparent). */
    private fun save(name: String, blocks: Int, title: String = "TEST"): SaveData {
        val data = ByteArray(blocks * 8192) { (it % 251).toByte() }
        data[0] = 'S'.code.toByte()
        data[1] = 'C'.code.toByte()
        data[2] = 0x11
        data[3] = blocks.toByte()
        data.fill(0, 4, 0x80)
        title.toByteArray(charset("Shift_JIS")).copyInto(data, 4)
        // Palette entry 1: red.
        data[0x62] = 0x1F
        data[0x63] = 0
        data.fill(0x01, 0x80, 0x100)
        return SaveData(name, data)
    }
}
