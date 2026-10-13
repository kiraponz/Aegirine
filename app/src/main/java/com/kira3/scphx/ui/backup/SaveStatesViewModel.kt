package com.kira3.scphx.ui.backup

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kira3.scphx.ScphxApplication
import com.kira3.scphx.data.LibraryRepository
import com.kira3.scphx.data.SaveStateFile
import com.kira3.scphx.data.SaveStateImport
import com.kira3.scphx.data.SaveStateRepository
import com.kira3.scphx.data.SaveStateResult
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

/** A state file being imported, waiting for the slot to put it in. */
data class PendingImport(val file: SaveStateImport, val title: String?, val takenSlots: Set<Int>)

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

    private val titles = MutableStateFlow<Map<String, String>>(emptyMap())

    val uiState: StateFlow<SaveStatesUiState> = combine(
        repository.states.catch { emit(emptyList()) },
        libraryRepository.games,
        busy,
        message,
        pending,
    ) { states, library, busy, message, pending ->
        val names = library.filter { it.serial != null }.associate { it.serial!! to it.title }
        titles.value = names
        val games = states.groupBy { it.serial }
            .map { (serial, list) -> GameStates(serial, names[serial], list.sortedBy { it.slot }) }
            .sortedWith(compareBy({ it.title == null }, { it.title ?: it.serial }))
        SaveStatesUiState(games, pending, busy, message)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SaveStatesUiState())

    fun thumbnail(state: SaveStateFile): ByteArray? = repository.thumbnail(state)

    fun export(state: SaveStateFile, uri: Uri) = run(SaveStateMessage.Exported) { repository.export(state, uri) }

    fun delete(state: SaveStateFile) = run(SaveStateMessage.Deleted) { repository.delete(state) }

    /** Reads the picked file's name; asks for a slot if it is a state. */
    fun open(uri: Uri) {
        viewModelScope.launch {
            val file = repository.inspect(uri)
            if (file == null) {
                message.value = SaveStateMessage.NotAState
                return@launch
            }
            val taken = uiState.value.games.orEmpty()
                .filter { it.serial == file.serial }
                .flatMap { it.states }
                .filter { it.crc.equals(file.crc, ignoreCase = true) }
                .map { it.slot }
                .toSet()
            pending.value = PendingImport(file, titles.value[file.serial], taken)
        }
    }

    fun cancelImport() {
        pending.value = null
    }

    fun import(slot: Int) {
        val file = pending.value?.file ?: return
        pending.value = null
        run(SaveStateMessage.Imported) { repository.import(file, slot) }
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
                val container = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as ScphxApplication).container
                SaveStatesViewModel(container.saveStateRepository, container.libraryRepository)
            }
        }
    }
}
