package com.kira3.ametrine.ui.backup

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kira3.ametrine.AmetrineApplication
import com.kira3.ametrine.data.Game
import com.kira3.ametrine.data.LibraryRepository
import com.kira3.ametrine.data.SaveStateFile
import com.kira3.ametrine.data.SaveStateImport
import com.kira3.ametrine.data.SaveStateRepository
import com.kira3.ametrine.data.SaveStateResult
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SaveStateMessage { Exported, Imported, Deleted, NotAState, Failed, Busy }

/** The states of one game. [title] is the library's name for the serial, if the game is in the library. */
class GameStates(val serial: String, val title: String?, val states: List<SaveStateFile>)

/** A game a state file can be imported for. */
data class ImportGame(val serial: String, val title: String?, val takenSlots: Set<Int>)

/**
 * A state file being imported, waiting for the game and slot to put it in; [serial] is the game its header
 * names and [slot] what its file name suggests.
 */
data class PendingImport(val file: SaveStateImport, val games: List<ImportGame>, val serial: String?, val slot: Int?)

data class SaveStatesUiState(
    /** Null while the folder is being read. */
    val games: List<GameStates>? = null,
    val pending: PendingImport? = null,
    val busy: Boolean = false,
    val message: SaveStateMessage? = null,
)

class SaveStatesViewModel(
    private val repository: SaveStateRepository,
    libraryRepository: LibraryRepository,
) : ViewModel() {
    private val busy = MutableStateFlow(false)
    private val message = MutableStateFlow<SaveStateMessage?>(null)
    private val pending = MutableStateFlow<PendingImport?>(null)

    private val libraryGames = MutableStateFlow<List<Game>>(emptyList())

    val uiState: StateFlow<SaveStatesUiState> = combine(
        repository.states.catch { emit(emptyList()) },
        libraryRepository.games,
        busy,
        message,
        pending,
    ) { states, library, busy, message, pending ->
        libraryGames.value = library
        val names = library.filter { it.serial != null }.associate { it.serial!! to it.title }
        val games = states.groupBy { it.serial }
            .map { (serial, list) -> GameStates(serial, names[serial], list.sortedBy { it.slot }) }
            .sortedWith(compareBy({ it.title == null }, { it.title ?: it.serial }))
        SaveStatesUiState(games, pending, busy, message)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SaveStatesUiState())

    fun thumbnail(state: SaveStateFile): ByteArray? = repository.thumbnail(state)

    fun export(state: SaveStateFile, uri: Uri) = run(SaveStateMessage.Exported) { repository.export(state, uri) }

    fun delete(state: SaveStateFile) = run(SaveStateMessage.Deleted) { repository.delete(state) }

    /** Reads the picked file; asks for the game and slot, with the game it was saved from as the choice. */
    fun open(uri: Uri) {
        viewModelScope.launch {
            val file = repository.inspect(uri)
            if (file == null) {
                message.value = SaveStateMessage.NotAState
                return@launch
            }
            val states = uiState.value.games.orEmpty().flatMap { it.states }
            val serials = (libraryGames.value.mapNotNull { it.serial } + states.map { it.serial } + listOfNotNull(file.serial)).distinct()
            val titles = libraryGames.value.filter { it.serial != null }.associate { it.serial!! to it.title }
            val games = serials.map { serial ->
                ImportGame(serial, titles[serial], states.filter { it.serial == serial }.map { it.slot }.toSet())
            }.sortedWith(compareBy({ it.serial != file.serial }, { it.title == null }, { it.title ?: it.serial }))
            pending.value = PendingImport(file, games, file.serial, file.slot)
        }
    }

    fun cancelImport() {
        pending.value = null
    }

    fun import(game: ImportGame, slot: Int) {
        val file = pending.value?.file ?: return
        pending.value = null
        run(SaveStateMessage.Imported) { repository.import(file.uri, game.serial, slot) }
    }

    fun onMessageShown() {
        message.value = null
    }

    private fun run(success: SaveStateMessage, action: suspend () -> SaveStateResult) {
        viewModelScope.launch {
            busy.value = true
            val result = withContext(NonCancellable) { runCatching { action() }.getOrDefault(SaveStateResult.Failed) }
            busy.value = false
            message.value = when (result) {
                SaveStateResult.Ok -> success
                SaveStateResult.Invalid -> SaveStateMessage.NotAState
                SaveStateResult.Failed -> SaveStateMessage.Failed
                SaveStateResult.Busy -> SaveStateMessage.Busy
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as AmetrineApplication).container
                SaveStatesViewModel(container.saveStateRepository, container.libraryRepository)
            }
        }
    }
}
