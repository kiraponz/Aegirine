package com.kira3.ametrine.ui.library

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kira3.ametrine.AmetrineApplication
import com.kira3.ametrine.data.Bios
import com.kira3.ametrine.data.BiosRepository
import com.kira3.ametrine.data.Game
import com.kira3.ametrine.data.LibraryRepository
import com.kira3.ametrine.data.LibrarySort
import com.kira3.ametrine.data.MemoryCardRepository
import com.kira3.ametrine.data.SaveIconChoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A game to start: what EmulationActivity needs from its Intent. */
data class LaunchRequest(val gameUri: Uri, val serial: String?)

/** A game whose settings to open. */
data class GameSettingsRequest(val serial: String, val title: String)

data class LibraryUiState(
    val bios: Bios? = null,
    val hasGameFolder: Boolean = false,
    /** Null until the cached list has been read. */
    val games: List<Game>? = null,
    val refreshing: Boolean = false,
    val sort: LibrarySort = LibrarySort.RecentlyPlayed,
    val query: String = "",
    /** Games in the folder before the search; zero shows the empty-folder state rather than "no matches". */
    val gameCount: Int = 0,
    val launchRequest: LaunchRequest? = null,
    val settingsRequest: GameSettingsRequest? = null,
    val shortcutIconRequest: ShortcutIconRequest? = null,
    val message: LibraryMessage? = null,
)

/** The game has no save of its own to take the shortcut's icon from: [choices] are the saves to pick one from. */
class ShortcutIconRequest(val game: Game, val serial: String?, val choices: List<SaveIconChoice>)

enum class LibraryMessage { ScanFailed, BiosRequired, CoreUnavailable, NoSerial, ShortcutUnsupported }

