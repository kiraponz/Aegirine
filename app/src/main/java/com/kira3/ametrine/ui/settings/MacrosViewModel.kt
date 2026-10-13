package com.kira3.ametrine.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kira3.ametrine.AmetrineApplication
import com.kira3.ametrine.data.MacroRepository
import com.kira3.ametrine.data.macro.Macro
import com.kira3.ametrine.data.macro.MemoryCondition
import com.kira3.ametrine.emulation.MacroEditActions
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A game's macros for the settings page: the same changes the in-game menu makes, without a game running. */
class MacrosViewModel(
    private val serial: String,
    private val repository: MacroRepository,
) : ViewModel(), MacroEditActions {
    /** Null while the macros are being read. */
    val macros: StateFlow<List<Macro>?> = repository.macros(serial)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    override fun rename(macro: Macro, name: String) = edit(macro.id) { it.copy(name = name) }
    override fun setLoop(macro: Macro, loop: Boolean) = edit(macro.id) { it.copy(loop = loop) }
    override fun setStartCondition(macro: Macro, condition: MemoryCondition?) = edit(macro.id) { it.copy(startWhen = condition) }
    override fun setStopCondition(macro: Macro, condition: MemoryCondition?) = edit(macro.id) { it.copy(stopWhen = condition) }
    override fun replace(macro: Macro) = edit(macro.id) { macro }
    override fun delete(macro: Macro) = edit(macro.id) { null }

    private fun edit(id: String, transform: (Macro) -> Macro?) {
        viewModelScope.launch {
            repository.update(serial) { list -> list.mapNotNull { if (it.id == id) transform(it) else it } }
        }
    }

    companion object {
        fun factory(serial: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as AmetrineApplication
                MacrosViewModel(serial, application.container.macroRepository)
            }
        }
    }
}
