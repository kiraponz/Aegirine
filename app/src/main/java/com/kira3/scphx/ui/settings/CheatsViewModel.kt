package com.kira3.scphx.ui.settings

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kira3.scphx.ScphxApplication
import com.kira3.scphx.data.CheatRepository
import com.kira3.scphx.emucore.Cheat
import com.kira3.scphx.emucore.Pnach
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class CheatsViewModel(
    private val serial: String,
    private val repository: CheatRepository,
    private val application: Application,
) : ViewModel() {
    /** Null while the cheats are being read. */
    val cheats: StateFlow<List<Cheat>?> = repository.cheats(serial)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** How many cheats the last import added; -1 when the file had none. Cleared by [onImportShown]. */
    val imported = MutableStateFlow<Int?>(null)

    fun setEnabled(id: String, enabled: Boolean) = edit { list ->
        list.map { if (it.id == id) it.copy(enabled = enabled) else it }
    }

    /** Adds a cheat, or replaces the one with [id]. */
    fun save(id: String?, name: String, code: String) = edit { list ->
        if (id == null) {
            list + Cheat(UUID.randomUUID().toString(), name.trim(), code.trim())
        } else {
            list.map { if (it.id == id) it.copy(name = name.trim(), code = code.trim()) else it }
        }
    }

    fun delete(id: String) = edit { list -> list.filterNot { it.id == id } }

    fun import(uri: Uri) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { application.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }
                    .onFailure { Log.w(TAG, "Could not read $uri", it) }
                    .getOrNull()
            }
            val found = text?.let { Pnach.parse(it) { UUID.randomUUID().toString() } }.orEmpty()
            if (found.isNotEmpty()) repository.update(serial) { it + found }
            imported.value = if (found.isEmpty()) -1 else found.size
        }
    }

    fun onImportShown() {
        imported.value = null
    }

    private fun edit(transform: (List<Cheat>) -> List<Cheat>) {
        viewModelScope.launch { repository.update(serial, transform) }
    }

    companion object {
        private const val TAG = "CheatsViewModel"

        fun factory(serial: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as ScphxApplication
                CheatsViewModel(serial, application.container.cheatRepository, application)
            }
        }
    }
}