class LibraryViewModel(
    biosRepository: BiosRepository,
    private val libraryRepository: LibraryRepository,
    private val memoryCardRepository: MemoryCardRepository,
) : ViewModel() {
    private val transient = MutableStateFlow(LibraryUiState())

    private val games = combine(
        libraryRepository.games.catch { emit(emptyList()) },
        libraryRepository.gameFolder,
    ) { games, folder -> if (folder == null) emptyList() else games }

    private val query = MutableStateFlow("")

    /** Favorites first, then games with a suspend state, then the chosen order; only games matching the search. */
    private val shown = combine(games, libraryRepository.sort, query) { games, sort, query ->
        val words = query.trim().lowercase().split(' ').filter { it.isNotEmpty() }
        val matching = games.filter { game ->
            words.all { word ->
                word in game.title.lowercase() ||
                    word in game.fileName.lowercase() ||
                    game.serial?.lowercase()?.contains(word) == true
            }
        }
        Triple(matching.sortedWith(compareByDescending<Game> { it.record.favorite }
            .thenByDescending { it.resumable }
            .then(sort.comparator())), games.size, sort)
    }

    val uiState: StateFlow<LibraryUiState> = combine(
        biosRepository.selected.catch { emit(null) },
        libraryRepository.gameFolder,
        shown,
        libraryRepository.refreshing,
        transient,
    ) { bios, folder, shown, refreshing, transient ->
        transient.copy(
            bios = bios,
            hasGameFolder = folder != null,
            games = shown.first,
            gameCount = shown.second,
            sort = shown.third,
            refreshing = refreshing,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    init {
        refresh()
    }

    /** Looks for new and removed games in the game folder. */
    fun refresh() {
        viewModelScope.launch {
            runCatching { libraryRepository.refresh() }.onFailure {
                Log.e(TAG, "Refreshing the library failed", it)
                showMessage(LibraryMessage.ScanFailed)
            }
        }
    }

    /** Picks up what the :emu process wrote while a game was running. Call when the library comes back. */
    fun reloadRecords() = libraryRepository.reloadRecords()

    fun onSortSelected(sort: LibrarySort) {
        viewModelScope.launch { libraryRepository.setSort(sort) }
    }

    fun onQueryChanged(text: String) {
        query.value = text
        transient.update { it.copy(query = text) }
    }

    fun onFavoriteToggled(game: Game) {
        viewModelScope.launch { libraryRepository.setFavorite(game, !game.record.favorite) }
    }

    fun onRenamed(game: Game, title: String) {
        viewModelScope.launch { libraryRepository.setCustomTitle(game, title) }
    }

    fun onGameFolderPicked(uri: Uri) {
        viewModelScope.launch {
            libraryRepository.setGameFolder(uri)
            refresh()
        }
    }

    fun onGameClicked(game: Game) {
        if (uiState.value.bios == null) {
            showMessage(LibraryMessage.BiosRequired)
            return
        }
        viewModelScope.launch {
            val serial = readSerial(game) ?: return@launch
            runCatching { libraryRepository.markPlayed(game.copy(serial = serial.value)) }
            transient.update { it.copy(launchRequest = LaunchRequest(game.uri, serial.value)) }
        }
    }

    /** Starts the BIOS itself, with no disc: the PS2 system menu. */
    fun onBootBiosClicked() {
        if (uiState.value.bios == null) {
            showMessage(LibraryMessage.BiosRequired)
            return
        }
        // An empty URI reaches the core as an empty path, which it boots as BIOS only.
        transient.update { it.copy(launchRequest = LaunchRequest(Uri.EMPTY, null)) }
    }

    fun onAddShortcutClicked(game: Game) {
        viewModelScope.launch {
            val serial = readSerial(game) ?: return@launch
            // The game's own save icon if there is one; otherwise the user picks among the saves.
            val own = serial.value?.let { memoryCardRepository.saveIcons(SHORTCUT_ICON_SIZE, SHORTCUT_ICON_FILL, it) }
                ?.firstOrNull()
            if (own != null) {
                pinShortcut(game, serial.value, own.icon)
                return@launch
            }
            val choices = memoryCardRepository.saveIcons(PREVIEW_ICON_SIZE, 1f)
            if (choices.isEmpty()) {
                pinShortcut(game, serial.value, null)
            } else {
                transient.update { it.copy(shortcutIconRequest = ShortcutIconRequest(game, serial.value, choices)) }
            }
        }
    }

    /** [choice] is the save whose icon the shortcut gets, or null for the app's own icon. */
    fun onShortcutIconChosen(choice: SaveIconChoice?) {
        val request = uiState.value.shortcutIconRequest ?: return
        transient.update { it.copy(shortcutIconRequest = null) }
        viewModelScope.launch {
            val icon = choice?.let {
                memoryCardRepository.saveIcon(it.cardName, it.saveName, SHORTCUT_ICON_SIZE, SHORTCUT_ICON_FILL)
            }
            pinShortcut(request.game, request.serial, icon)
        }
    }

    fun onShortcutIconDismissed() = transient.update { it.copy(shortcutIconRequest = null) }

    private fun pinShortcut(game: Game, serial: String?, icon: Bitmap?) {
        val pinned = runCatching { libraryRepository.requestPinnedShortcut(game, serial, icon) }
            .onFailure { Log.e(TAG, "Pinning a shortcut for ${game.fileName} failed", it) }
            .getOrDefault(false)
        if (!pinned) showMessage(LibraryMessage.ShortcutUnsupported)
    }

    fun onGameSettingsClicked(game: Game) {
        viewModelScope.launch {
            val serial = readSerial(game) ?: return@launch
            // Per-game settings are stored by serial.
            val value = serial.value
            if (value == null) {
                showMessage(LibraryMessage.NoSerial)
            } else {
                transient.update { it.copy(settingsRequest = GameSettingsRequest(value, game.title)) }
            }
        }
    }

    fun onLaunchHandled() = transient.update { it.copy(launchRequest = null) }

    fun onSettingsRequestHandled() = transient.update { it.copy(settingsRequest = null) }

    fun onMessageShown() = transient.update { it.copy(message = null) }

    /** The game's serial boxed (it can be null), or null after telling the user the core is unavailable. */
    private suspend fun readSerial(game: Game): Serial? {
        val result = runCatching { libraryRepository.readSerial(game) }
            .onFailure { Log.e(TAG, "Reading serial of ${game.fileName} failed", it) }
        if (result.exceptionOrNull() is IllegalStateException) {
            showMessage(LibraryMessage.CoreUnavailable)
            return null
        }
        return Serial(result.getOrNull())
    }

    private class Serial(val value: String?)

    private fun showMessage(message: LibraryMessage) = transient.update { it.copy(message = message) }

    companion object {
        private const val TAG = "LibraryViewModel"

        /**
         * An adaptive icon is 108dp and the launcher shows a circle of 72dp in the middle at most,
         * so the square picture's corners must stay inside that: 108 * 0.46 * sqrt(2) < 72.
         */
        private const val SHORTCUT_ICON_SIZE = 432
        private const val SHORTCUT_ICON_FILL = 0.46f
        private const val PREVIEW_ICON_SIZE = 128

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as AmetrineApplication).container
                LibraryViewModel(container.biosRepository, container.libraryRepository, container.memoryCardRepository)
            }
        }
    }
}
