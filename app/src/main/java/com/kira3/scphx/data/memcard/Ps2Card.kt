package com.kira3.scphx.data.memcard

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.util.TimeZone

/**
 * A PS2 memory card file (`.ps2`, 8 to 64 MB, with or without the 16-byte spare area per page) as
 * a [SaveStore]. The layout is the one in docs of mymc / PCSX2 (Reference/PS2-MemoryCardFileSystem):
 * superblock, indirect FAT, FAT and allocatable clusters of 1024 bytes, with 512-byte directory
 * entries and the saves as folders in the root directory.
 *
 * Changes are kept in memory and written by [close] when the card was opened [writable]; open a
 * copy of the card and replace the original with it, so a failure leaves the card as it was.
 */
class Ps2Card(private val file: File, private val writable: Boolean = false) : SaveStore, Closeable {
    private val raf = RandomAccessFile(file, if (writable) "rw" else "r")

    /** 528 for a raw page (data and spare area), 512 for data only. */
    private val stride: Int = if (file.length() % RAW_PAGE == 0L) RAW_PAGE.toInt() else PAGE

    val totalClusters: Int = (file.length() / stride / PAGES_PER_CLUSTER).toInt()

    var state: CardState = CardState.Unsupported
        private set

    private var allocOffset = 0
    private var allocEnd = 0
    private var rootCluster = 0
    private var ifcList = IntArray(0)
    private var fat = IntArray(0)
    private var fatClusters = IntArray(0)

    private val cache = HashMap<Int, ByteArray>()
    private val dirty = HashSet<Int>()
    private val fatDirty = HashSet<Int>()

    init {
        load()
    }

    private fun load() {
        if (totalClusters < MIN_CLUSTERS) return
        val sb = ByteArray(PAGE)
        readPage(0, sb, 0)
        if (sb.all { it == 0xFF.toByte() }) {
            state = CardState.Blank
            return
        }
        if (String(sb, 0, MAGIC.length, Charsets.ISO_8859_1) != MAGIC) return
        if (u16(sb, 0x28) != PAGE || u16(sb, 0x2A) != PAGES_PER_CLUSTER || u16(sb, 0x2C) != 16) return
        if (u32(sb, 0x30) != totalClusters) return
        allocOffset = u32(sb, 0x34)
        allocEnd = u32(sb, 0x38)
        rootCluster = u32(sb, 0x3C)
        ifcList = IntArray(32) { u32(sb, 0x50 + it * 4) }
        if (allocOffset <= 0 || allocOffset >= totalClusters || allocEnd <= 0) return

        val fatList = ArrayList<Int>()
        for (ifc in ifcList) {
            if (ifc == 0 || ifc == -1 || ifc >= totalClusters) continue
            val data = cluster(ifc)
            for (k in 0 until CLUSTER / 4) {
                val v = u32(data, k * 4)
                if (v != 0 && v != -1 && v < totalClusters) fatList += v
            }
        }
        fatClusters = fatList.toIntArray()
        fat = IntArray(fatClusters.size * FAT_PER_CLUSTER)
        fatClusters.forEachIndexed { i, c ->
            val data = cluster(c)
            for (k in 0 until FAT_PER_CLUSTER) fat[i * FAT_PER_CLUSTER + k] = u32(data, k * 4)
        }
        cache.clear()
        state = CardState.Formatted
    }

    // ---- Clusters ----

    private fun readPage(page: Int, out: ByteArray, outOffset: Int) {
        raf.seek(page.toLong() * stride)
        raf.readFully(out, outOffset, PAGE)
    }

    private fun cluster(absolute: Int): ByteArray = cache.getOrPut(absolute) {
        val data = ByteArray(CLUSTER)
        for (p in 0 until PAGES_PER_CLUSTER) readPage(absolute * PAGES_PER_CLUSTER + p, data, p * PAGE)
        data
    }

    private fun touch(absolute: Int) {
        dirty += absolute
    }

    private fun writeCluster(absolute: Int, data: ByteArray) {
        val spare = ByteArray(16)
        for (p in 0 until PAGES_PER_CLUSTER) {
            raf.seek((absolute * PAGES_PER_CLUSTER + p).toLong() * stride)
            raf.write(data, p * PAGE, PAGE)
            if (stride == RAW_PAGE.toInt()) {
                Ps2Ecc.spare(data, p * PAGE, spare, 0)
                raf.write(spare)
            }
        }
    }

