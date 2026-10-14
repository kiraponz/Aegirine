package com.kira3.ametrine.emucore

import android.content.ContentResolver
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.Log

/**
 * Lets the core open SAF documents by path. The core only takes file paths, so a game is
 * handed to it as a virtual path, which the core's file functions send back here
 * (android_host.cpp):
 *
 *     /saf/<tree URI>/<anchor>/<name>[/<name>...]
 *
 * The tree and the anchor are URL-encoded, so neither holds a '/'; the names are the documents'
 * own. The anchor is the folder the names are looked up in:
 * - `d<document id>`: a folder inside the tree. This is the folder the game is in, so a .cue
 *   finds its .bin files (and .sbi, .ppf) beside it, in subfolders too.
 * - `f<document id>`: a single file of the tree, when the provider cannot say which folder
 *   holds it. The anchor then holds that file alone.
 * - `u<document URI>`: a single document from outside any tree (the tree part is "-").
 *
 * Read-only. Folder listings are kept for a few seconds, since a cue sheet looks up every track
 * in the same folder.
 */
internal class SafFiles(private val resolver: ContentResolver) {

    private class Entry(val id: String, val name: String, val isDir: Boolean, val size: Long, val modified: Long)

    /** Where a resolved path points: a document, or the folder of a single-file anchor. */
    private class Target(val uri: Uri?, val entry: Entry?, val children: (() -> List<Entry>?)?)

    private class Listing(val time: Long, val entries: List<Entry>)

    private val listings = HashMap<String, Listing>()

    /** The core's path for [documentUri], or null if it is not a document we can name. Blocking I/O. */
    fun pathFor(documentUri: Uri): String? {
        val name = query(documentUri)?.name ?: return null
        if (!isTreeDocument(documentUri)) return "$PREFIX-/u${Uri.encode(documentUri.toString())}/$name"

        val treeId = DocumentsContract.getTreeDocumentId(documentUri)
        val docId = DocumentsContract.getDocumentId(documentUri)
        val tree = DocumentsContract.buildTreeDocumentUri(documentUri.authority, treeId)
        // The ids from the tree's root down to the document; not every provider can tell.
        val parent = try {
            DocumentsContract.findDocumentPath(resolver, documentUri)?.path
                ?.takeIf { it.size >= 2 && it.last() == docId }?.let { it[it.size - 2] }
        } catch (e: Exception) {
            Log.w(TAG, "No document path for $documentUri", e)
            null
        }
        val anchor = if (parent != null) "d${Uri.encode(parent)}" else "f${Uri.encode(docId)}"
        return "$PREFIX${Uri.encode(tree.toString())}/$anchor/$name"
    }

    /** A new read-only descriptor for the file at [path], or -1. */
    fun open(path: String): Int {
        val uri = resolve(path)?.takeIf { it.entry?.isDir == false }?.uri ?: return -1
        return try {
            resolver.openFileDescriptor(uri, "r")?.detachFd() ?: -1
        } catch (e: Exception) {
            Log.w(TAG, "Could not open $uri", e)
            -1
        }
    }

    /** [size, modified (seconds), 1 for a folder or 0], or null if nothing is at [path]. */
    fun stat(path: String): LongArray? {
        val target = resolve(path) ?: return null
        val entry = target.entry ?: return longArrayOf(0, 0, 1)
        return longArrayOf(if (entry.isDir) 0 else entry.size, entry.modified / 1000, if (entry.isDir) 1 else 0)
    }

    /** The entries of the folder at [path], each "name\tisDir(0/1)\tsize\tmodified (seconds)", or null. */
    fun list(path: String): Array<String>? {
        val target = resolve(path) ?: return null
        val children = target.children?.invoke() ?: return null
        return children.map { "${it.name}\t${if (it.isDir) 1 else 0}\t${it.size}\t${it.modified / 1000}" }.toTypedArray()
    }

    private fun resolve(path: String): Target? = try {
        resolveOrThrow(path)
    } catch (e: Exception) {
        Log.w(TAG, "Could not resolve $path", e)
        null
    }

