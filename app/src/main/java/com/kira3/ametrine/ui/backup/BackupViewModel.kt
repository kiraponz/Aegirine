package com.kira3.ametrine.ui.backup

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kira3.ametrine.AmetrineApplication
import com.kira3.ametrine.data.BackupContents
import com.kira3.ametrine.data.BackupPart
import com.kira3.ametrine.data.BackupPartInfo
import com.kira3.ametrine.data.BackupRepository
import com.kira3.ametrine.data.BackupResult
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class BackupMessage { BackedUp, Restored, NotABackup, Failed, Busy }

/** A backup file picked to restore from, and which of its parts to restore. */
data class RestorePlan(val uri: Uri, val contents: BackupContents, val selected: Set<BackupPart>)

data class BackupUiState(
    /** What the device holds; null while it is being counted. */
    val available: List<BackupPartInfo>? = null,
    val selected: Set<BackupPart> = emptySet(),
    val restore: RestorePlan? = null,
    val busy: Boolean = false,
    val message: BackupMessage? = null,
)

class BackupViewModel(private val repository: BackupRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(BackupUiState())
    val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val available = repository.available()
            _uiState.update { it.copy(available = available, selected = available.map { info -> info.part }.toSet()) }
        }
    }

    fun toggle(part: BackupPart) = _uiState.update {
        it.copy(selected = if (part in it.selected) it.selected - part else it.selected + part)
    }

    fun fileName(): String = "ametrine-backup-${SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())}.zip"

    fun backUp(uri: Uri) = run {
        when (repository.export(uri, _uiState.value.selected)) {
            BackupResult.Ok -> BackupMessage.BackedUp
            BackupResult.Busy -> BackupMessage.Busy
            else -> BackupMessage.Failed
        }
    }

    /** Reads the picked file, for the restore dialog. */
    fun open(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true) }
            val contents = repository.inspect(uri)
            _uiState.update {
                if (contents == null || contents.parts.isEmpty()) {
                    it.copy(busy = false, message = BackupMessage.NotABackup)
                } else {
                    it.copy(busy = false, restore = RestorePlan(uri, contents, contents.parts.map { info -> info.part }.toSet()))
                }
            }
        }
    }

    fun toggleRestore(part: BackupPart) = _uiState.update { state ->
        val plan = state.restore ?: return@update state
        state.copy(restore = plan.copy(selected = if (part in plan.selected) plan.selected - part else plan.selected + part))
    }

    fun cancelRestore() = _uiState.update { it.copy(restore = null) }

    fun restore() {
        val plan = _uiState.value.restore ?: return
        _uiState.update { it.copy(restore = null) }
        run {
            when (repository.restore(plan.uri, plan.selected)) {
                BackupResult.Ok -> BackupMessage.Restored
                BackupResult.Busy -> BackupMessage.Busy
                BackupResult.Invalid -> BackupMessage.NotABackup
                BackupResult.Failed -> BackupMessage.Failed
            }
        }
    }

    fun onMessageShown() = _uiState.update { it.copy(message = null) }

    private fun run(action: suspend () -> BackupMessage) {
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true) }
            // A half-written restore is worse than a finished one, so leaving the screen does not stop it.
            val message = withContext(NonCancellable) { action() }
            val available = repository.available()
            _uiState.update { it.copy(busy = false, message = message, available = available) }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as AmetrineApplication).container
                BackupViewModel(container.backupRepository)
            }
        }
    }
}