    // ---- FAT ----

    private fun fatGet(rel: Int): Int = fat[rel]

    private fun fatSet(rel: Int, value: Int) {
        fat[rel] = value
        fatDirty += rel / FAT_PER_CLUSTER
    }

    private fun isFree(rel: Int) = fat[rel] and USED_BIT == 0

    /** The clusters of the chain that starts at [first] (relative to the allocation area). */
    private fun chain(first: Int): List<Int> {
        val out = ArrayList<Int>()
        var c = first
        while (c in fat.indices && out.size <= fat.size) {
            out += c
            val next = fat[c]
            if (next == END_OF_CHAIN) break
            if (next and USED_BIT == 0) break
            c = next and 0x7FFFFFFF
        }
        return out
    }

    /** Clusters that games may use: the core and the BIOS round the count down to whole thousands. */
    private val usable: Int get() = minOf(fat.size, (allocEnd / 1000) * 1000)

    private fun freeClusterCount(): Int {
        var n = 0
        for (i in 0 until usable) if (isFree(i)) n++
        return n
    }

    private var allocCursor = 0

    private fun allocate(): Int {
        for (k in 0 until usable) {
            val c = (allocCursor + k) % usable
            if (isFree(c)) {
                allocCursor = c + 1
                fatSet(c, END_OF_CHAIN)
                return c
            }
        }
        error("card full")
    }

    private fun free(first: Int) {
        for (c in chain(first)) fatSet(c, FREE_ENTRY)
    }

    // ---- Directories ----

    private fun entryLocation(dirFirst: Int, index: Int): Pair<ByteArray, Int>? {
        val chain = chain(dirFirst)
        val c = chain.getOrNull(index / 2) ?: return null
        return cluster(allocOffset + c) to (index % 2) * ENTRY
    }

    private fun entry(dirFirst: Int, index: Int): ByteArray? {
        val (data, offset) = entryLocation(dirFirst, index) ?: return null
        return data.copyOfRange(offset, offset + ENTRY)
    }

    private fun putEntry(dirFirst: Int, index: Int, raw: ByteArray) {
        val chain = chain(dirFirst)
        val c = chain[index / 2]
        val data = cluster(allocOffset + c)
        raw.copyInto(data, (index % 2) * ENTRY, 0, ENTRY)
        touch(allocOffset + c)
    }

    private class Dirent(val index: Int, val raw: ByteArray) {
        val mode get() = u32(raw, 0)
        val length get() = u32(raw, 4)
        val cluster get() = u32(raw, 0x10)
        val used get() = mode and DF_EXISTS != 0
        val isDir get() = mode and DF_DIRECTORY != 0
        val isFile get() = mode and DF_FILE != 0
        val name: String
            get() {
                var end = 0x40
                while (end < 0x60 && raw[end].toInt() != 0) end++
                return String(raw, 0x40, end - 0x40, Charsets.ISO_8859_1)
            }
    }

    private fun entriesOf(dirFirst: Int, count: Int): List<Dirent> =
        (0 until count).mapNotNull { i -> entry(dirFirst, i)?.let { Dirent(i, it) } }

    private fun rootLength(): Int = u32(entry(rootCluster, 0)!!, 4)

    private fun rootSaves(): List<Dirent> =
        entriesOf(rootCluster, rootLength()).drop(2).filter { it.used && it.isDir && it.mode and DF_HIDDEN == 0 && !isSystemSave(it.name) }

    private fun filesOf(save: Dirent): List<Dirent> =
        entriesOf(save.cluster, save.length).drop(2).filter { it.used && it.isFile }

    private fun clustersOf(save: Dirent): Int =
        chain(save.cluster).size + filesOf(save).sumOf { if (it.cluster == EMPTY_FILE) 0 else chain(it.cluster).size }

