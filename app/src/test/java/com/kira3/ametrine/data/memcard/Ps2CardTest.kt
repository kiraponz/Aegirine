package com.kira3.ametrine.data.memcard

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Random

class Ps2CardTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun resource(name: String): ByteArray = javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes()

    /** A card as the core creates it: all ones, 528-byte pages. */
    private fun blankCard(megabytes: Int = 8, raw: Boolean = true): File {
        val file = temp.newFile()
        val pageBytes = if (raw) 528L else 512L
        val pages = megabytes * 2048L
        file.outputStream().buffered().use { out ->
            val page = ByteArray(pageBytes.toInt()) { 0xFF.toByte() }
            for (i in 0 until pages) out.write(page)
        }
        return file
    }

    private fun save(name: String, vararg sizes: Int, seed: Int = 1): SaveData {
        val random = Random(seed.toLong())
        val files = sizes.mapIndexed { i, size ->
            SaveFile("file$i.dat", ByteArray(size).also { random.nextBytes(it) }, 1_700_000_000_000L, 1_700_000_100_000L)
        }
        return SaveData(name, files, 1_700_000_000_000L, 1_700_000_100_000L)
    }

    @Test
    fun superblockMatchesTheOneTheBiosWrites() {
        val real = resource("ps2_superblock_8mb.bin")
        assertArrayEquals(real, Ps2CardFormat.superblock(8192, 512))
    }

    @Test
    fun eccMatchesTheSpareAreaOfARealPage() {
        val real = resource("ps2_superblock_8mb.bin")
        val spare = ByteArray(16)
        Ps2Ecc.spare(real, 0, spare, 0)
        assertArrayEquals(resource("ps2_superblock_8mb.spare"), spare)
    }

    @Test
    fun eccMatchesEveryWrittenPageOfARealCard() {
        val pages = resource("ps2_card_first_pages.bin")
        var checked = 0
        for (p in 0 until pages.size / 528) {
            val spare = pages.copyOfRange(p * 528 + 512, (p + 1) * 528)
            // Page 1 holds the card ID, which the page's code does not cover.
            if (p == 1 || spare.all { it == 0xFF.toByte() }) continue
            val mine = ByteArray(16)
            Ps2Ecc.spare(pages, p * 528, mine, 0)
            assertArrayEquals("page $p", spare, mine)
            checked++
        }
        assertTrue(checked >= 18)
    }

    @Test
    fun aBlankCardIsBlankAndEmpty() {
        Ps2Card(blankCard()).use {
            assertEquals(CardState.Blank, it.state)
            assertTrue(it.list().isEmpty())
        }
    }

    @Test
    fun savesWrittenToABlankCardReadBackAndFreeTheirSpace() {
        val card = blankCard()
        val a = save("BASLUS-20000TEST", 100, 2500, 0, 4096, seed = 1)
        val b = save("BASLUS-20001TEST", 1_300_000, seed = 2)
        Ps2Card(card, writable = true).use {
            assertEquals(WriteResult.Ok, it.write(a, false))
            assertEquals(WriteResult.Ok, it.write(b, false))
        }
        Ps2Card(card).use { c ->
            assertEquals(CardState.Formatted, c.state)
            assertEquals(listOf("BASLUS-20000TEST", "BASLUS-20001TEST"), c.list().map { it.name })
            for (expected in listOf(a, b)) {
                val read = c.read(expected.name)!!
                assertEquals(expected.files.map { it.name }, read.files.map { it.name })
                expected.files.zip(read.files).forEach { (x, y) -> assertArrayEquals(x.data, y.data) }
                assertEquals(expected.files.map { it.created / 1000 }, read.files.map { it.created / 1000 })
            }
            // 8 MB card: 8000 clusters usable, one of them the root directory.
            // The root directory (now two clusters), then each save's directory and files.
            val used = 2 + (3 + (1 + 3 + 0 + 4)) + (2 + 1270)
            assertEquals(8000 - used, c.freeKilobytes())
        }
        Ps2Card(card, writable = true).use {
            it.delete("BASLUS-20001TEST")
            it.delete("BASLUS-20000TEST")
        }
        Ps2Card(card).use {
            assertTrue(it.list().isEmpty())
            // The root directory keeps the cluster it grew by.
            assertEquals(7998, it.freeKilobytes())
        }
    }

    @Test
    fun replacingKeepsOneCopyAndRefusesWithoutPermission() {
        val card = blankCard()
        Ps2Card(card, writable = true).use {
            assertEquals(WriteResult.Ok, it.write(save("BASLUS-20000TEST", 5000), false))
            assertEquals(WriteResult.Exists, it.write(save("BASLUS-20000TEST", 10), false))
            assertEquals(WriteResult.Ok, it.write(save("BASLUS-20000TEST", 10, seed = 7), true))
        }
        Ps2Card(card).use {
            assertEquals(1, it.list().size)
            assertEquals(10, it.read("BASLUS-20000TEST")!!.files[0].data.size)
        }
    }

    @Test
    fun theRootDirectoryGrowsAndDeletedEntriesAreReused() {
        val card = blankCard()
        Ps2Card(card, writable = true).use {
            for (i in 0 until 9) assertEquals(WriteResult.Ok, it.write(save("SAVE$i", 1500, seed = i), false))
            it.delete("SAVE3")
            assertEquals(WriteResult.Ok, it.write(save("NEW", 10), false))
        }
        Ps2Card(card).use {
            assertEquals(9, it.list().size)
            assertNotNull(it.read("NEW"))
            assertNull(it.read("SAVE3"))
            assertArrayEquals(save("SAVE8", 1500, seed = 8).files[0].data, it.read("SAVE8")!!.files[0].data)
        }
    }

    @Test
    fun aSaveThatDoesNotFitIsRefusedAndLeavesTheCardAlone() {
        val card = blankCard()
        Ps2Card(card, writable = true).use {
            assertEquals(WriteResult.Full, it.write(save("BIG", 9_000_000), false))
            assertEquals(WriteResult.Ok, it.write(save("SMALL", 10), false))
            assertEquals(listOf("SMALL"), it.list().map { s -> s.name })
        }
    }

    @Test
    fun cardsWithoutSpareAreasAndOtherSizesWork() {
        for ((mb, raw) in listOf(8 to false, 16 to true, 32 to true, 64 to true)) {
            val card = blankCard(mb, raw)
            val s = save("BASLUS-20000TEST", 70_000, 3, seed = mb)
            Ps2Card(card, writable = true).use { assertEquals(WriteResult.Ok, it.write(s, false)) }
            Ps2Card(card).use {
                assertEquals(CardState.Formatted, it.state)
                val read = it.read("BASLUS-20000TEST")!!
                s.files.zip(read.files).forEach { (x, y) -> assertArrayEquals(x.data, y.data) }
            }
        }
    }

    @Test
    fun iconSysTitleIsReadFromShiftJis() {
        val sys = ByteArray(964)
        "PS2D".toByteArray().copyInto(sys)
        sys[6] = 8
        val title = "ＧＲＡＮ　ＴＵＲ".toByteArray(charset("Shift_JIS"))
        title.copyInto(sys, 0xC0)
        assertTrue(iconSysTitle(sys)!!.startsWith("GRAN"))
        assertNull(iconSysTitle(ByteArray(964)))
    }

    /** With AMETRINE_REAL_CARD set to a card the BIOS formatted, saves read from it and copy to a new card. */
    @Test
    fun aRealCardReadsAndItsSavesCopy() {
        val path = System.getenv("AMETRINE_REAL_CARD")
        assumeTrue(path != null && File(path).exists())
        val target = blankCard()
        Ps2Card(File(path!!)).use { real ->
            assertEquals(CardState.Formatted, real.state)
            val saves = real.list()
            println("real card: " + saves.joinToString { "${it.name} '${it.title}' ${it.kilobytes}KB ${it.files} files" })
            assertTrue(saves.isNotEmpty())
            Ps2Card(target, writable = true).use { out ->
                for (info in saves) assertEquals(WriteResult.Ok, out.write(real.read(info.name)!!, false))
            }
            Ps2Card(target).use { copy ->
                for (info in saves) {
                    val a = real.read(info.name)!!
                    val b = copy.read(info.name)!!
                    a.files.zip(b.files).forEach { (x, y) ->
                        assertEquals(x.name, y.name)
                        assertArrayEquals(x.data, y.data)
                    }
                }
                assertEquals(saves.map { it.kilobytes }, copy.list().map { it.kilobytes })
            }
        }
        System.getenv("AMETRINE_COPY_OUT")?.let { target.copyTo(File(it), overwrite = true) }
    }

    @Test
    fun formatErasedLeavesAnEmptyFormattedCard() {
        val file = blankCard()
        Ps2Card(file, writable = true).use { it.write(save("BASLUS-00001", 3000), replace = false) }
        val length = file.length()
        file.outputStream().use { out -> repeat((length / 16).toInt()) { out.write(ByteArray(16) { 0xFF.toByte() }) } }
        Ps2Card(file, writable = true).use {
            assertEquals(CardState.Blank, it.state)
            it.formatErased()
        }
        Ps2Card(file).use {
            assertEquals(CardState.Formatted, it.state)
            assertTrue(it.list().isEmpty())
        }
    }
}
