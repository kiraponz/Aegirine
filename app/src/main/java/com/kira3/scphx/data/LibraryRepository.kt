package com.kira3.scphx.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.kira3.scphx.data.db.GameDao
import com.kira3.scphx.data.db.GameEntity
import com.kira3.scphx.emucore.EmulatorCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class Game(
    val uri: Uri,
    val fileName: String,
    val serial: String?,
    /** Whether the core has already tried to read [serial]. */
    val serialRead: Boolean,
    val sizeBytes: Long,
) {
    /** The file name without its extension. */
    val title: String get() = fileName.substringBeforeLast('.')

    /** Upper-case extension, e.g. "CHD". */
    val format: String get() = fileName.substringAfterLast('.', "").uppercase()
}

/**
 * Games in the folder the user picked through SAF. The list lives in Room; [refresh]
 * brings it up to date with the folder.
 */
class LibraryRepository(
    context: Context,
    private val core: EmulatorCore,
    private val preferences: AppPreferences,
    private val dao: GameDao,
) {
    private val contentResolver = context.applicationContext.contentResolver
    private val refreshLock = Mutex()
    private val _refreshing = MutableStateFlow(false)

    /** SAF tree URI of the game folder, or null before one is picked. */
    val gameFolder: Flow<Uri?> = preferences.gameFolder

    /** Cached games, sorted by name. */
    val games: Flow<List<Game>> = dao.observeAll().map { rows ->
        rows.map { it.toGame() }.sortedBy { it.title.lowercase() }
    }

    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /** Keeps read access to [treeUri] across restarts and makes it the game folder. */
    suspend fun setGameFolder(treeUri: Uri) {
        contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        preferences.setGameFolder(treeUri)
    }

    /**
     * Scans the game folder and updates the cache: new and changed images are added, images
     * that are gone are dropped. Serials are read afterwards, one game at a time, so the
     * list appears before the slow part. Throws if the folder can't be read.
     */
    suspend fun refresh() = refreshLock.withLock {
        _refreshing.value = true
        try {
            val folder = gameFolder.first()
            if (folder == null) {
                dao.sync(removed = dao.getAll().map { it.uri }, changed = emptyList())
                return@withLock
            }
            val scanned = withContext(Dispatchers.IO) { scan(folder) }
            val cached = dao.getAll().associateBy { it.uri }
            val scannedUris = scanned.mapTo(HashSet()) { it.uri }
            dao.sync(
                removed = cached.keys.filter { it !in scannedUris },
                changed = scanned.mapNotNull { found ->
                    val old = cached[found.uri]
                    when {
                        old == null -> found
                        // A different file under the same name: read the serial again.
                        old.sizeBytes != found.sizeBytes || old.lastModified != found.lastModified ->
                            found
                        else -> null
                    }
                },
            )
            dao.getAll().filter { !it.serialRead }.forEach { readSerial(it.toGame()) }
        } finally {
            _refreshing.value = false
        }
    }

    /**
     * The game's serial (e.g. "SLUS-20312"), or null for images the core can't read it
     * from. Reads it through the core and caches it if that hasn't happened yet.
     */
    suspend fun readSerial(game: Game): String? {
        if (game.serialRead) return game.serial
        val serial = withContext(Dispatchers.IO) {
            core.initialize()
            core.readGameSerial(game.uri)
        }
        dao.setSerial(game.uri.toString(), serial)
        return serial
    }

    /** Every game image under [treeUri], subfolders included. */
    private fun scan(treeUri: Uri): List<GameEntity> {
        val games = mutableListOf<GameEntity>()
        val pending = ArrayDeque<String>().apply { add(DocumentsContract.getTreeDocumentId(treeUri)) }
        while (pending.isNotEmpty()) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, pending.removeFirst())
            val cursor = contentResolver.query(children, PROJECTION, null, null, null)
                ?: throw IllegalStateException("Could not list $children")
            cursor.use {
                while (it.moveToNext()) {
                    val id = it.getString(0) ?: continue
                    val name = it.getString(1) ?: continue
                    if (it.getString(2) == Document.MIME_TYPE_DIR) {
                        pending.add(id)
                    } else if (name.substringAfterLast('.', "").lowercase() in GAME_EXTENSIONS) {
                        games += GameEntity(
                            uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id).toString(),
                            fileName = name,
                            sizeBytes = it.getLong(3),
                            lastModified = it.getLong(4),
                        )
                    }
                }
            }
        }
        return games
    }

    private fun GameEntity.toGame() = Game(
        uri = Uri.parse(uri),
        fileName = fileName,
        serial = serial,
        serialRead = serialRead,
        sizeBytes = sizeBytes,
    )

    private companion object {
        val PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )

        // Disc images the core opens directly. (.bin/.cue sets are left out for now.)
        val GAME_EXTENSIONS = setOf("iso", "chd", "cso", "zso", "gz", "elf")
    }
}