    private fun readFile(f: Dirent): ByteArray {
        val length = f.length
        val out = ByteArray(length)
        if (length == 0 || f.cluster == EMPTY_FILE) return out
        var written = 0
        for (c in chain(f.cluster)) {
            val n = minOf(CLUSTER, length - written)
            if (n <= 0) break
            cluster(allocOffset + c).copyInto(out, written, 0, n)
            written += n
        }
        return out
    }

    // ---- SaveStore ----

    override fun list(): List<SaveInfo> {
        if (state != CardState.Formatted) return emptyList()
        return rootSaves().map { save ->
            val files = filesOf(save)
            val title = files.firstOrNull { it.name == "icon.sys" }?.let { iconSysTitle(readFile(it)) }
            SaveInfo(save.name, title, clustersOf(save), files.size, fromBytes(save.raw, 0x18))
        }
    }

    override fun read(name: String): SaveData? {
        if (state != CardState.Formatted) return null
        val save = rootSaves().firstOrNull { it.name == name } ?: return null
        val files = filesOf(save).map {
            SaveFile(it.name, readFile(it), fromBytes(it.raw, 8), fromBytes(it.raw, 0x18), it.mode, u32(it.raw, 0x20))
        }
        return SaveData(save.name, files, fromBytes(save.raw, 8), fromBytes(save.raw, 0x18), save.mode, u32(save.raw, 0x20))
    }

    override fun freeKilobytes(): Int? = if (state == CardState.Formatted) freeClusterCount() else null

    /** The size of the space games can use, in kilobytes. */
    fun capacityKilobytes(): Int = if (state == CardState.Formatted) usable else 0

    override fun delete(name: String) {
        check(writable)
        val save = rootSaves().firstOrNull { it.name == name } ?: return
        removeSave(save)
    }

    private fun removeSave(save: Dirent) {
        for (f in filesOf(save)) if (f.cluster != EMPTY_FILE) free(f.cluster)
        free(save.cluster)
        // The entry stays in the list as a deleted one, as on a real card.
        val raw = save.raw.copyOf()
        putInt(raw, 0, save.mode and DF_EXISTS.inv())
        putEntry(rootCluster, save.index, raw)
    }

    override fun write(save: SaveData, replace: Boolean): WriteResult {
        check(writable)
        if (state == CardState.Blank) format()
        if (state != CardState.Formatted) return WriteResult.Failed
        val existing = rootSaves().firstOrNull { it.name == save.name }
        if (existing != null) {
            if (!replace) return WriteResult.Exists
            removeSave(existing)
        }

        val entryCount = 2 + save.files.size
        val dirClusters = (entryCount + 1) / 2
        val fileClusters = save.files.sumOf { (it.data.size + CLUSTER - 1) / CLUSTER }
        val rootEntries = entriesOf(rootCluster, rootLength())
        val reusable = rootEntries.drop(2).firstOrNull { !it.used }
        val newRootCluster = if (reusable == null && rootLength() % 2 == 0) 1 else 0
        if (dirClusters + fileClusters + newRootCluster > freeClusterCount()) return WriteResult.Full

        val now = System.currentTimeMillis()
        // A slot in the root directory.
        val index = reusable?.index ?: rootLength()
        if (reusable == null) {
            if (newRootCluster == 1) {
                val last = chain(rootCluster).last()
                val added = allocate()
                fatSet(last, added or USED_BIT)
                cluster(allocOffset + added).fill(0)
                touch(allocOffset + added)
            }
        }

        // The save's own directory: its "." and ".." entries, then its files.
        val dirChain = List(dirClusters) { allocate() }
        for (i in 0 until dirChain.size - 1) fatSet(dirChain[i], dirChain[i + 1] or USED_BIT)
        for (c in dirChain) {
            cluster(allocOffset + c).fill(0)
            touch(allocOffset + c)
        }
        val dot = ByteArray(ENTRY)
        putInt(dot, 0, DEFAULT_DIR_MODE)
        putInt(dot, 0x14, index)
        toBytes(save.created, dot, 8)
        toBytes(save.created, dot, 0x18)
        dot[0x40] = '.'.code.toByte()
        putEntry(dirChain[0], 0, dot)
        val dotdot = ByteArray(ENTRY)
        putInt(dotdot, 0, DEFAULT_DIR_MODE)
        toBytes(save.created, dotdot, 8)
        toBytes(save.created, dotdot, 0x18)
        dotdot[0x40] = '.'.code.toByte()
        dotdot[0x41] = '.'.code.toByte()
        putEntry(dirChain[0], 1, dotdot)

        save.files.forEachIndexed { i, f ->
            val raw = ByteArray(ENTRY)
            putInt(raw, 0, f.mode or DF_EXISTS)
            putInt(raw, 4, f.data.size)
            toBytes(f.created, raw, 8)
            toBytes(f.modified, raw, 0x18)
            putInt(raw, 0x20, f.attr)
            putName(raw, f.name)
            if (f.data.isEmpty()) {
                putInt(raw, 0x10, EMPTY_FILE)
            } else {
                val clusters = List((f.data.size + CLUSTER - 1) / CLUSTER) { allocate() }
                for (k in 0 until clusters.size - 1) fatSet(clusters[k], clusters[k + 1] or USED_BIT)
                clusters.forEachIndexed { k, c ->
                    val data = cluster(allocOffset + c)
                    data.fill(0)
                    f.data.copyInto(data, 0, k * CLUSTER, minOf(f.data.size, (k + 1) * CLUSTER))
                    touch(allocOffset + c)
                }
                putInt(raw, 0x10, clusters[0])
            }
            putEntry(dirChain[0], 2 + i, raw)
        }

        val dirEntry = ByteArray(ENTRY)
        putInt(dirEntry, 0, save.mode or DF_EXISTS)
        putInt(dirEntry, 4, entryCount)
        toBytes(save.created, dirEntry, 8)
        putInt(dirEntry, 0x10, dirChain[0])
        toBytes(save.modified.takeIf { it > 0 } ?: now, dirEntry, 0x18)
        putInt(dirEntry, 0x20, save.attr)
        putName(dirEntry, save.name)
        putEntry(rootCluster, index, dirEntry)

        if (reusable == null) {
            val root = entry(rootCluster, 0)!!
            putInt(root, 4, rootLength() + 1)
            toBytes(now, root, 0x18)
            putEntry(rootCluster, 0, root)
        }
        return WriteResult.Ok
    }

