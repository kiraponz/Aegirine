package com.kira3.scphx.ui.library

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kira3.scphx.ScphxApplication
import com.kira3.scphx.data.Bios
import com.kira3.scphx.data.BiosRepository
import com.kira3.scphx.data.Game
import com.kira3.scphx.data.LibraryRepository
import com.kira3.scphx.data.LibrarySort
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
    val message: LibraryMessage? = null,
)

enum class LibraryMessage { ScanFailed, BiosRequired, CoreUnavailable, NoSerial }

class LibraryViewModel(
    biosRepository: BiosRepository,
    private val libraryRepository: LibraryRepository,
) : ViewModel() {
    private val transient = MutableStateFlow(LibraryUiState())

    private val games = combine(
        libraryRepository.games.catch { emit(emptyList()) },
        libraryRepository.gameFolder,
    ) { games, folder -> if (folder == null) emptyList() else games }

    private val query = MutableStateFlow("")

    /** Favorites first, then the chosen order; only games matching the search. */
    private val shown = combine(games, libraryRepository.sort, query) { games, sort, query ->
        val words = query.trim().lowercase().split(' ').filter { it.isNotEmpty() }
        val matching = games.filter { game ->
            words.all { word ->
                word in game.title.lowercase() ||
                    word in game.fileName.lowercase() ||
                    game.serial?.lowercase()?.contains(word) == true
            }
        }
        Triple(matching.sortedWith(compareByDescending<Game> { it.record.favorite }.then(sort.comparator())), games.size, sort)
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

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as ScphxApplication).container
                LibraryViewModel(container.biosRepository, container.libraryRepository)
            }
        }
    }
}