    private fun resolveOrThrow(path: String): Target? {
        if (!path.startsWith(PREFIX)) return null
        val parts = path.removePrefix(PREFIX).split('/')
        if (parts.size < 2) return null
        val anchor = Uri.decode(parts[1])
        val names = parts.drop(2).filter { it.isNotEmpty() && it != "." }

        when (anchor.firstOrNull()) {
            'u', 'f' -> {
                // A folder holding just this one file.
                val uri = if (anchor[0] == 'u') {
                    Uri.parse(anchor.substring(1))
                } else {
                    DocumentsContract.buildDocumentUriUsingTree(Uri.parse(Uri.decode(parts[0])), anchor.substring(1))
                }
                val file = query(uri) ?: return null
                return when {
                    names.isEmpty() -> Target(null, null) { listOf(file) }
                    names.size == 1 && names[0].equals(file.name, ignoreCase = true) -> Target(uri, file, null)
                    else -> null
                }
            }

            'd' -> {
                val tree = Uri.parse(Uri.decode(parts[0]))
                // The folders walked so far, for "..".
                val folders = ArrayList<Entry?>().apply { add(null) }
                var folderId = anchor.substring(1)
                val ids = ArrayList<String>().apply { add(folderId) }
                var file: Entry? = null
                for ((i, name) in names.withIndex()) {
                    if (name == "..") {
                        if (ids.size == 1) return null
                        ids.removeAt(ids.size - 1)
                        folders.removeAt(folders.size - 1)
                        folderId = ids.last()
                        continue
                    }
                    val child = findChild(tree, folderId, name) ?: return null
                    if (child.isDir) {
                        folderId = child.id
                        ids += child.id
                        folders += child
                    } else {
                        // A file can only be the last name.
                        if (i != names.size - 1) return null
                        file = child
                    }
                }
                if (file != null) return Target(DocumentsContract.buildDocumentUriUsingTree(tree, file.id), file, null)
                val id = folderId
                return Target(DocumentsContract.buildDocumentUriUsingTree(tree, id), folders.last() ?: ROOT_ENTRY) {
                    children(tree, id)
                }
            }

            else -> return null
        }
    }

    /** [name] in the folder [folderId]: the exact name, or else one that differs only in case. */
    private fun findChild(tree: Uri, folderId: String, name: String): Entry? {
        val children = children(tree, folderId) ?: return null
        return children.firstOrNull { it.name == name } ?: children.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    private fun children(tree: Uri, folderId: String): List<Entry>? {
        val key = "$tree\n$folderId"
        val now = SystemClock.elapsedRealtime()
        synchronized(listings) {
            listings[key]?.takeIf { now - it.time < LISTING_LIFETIME_MS }?.let { return it.entries }
        }

        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folderId)
        val entries = resolver.query(uri, PROJECTION, null, null, null)?.use { cursor ->
            val list = ArrayList<Entry>()
            while (cursor.moveToNext()) cursor.toEntry()?.let { list += it }
            list
        } ?: return null
        synchronized(listings) { listings[key] = Listing(now, entries) }
        return entries
    }

    private fun query(uri: Uri): Entry? = try {
        resolver.query(uri, PROJECTION, null, null, null)?.use { if (it.moveToFirst()) it.toEntry() else null }
    } catch (e: Exception) {
        Log.w(TAG, "Could not query $uri", e)
        null
    }

    private fun android.database.Cursor.toEntry(): Entry? {
        val id = getString(0) ?: return null
        val name = getString(1) ?: return null
        return Entry(id, name, getString(2) == Document.MIME_TYPE_DIR, getLong(3), getLong(4))
    }

    private fun isTreeDocument(uri: Uri): Boolean {
        val segments = uri.pathSegments
        return segments.size >= 4 && segments[0] == "tree" && segments[2] == "document"
    }

    companion object {
        const val PREFIX = "/saf/"
        private const val TAG = "SafFiles"
        private const val LISTING_LIFETIME_MS = 10_000L

        /** The anchor folder itself, whose details the walk does not have. */
        private val ROOT_ENTRY = Entry("", "", isDir = true, size = 0, modified = 0)

        private val PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
    }
}
