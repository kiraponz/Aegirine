package com.kira3.scphx.ui.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kira3.scphx.ScphxApplication
import com.kira3.scphx.data.Bios
import com.kira3.scphx.data.BiosRepository
import com.kira3.scphx.data.SettingsRepository
import com.kira3.scphx.emucore.settings.EmulatorSettings
import com.kira3.scphx.emucore.settings.PerformancePreset
import com.kira3.scphx.emucore.settings.SettingsValues
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val values: SettingsValues,
    /** Paths the game sets for itself. Always empty in the global settings. */
    val overrides: Set<String>,
    /** Installed BIOS files, for a game's BIOS choice. */
    val bioses: List<Bios>,
)

/** The global settings when [serial] is null, else that game's settings. */
class SettingsViewModel(
    private val serial: String?,
    private val settingsRepository: SettingsRepository,
    biosRepository: BiosRepository,
) : ViewModel() {
    private val bioses = flow {
        emit(emptyList())
        if (serial != null) emit(biosRepository.installed())
    }.catch {
        Log.e(TAG, "Listing BIOS files failed", it)
        emit(emptyList())
    }

    private val settings = if (serial == null) {
        settingsRepository.global.map { SettingsValues(it) to emptySet<String>() }
    } else {
        settingsRepository.game(serial).map { SettingsValues(it.effective) to it.overrides.paths }
    }

    /** Null until the settings have been read. */
    val uiState: StateFlow<SettingsUiState?> = combine(settings, bioses) { (values, overrides), bioses ->
        SettingsUiState(values, overrides, bioses)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun set(path: String, value: Any) = edit { SettingsValues(it).with(path, value) }

    fun applyPreset(preset: PerformancePreset) = edit { preset.applyTo(it) }

    /** Makes the values at [paths] follow the global settings again. Only for a game. */
    fun reset(paths: Collection<String>) {
        val serial = serial ?: return
        viewModelScope.launch { withContext(NonCancellable) { settingsRepository.resetGame(serial, paths) } }
    }

    // Saving must not be cut short when the screen closes right after a change.
    private fun edit(transform: (EmulatorSettings) -> EmulatorSettings) {
        viewModelScope.launch {
            withContext(NonCancellable) {
                if (serial == null) settingsRepository.updateGlobal(transform) else settingsRepository.updateGame(serial, transform)
            }
        }
    }

    companion object {
        private const val TAG = "SettingsViewModel"

        fun factory(serial: String?): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as ScphxApplication).container
                SettingsViewModel(serial, container.settingsRepository, container.biosRepository)
            }
        }
    }
}
