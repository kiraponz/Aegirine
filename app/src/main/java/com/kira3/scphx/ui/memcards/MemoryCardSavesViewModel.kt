package com.kira3.scphx.ui.memcards

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kira3.scphx.ScphxApplication
import com.kira3.scphx.data.MemoryCard
import com.kira3.scphx.data.MemoryCardRepository
import com.kira3.scphx.data.MemoryCardResult
import com.kira3.scphx.data.SaveListing
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MemoryCardSavesUiState(
    /** The card itself; null if it is gone. */
    val card: MemoryCard? = null,
    /** Null while the card is being read. */
    val listing: SaveListing? = null,
    /** The other cards, to copy or move saves to. */
    val others: List<MemoryCard> = emptyList(),
    val selected: Set<String> = emptySet(),
    val busy: Boolean = false,
    val message: MemoryCardMessage? = null,
    /** Saves the target already has, while the user decides whether to replace them. */
    val conflict: Conflict? = null,
    val loaded: Boolean = false,
)

/** A copy or move waiting for the user to say what to do with saves that [existing] on [target]. */
data class Conflict(val target: MemoryCard, val move: Boolean, val existing: List<String>)

/** The saves on the card [name]: pick some and copy, move or delete them. */
class MemoryCardSavesViewModel(
    private val name: String,
    private val repository: MemoryCardRepository,
) : ViewModel() {
    private val selected = MutableStateFlow(emptySet<String>())
    private val busy = MutableStateFlow(false)
    private val message = MutableStateFlow<MemoryCardMessage?>(null)
    private val conflict = MutableStateFlow<Conflict?>(null)

    private data class Loaded(val card: MemoryCard?, val listing: SaveListing?, val others: List<MemoryCard>, val loaded: Boolean)

    private val loaded = repository.cards.flatMapLatest { cards ->
        val card = cards.firstOrNull { it.name == name }
        if (card == null) {
            flowOf(Loaded(null, null, cards, true))
        } else {
            kotlinx.coroutines.flow.flow {
                emit(Loaded(card, null, cards.filter { it.name != name }, false))
                emit(Loaded(card, repository.listSaves(card), cards.filter { it.name != name }, true))
            }
        }
    }

    val uiState: StateFlow<MemoryCardSavesUiState> = combine(loaded, selected, busy, message, conflict) { l, selected, busy, message, conflict ->
        MemoryCardSavesUiState(l.card, l.listing, l.others, selected.intersect(l.listing?.saves?.map { it.name }.orEmpty().toSet()), busy, message, conflict, l.loaded)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MemoryCardSavesUiState())

    fun toggle(saveName: String) = selected.update { if (saveName in it) it - saveName else it + saveName }

    fun selectAll(names: List<String>) {
        selected.value = names.toSet()
    }

    fun clearSelection() {
        selected.value = emptySet()
    }

    fun onMessageShown() = message.update { null }

    fun dismissConflict() {
        conflict.value = null
    }

    /** Starts copying (or moving) the selected saves to [target]; asks first if some would be replaced. */
    fun transfer(target: MemoryCard, move: Boolean) {
        val card = uiState.value.card ?: return
        val names = selected.value.toList()
        if (names.isEmpty()) return
        viewModelScope.launch {
            busy.value = true
            val existing = runCatching { repository.conflicts(target, names) }.getOrDefault(emptyList())
            busy.value = false
            if (existing.isEmpty()) run(card, target, names, move) else conflict.value = Conflict(target, move, existing)
        }
    }

    /** Goes on with the pending transfer, replacing the saves that exist or leaving them out. */
    fun resolveConflict(replace: Boolean) {
        val pending = conflict.value ?: return
        val card = uiState.value.card ?: return
        conflict.value = null
        val names = selected.value.toList().let { all -> if (replace) all else all - pending.existing.toSet() }
        if (names.isEmpty()) return
        run(card, pending.target, names, pending.move)
    }

    private fun run(card: MemoryCard, target: MemoryCard, names: List<String>, move: Boolean) {
        viewModelScope.launch {
            busy.value = true
            val result = withContext(NonCancellable) {
                runCatching { repository.transfer(card, target, names, move) }
                    .onFailure { Log.e(TAG, "Copying saves failed", it) }
                    .getOrDefault(MemoryCardResult.Failed)
            }
            busy.value = false
            selected.value = emptySet()
            message.value = result.toMessage(if (move) MemoryCardMessage.SavesMoved else MemoryCardMessage.SavesCopied)
        }
    }

    fun deleteSelected() {
        val card = uiState.value.card ?: return
        val names = selected.value.toList()
        if (names.isEmpty()) return
        viewModelScope.launch {
            busy.value = true
            val result = withContext(NonCancellable) {
                runCatching { repository.deleteSaves(card, names) }
                    .onFailure { Log.e(TAG, "Deleting saves failed", it) }
                    .getOrDefault(MemoryCardResult.Failed)
            }
            busy.value = false
            selected.value = emptySet()
            message.value = result.toMessage(MemoryCardMessage.SavesDeleted)
        }
    }

    private fun MemoryCardResult.toMessage(success: MemoryCardMessage) = when (this) {
        MemoryCardResult.Ok -> success
        MemoryCardResult.Full -> MemoryCardMessage.Full
        MemoryCardResult.Busy -> MemoryCardMessage.Busy
        MemoryCardResult.Exists -> MemoryCardMessage.Exists
        MemoryCardResult.Invalid -> MemoryCardMessage.Invalid
        MemoryCardResult.Failed -> MemoryCardMessage.Failed
    }

    companion object {
        private const val TAG = "MemoryCardSavesVM"

        fun factory(name: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as ScphxApplication).container
                MemoryCardSavesViewModel(name, container.memoryCardRepository)
            }
        }
    }
}