    /** Formats a card whose every byte is erased (0xFF); the saves it held are gone. */
    fun formatErased() {
        check(state == CardState.Blank)
        format()
    }

    /** Writes an empty file system over a blank card of any of the standard sizes. */
    private fun format() {
        check(writable)
        val clusters = totalClusters
        allocOffset = clusters / 256 + 9
        allocEnd = clusters - 16 - allocOffset
        rootCluster = 0
        ifcList = IntArray(32).also { it[0] = 8 }
        val sb = Ps2CardFormat.superblock(clusters, PAGE)

        // The first block as the BIOS leaves it: the superblock, 8 bytes of card ID in the next page
        // (made up here), and the rest erased, each page with its spare area.
        val first = ByteArray(8 * CLUSTER) { 0xFF.toByte() }
        sb.copyInto(first, 0)
        for (c in 0 until 8) writeCluster(c, first.copyOfRange(c * CLUSTER, (c + 1) * CLUSTER))
        // The card ID is not covered by the page's code (on a real card it is added after).
        raf.seek(PAGES_PER_CLUSTER.toLong() / 2 * stride)
        raf.write(ByteArray(8).also { java.util.Random().nextBytes(it) })

        val fatCount = clusters / 256
        fatClusters = IntArray(fatCount) { 9 + it }
        fat = IntArray(fatCount * FAT_PER_CLUSTER) { FREE_ENTRY }

        val ifc = ByteArray(CLUSTER) { 0xFF.toByte() }
        fatClusters.forEachIndexed { i, c -> putInt(ifc, i * 4, c) }
        cache[8] = ifc
        touch(8)
        for (i in 0 until fatCount) fatDirty += i

        // The root directory: "." (the directory itself) and ".." entries.
        val now = System.currentTimeMillis()
        val rootData = ByteArray(CLUSTER)
        cache[allocOffset] = rootData
        touch(allocOffset)
        fatSet(0, END_OF_CHAIN)
        val dot = ByteArray(ENTRY)
        putInt(dot, 0, DEFAULT_DIR_MODE)
        putInt(dot, 4, 2)
        toBytes(now, dot, 8)
        toBytes(now, dot, 0x18)
        dot[0x40] = '.'.code.toByte()
        val dotdot = ByteArray(ENTRY)
        putInt(dotdot, 0, DEFAULT_DIR_MODE and DF_READ.inv() or DF_HIDDEN)
        toBytes(now, dotdot, 8)
        toBytes(now, dotdot, 0x18)
        dotdot[0x40] = '.'.code.toByte()
        dotdot[0x41] = '.'.code.toByte()
        dot.copyInto(rootData, 0)
        dotdot.copyInto(rootData, ENTRY)
        state = CardState.Formatted
    }

