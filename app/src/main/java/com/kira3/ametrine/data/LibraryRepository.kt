package com.kira3.ametrine.data

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.kira3.ametrine.R
import com.kira3.ametrine.data.db.GameDao
import com.kira3.ametrine.data.db.GameEntity
import com.kira3.ametrine.emucore.EmulatorCore
import com.kira3.ametrine.emulation.EmulationActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
    /** The GameDB name for [serial], if the database knows the game. */
    val dbTitle: String? = null,
    val lastModified: Long = 0,
    val record: GameRecord = GameRecord(),
    /** Whether "Exit and resume later" left a state for the next start. */
    val resumable: Boolean = false,
) {
    /** The user's title, else the GameDB name, else the file name without its extension. */
    val title: String get() = record.customTitle ?: dbTitle ?: fileName.substringBeforeLast('.')

    /** Key in [GameRecordRepository]. */
    val recordKey: String get() = GameRecordRepository.keyOf(serial, uri.toString())

    /** Upper-case extension, e.g. "CHD". */
    val format: String get() = fileName.substringAfterLast('.', "").uppercase()
}

/** How the library orders its games. Ties fall back to the title. */
enum class LibrarySort {
    RecentlyPlayed, Title, PlayTime, RecentlyAdded, Size;

    fun comparator(): Comparator<Game> {
        val byTitle = compareBy<Game> { it.title.lowercase() }
        return when (this) {
            Title -> byTitle
            RecentlyPlayed -> compareByDescending<Game> { it.record.lastPlayed }.then(byTitle)
            PlayTime -> compareByDescending<Game> { it.record.playSeconds }.then(byTitle)
            RecentlyAdded -> compareByDescending<Game> { it.lastModified }.then(byTitle)
            Size -> compareByDescending<Game> { it.sizeBytes }.then(byTitle)
        }
    }

    companion object {
        fun fromName(name: String?): LibrarySort = entries.firstOrNull { it.name == name } ?: RecentlyPlayed
    }
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
    private val records: GameRecordRepository,
) {
    private val contentResolver = context.applicationContext.contentResolver
    private val refreshLock = Mutex()
    private val _refreshing = MutableStateFlow(false)

    /** SAF tree URI of the game folder, or null before one is picked. */
    val gameFolder: Flow<Uri?> = preferences.gameFolder

    private val resumeChanges = MutableStateFlow(0)

    /** Cached games with their records, in no particular order; see [LibrarySort]. */
    val games: Flow<List<Game>> = combine(dao.observeAll(), records.records, resumeChanges) { rows, records, _ ->
        val resumable = withContext(Dispatchers.IO) { core.resumableSerials() }
        rows.map { row ->
            val game = row.toGame()
            game.copy(record = records[game.recordKey] ?: GameRecord(), resumable = game.serial in resumable)
        }
    }

    /** Re-reads what the :emu process wrote (play time, suspend states) while the game was running. */
    fun reloadRecords() {
        records.refresh()
        resumeChanges.value++
    }

    suspend fun setFavorite(game: Game, favorite: Boolean) =
        records.update(game.recordKey) { it.copy(favorite = favorite) }

    /** A blank [title] goes back to the GameDB name or file name. */
    suspend fun setCustomTitle(game: Game, title: String) =
        records.update(game.recordKey) { it.copy(customTitle = title.trim().ifEmpty { null }) }

    /** Called when a game is started, so "recently played" is right before the first play time arrives. */
    suspend fun markPlayed(game: Game) = records.update(game.recordKey) { it.copy(lastPlayed = System.currentTimeMillis()) }

    val sort: Flow<LibrarySort> = preferences.librarySort.map { LibrarySort.fromName(it) }

    suspend fun setSort(sort: LibrarySort) = preferences.setLibrarySort(sort.name)

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
        val (serial, dbTitle) = withContext(Dispatchers.IO) {
            core.initialize()
            val serial = core.readGameSerial(game.uri)
            serial to serial?.let { core.gameDbTitle(it) }
        }
        dao.setSerial(game.uri.toString(), serial, dbTitle)
        return serial
    }

    private val appContext = context.applicationContext

    /**
     * Asks the launcher to pin a shortcut that starts [game] directly, with [icon] (a drawn
     * disc if null). False if the launcher can't pin shortcuts. The shortcut keeps working as long
     * as the game folder's access does.
     */
    fun requestPinnedShortcut(game: Game, serial: String?, icon: Bitmap?): Boolean {
        val manager = appContext.getSystemService(ShortcutManager::class.java)
        if (manager == null || !manager.isRequestPinShortcutSupported) return false
        val shortcut = ShortcutInfo.Builder(appContext, "game:${game.recordKey}")
            .setShortLabel(game.title)
            .setLongLabel(game.title)
            .setIcon(icon?.let(Icon::createWithAdaptiveBitmap) ?: Icon.createWithResource(appContext, R.drawable.ic_shortcut_disc))
            .setIntent(EmulationActivity.shortcutIntent(appContext, game.uri, serial))
            .build()
        return manager.requestPinShortcut(shortcut, null)
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
        dbTitle = dbTitle,
        lastModified = lastModified,
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
