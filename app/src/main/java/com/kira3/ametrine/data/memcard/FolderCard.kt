package com.kira3.ametrine.data.memcard

import java.io.File

/**
 * A folder memory card as a [SaveStore]: each save is a folder of files. The core adds its own
 * bookkeeping files (names starting with `_pcsx2_`) and rebuilds them when they are missing, so a
 * save copied in as plain files is picked up.
 */
class FolderCard(private val folder: File) : SaveStore {
    private fun saves(): List<File> =
        folder.listFiles { f -> f.isDirectory && !f.name.startsWith(CORE_PREFIX) && !isSystemSave(f.name) }
            .orEmpty().sortedBy { it.name }

    private fun filesOf(save: File): List<File> =
        save.listFiles { f -> f.isFile && !f.name.startsWith(CORE_PREFIX) }.orEmpty().sortedBy { it.name }

    override fun list(): List<SaveInfo> = saves().map { save ->
        val files = filesOf(save)
        val title = files.firstOrNull { it.name == "icon.sys" }?.let { iconSysTitle(it.readBytes()) }
        // The folder's own entries (. and .. and a file each, two to a cluster), then the files.
        val clusters = (files.size + 3) / 2 + files.sumOf { ((it.length() + 1023) / 1024).toInt() }
        SaveInfo(save.name, title, clusters, files.size, save.lastModified())
    }

    override fun read(name: String): SaveData? {
        val save = saves().firstOrNull { it.name == name } ?: return null
        val files = filesOf(save).map { SaveFile(it.name, it.readBytes(), it.lastModified(), it.lastModified()) }
        return SaveData(save.name, files, save.lastModified(), save.lastModified())
    }

    override fun write(save: SaveData, replace: Boolean): WriteResult {
        ensureFormatted()
        val target = File(folder, save.name)
        if (target.exists()) {
            if (!replace) return WriteResult.Exists
        }
        // Built beside the card's saves and moved into place, so a failure leaves nothing half-written.
        val staging = File(folder, "${CORE_PREFIX}staging_${save.name}")
        staging.deleteRecursively()
        return try {
            if (!staging.mkdirs()) return WriteResult.Failed
            for (f in save.files) {
                val out = File(staging, f.name)
                out.writeBytes(f.data)
                if (f.modified > 0) out.setLastModified(f.modified)
            }
            if (save.modified > 0) staging.setLastModified(save.modified)
            target.deleteRecursively()
            if (staging.renameTo(target)) WriteResult.Ok else WriteResult.Failed
        } catch (e: java.io.IOException) {
            staging.deleteRecursively()
            WriteResult.Failed
        }
    }

    override fun delete(name: String) {
        File(folder, name).takeIf { it.isDirectory && !it.name.startsWith(CORE_PREFIX) }?.deleteRecursively()
    }

    override fun freeKilobytes(): Int? = null

    /**
     * A card the core has not formatted has an empty superblock file, and the core ignores the
     * saves in it; write the superblock of a standard 8 MB card so they show up.
     */
    private fun ensureFormatted() {
        val sb = File(folder, SUPERBLOCK)
        if (sb.length() >= SUPERBLOCK_SIZE) return
        sb.writeBytes(Ps2CardFormat.superblock(8192, SUPERBLOCK_SIZE))
    }

    private companion object {
        const val CORE_PREFIX = "_pcsx2_"
        const val SUPERBLOCK = "_pcsx2_superblock"

        /** The core reads a whole erase block (16 pages of 512 bytes) as the superblock. */
        const val SUPERBLOCK_SIZE = 8192
    }
}
