package com.kira3.ametrine.data.memcard

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Random

class FolderCardTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun newFolderCard(): Pair<File, FolderCard> {
        val folder = temp.newFolder()
        File(folder, "_pcsx2_superblock").writeBytes(ByteArray(0))
        return folder to FolderCard(folder)
    }

    private fun save(name: String, vararg sizes: Int): SaveData {
        val random = Random(name.hashCode().toLong())
        return SaveData(
            name,
            sizes.mapIndexed { i, size ->
                SaveFile("f$i.bin", ByteArray(size).also { random.nextBytes(it) }, 1_700_000_000_000L, 1_700_000_000_000L)
            },
            1_700_000_000_000L, 1_700_000_000_000L,
        )
    }

    @Test
    fun savesWriteListReadAndDelete() {
        val (folder, card) = newFolderCard()
        val a = save("BASLUS-20000TEST", 10, 3000)
        assertEquals(WriteResult.Ok, card.write(a, false))
        assertEquals(WriteResult.Exists, card.write(a, false))
        assertEquals(WriteResult.Ok, card.write(save("BASLUS-20000TEST", 5), true))
        assertEquals(listOf("BASLUS-20000TEST"), card.list().map { it.name })
        assertEquals(1, card.read("BASLUS-20000TEST")!!.files.size)
        // The superblock is filled in so the core treats the card as formatted.
        assertEquals(8192L, File(folder, "_pcsx2_superblock").length())
        assertEquals(0x6F.toByte(), File(folder, "_pcsx2_superblock").readBytes()[0x16])
        card.delete("BASLUS-20000TEST")
        assertTrue(card.list().isEmpty())
        assertNull(card.read("BASLUS-20000TEST"))
        // No staging folder is left behind.
        assertEquals(listOf("_pcsx2_superblock"), folder.list()!!.toList())
    }

    @Test
    fun savesCopyBetweenFolderAndFileCards() {
        val (_, folderCard) = newFolderCard()
        val original = save("BASLUS-20001TEST", 100, 5000, 1)
        assertEquals(WriteResult.Ok, folderCard.write(original, false))

        val file = temp.newFile().also { f ->
            f.outputStream().buffered().use { out ->
                val page = ByteArray(528) { 0xFF.toByte() }
                repeat(16384) { out.write(page) }
            }
        }
        Ps2Card(file, writable = true).use { ps2 ->
            assertEquals(WriteResult.Ok, ps2.write(folderCard.read("BASLUS-20001TEST")!!, false))
        }
        val (_, back) = newFolderCard()
        Ps2Card(file).use { ps2 ->
            assertEquals(WriteResult.Ok, back.write(ps2.read("BASLUS-20001TEST")!!, false))
        }
        val result = back.read("BASLUS-20001TEST")!!
        assertNotNull(result)
        assertEquals(original.files.map { it.name }, result.files.map { it.name })
        original.files.zip(result.files).forEach { (x, y) -> assertArrayEquals(x.data, y.data) }
    }

    @Test
    fun theBiosSystemFolderIsNotListed() {
        val (folder, card) = newFolderCard()
        File(folder, "BIDATA-SYSTEM").mkdirs()
        File(folder, "BIDATA-SYSTEM/history").writeBytes(ByteArray(4))
        assertTrue(card.list().isEmpty())
    }
}