    override fun close() {
        try {
            if (writable) {
                for (c in dirty) writeCluster(c, cluster(c))
                for (i in fatDirty) {
                    val data = ByteArray(CLUSTER)
                    for (k in 0 until FAT_PER_CLUSTER) putInt(data, k * 4, fat[i * FAT_PER_CLUSTER + k])
                    writeCluster(fatClusters[i], data)
                }
            }
        } finally {
            raf.close()
        }
    }

    companion object {
        private const val PAGE = 512
        private const val RAW_PAGE = 528L
        private const val PAGES_PER_CLUSTER = 2
        private const val CLUSTER = 1024
        private const val ENTRY = 512
        private const val FAT_PER_CLUSTER = 256
        private const val MIN_CLUSTERS = 8192
        private const val MAGIC = "Sony PS2 Memory Card Format "

        private const val USED_BIT = 0x80000000.toInt()
        private const val END_OF_CHAIN = -1
        private const val FREE_ENTRY = 0x7FFFFFFF
        private const val EMPTY_FILE = -1

        private const val DF_READ = 0x0001
        private const val DF_FILE = 0x0010
        private const val DF_DIRECTORY = 0x0020
        private const val DF_HIDDEN = 0x2000
        private const val DF_EXISTS = 0x8000

        private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

        private fun u32(b: ByteArray, o: Int) =
            (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
                ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

        private fun putShort(b: ByteArray, o: Int, v: Int) {
            b[o] = v.toByte()
            b[o + 1] = (v shr 8).toByte()
        }

        private fun putInt(b: ByteArray, o: Int, v: Int) {
            b[o] = v.toByte()
            b[o + 1] = (v shr 8).toByte()
            b[o + 2] = (v shr 16).toByte()
            b[o + 3] = (v shr 24).toByte()
        }

        private fun putName(raw: ByteArray, name: String) {
            val bytes = name.toByteArray(Charsets.ISO_8859_1)
            bytes.copyInto(raw, 0x40, 0, minOf(bytes.size, 31))
        }

        /** Card times are Japan time (UTC+9): [0, sec, min, hour, day, month, year (2 bytes)]. */
        private val JST = TimeZone.getTimeZone("GMT+9")

        fun toBytes(millis: Long, out: ByteArray, offset: Int) {
            val cal = java.util.Calendar.getInstance(JST).apply { timeInMillis = millis }
            out[offset] = 0
            out[offset + 1] = cal.get(java.util.Calendar.SECOND).toByte()
            out[offset + 2] = cal.get(java.util.Calendar.MINUTE).toByte()
            out[offset + 3] = cal.get(java.util.Calendar.HOUR_OF_DAY).toByte()
            out[offset + 4] = cal.get(java.util.Calendar.DAY_OF_MONTH).toByte()
            out[offset + 5] = (cal.get(java.util.Calendar.MONTH) + 1).toByte()
            putShort(out, offset + 6, cal.get(java.util.Calendar.YEAR))
        }

        fun fromBytes(raw: ByteArray, offset: Int): Long {
            val year = u16(raw, offset + 6)
            val month = raw[offset + 5].toInt()
            val day = raw[offset + 4].toInt()
            if (year == 0 || month !in 1..12 || day !in 1..31) return 0L
            val cal = java.util.Calendar.getInstance(JST)
            cal.clear()
            cal.set(year, month - 1, day, raw[offset + 3].toInt(), raw[offset + 2].toInt(), raw[offset + 1].toInt())
            return cal.timeInMillis
        }
    }
}
