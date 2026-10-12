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
import com.kira3.scphx.data.GameFile
import com.kira3.scphx.data.LibraryRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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
    /** Null while the folder is being scanned. */
    val games: List<GameFile>? = null,
    val launchRequest: LaunchRequest? = null,
    val settingsRequest: GameSettingsRequest? = null,
    val message: LibraryMessage? = null,
)

enum class LibraryMessage { InvalidBios, ScanFailed, BiosRequired, CoreUnavailable, NoSerial }

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(
    private val biosRepository: BiosRepository,
    private val libraryRepository: LibraryRepository,
) : ViewModel() {
    private val transient = MutableStateFlow(LibraryUiState())

    private val games = libraryRepository.gameFolder.flatMapLatest { folder ->
        if (folder == null) {
            flowOf(emptyList())
        } else {
            flow<List<GameFile>?> {
                emit(null)
                emit(libraryRepository.scan(folder))
            }.catch {
                Log.e(TAG, "Scanning $folder failed", it)
                showMessage(LibraryMessage.ScanFailed)
                emit(emptyList())
            }
        }
    }

    val uiState: StateFlow<LibraryUiState> = combine(
        biosRepository.selected.catch { emit(null) },
        libraryRepository.gameFolder,
        games,
        transient,
    ) { bios, folder, games, transient ->
        transient.copy(bios = bios, hasGameFolder = folder != null, games = games)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    fun onBiosPicked(uri: Uri) {
        viewModelScope.launch {
            val bios = runCatching { biosRepository.import(uri) }
                .onFailure { Log.e(TAG, "Importing BIOS failed", it) }
                .getOrNull()
            if (bios == null) showMessage(LibraryMessage.InvalidBios)
        }
    }

    fun onGameFolderPicked(uri: Uri) {
        viewModelScope.launch { libraryRepository.setGameFolder(uri) }
    }

    fun onGameClicked(game: GameFile) {
        if (uiState.value.bios == null) {
            showMessage(LibraryMessage.BiosRequired)
            return
        }
        viewModelScope.launch {
            val serial = runCatching { libraryRepository.readSerial(game) }
                .onFailure { Log.e(TAG, "Reading serial of ${game.name} failed", it) }
            if (serial.exceptionOrNull() is IllegalStateException) {
                showMessage(LibraryMessage.CoreUnavailable)
                return@launch
            }
            transient.update { it.copy(launchRequest = LaunchRequest(game.uri, serial.getOrNull())) }
        }
    }

    fun onGameSettingsClicked(game: GameFile) {
        viewModelScope.launch {
            val serial = runCatching { libraryRepository.readSerial(game) }
                .onFailure { Log.e(TAG, "Reading serial of ${game.name} failed", it) }
            when {
                serial.exceptionOrNull() is IllegalStateException -> showMessage(LibraryMessage.CoreUnavailable)
                // Per-game settings are stored by serial.
                serial.getOrNull() == null -> showMessage(LibraryMessage.NoSerial)
                else -> transient.update {
                    it.copy(settingsRequest = GameSettingsRequest(serial.getOrThrow()!!, game.name.substringBeforeLast('.')))
                }
            }
        }
    }

    fun onLaunchHandled() = transient.update { it.copy(launchRequest = null) }

    fun onSettingsRequestHandled() = transient.update { it.copy(settingsRequest = null) }

    fun onMessageShown() = transient.update { it.copy(message = null) }

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
