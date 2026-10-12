package com.kira3.scphx.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.kira3.scphx.emucore.EmulatorCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

data class GameFile(val name: String, val uri: Uri)

/**
 * Finds games in the folder the user picked through SAF.
 *
 * Provisional: scans on every call. The Room-backed cache comes with the real library.
 */
class LibraryRepository(
    context: Context,
    private val core: EmulatorCore,
    private val preferences: AppPreferences,
) {
    private val contentResolver = context.applicationContext.contentResolver

    /** SAF tree URI of the game folder, or null before one is picked. */
    val gameFolder: Flow<Uri?> = preferences.gameFolder

    /** Keeps read access to [treeUri] across restarts and makes it the game folder. */
    suspend fun setGameFolder(treeUri: Uri) {
        contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        preferences.setGameFolder(treeUri)
    }

    /** Every game image under [treeUri], subfolders included, sorted by name. */
    suspend fun scan(treeUri: Uri): List<GameFile> = withContext(Dispatchers.IO) {
        val games = mutableListOf<GameFile>()
        val pending = ArrayDeque<String>().apply { add(DocumentsContract.getTreeDocumentId(treeUri)) }
        while (pending.isNotEmpty()) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, pending.removeFirst())
            contentResolver.query(children, PROJECTION, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: continue
                    if (cursor.getString(2) == Document.MIME_TYPE_DIR) {
                        pending.add(id)
                    } else if (name.substringAfterLast('.', "").lowercase() in GAME_EXTENSIONS) {
                        games += GameFile(name, DocumentsContract.buildDocumentUriUsingTree(treeUri, id))
                    }
                }
            }
        }
        games.sortedBy { it.name.lowercase() }
    }

    /** The game's serial (e.g. "SLUS-20312"), or null for formats the core can't read it from. */
    suspend fun readSerial(game: GameFile): String? = withContext(Dispatchers.IO) {
        core.initialize()
        core.readGameSerial(game.uri)
    }

    private companion object {
        val PROJECTION = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)

        // Disc images the core opens directly. (.bin/.cue sets are left out for now.)
        val GAME_EXTENSIONS = setOf("iso", "chd", "cso", "zso", "gz", "elf")
    }
}
