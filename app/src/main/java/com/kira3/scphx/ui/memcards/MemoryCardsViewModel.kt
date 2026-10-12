package com.kira3.scphx.ui.memcards

import android.net.Uri
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

/** What to tell the user once, after an action. */
enum class MemoryCardMessage {
    Created, Imported, Exported, Renamed, Deleted, Formatted, Exists, Invalid, Failed, Full, Busy,
    SavesCopied, SavesMoved, SavesDeleted,
}

data class MemoryCardsUiState(
    /** Null while the card folder is being read. */
    val cards: List<MemoryCard>? = null,
    val busy: Boolean = false,
    val message: MemoryCardMessage? = null,
)

class MemoryCardsViewModel(private val repository: MemoryCardRepository) : ViewModel() {
    private val busy = MutableStateFlow(false)
    private val message = MutableStateFlow<MemoryCardMessage?>(null)

    val uiState: StateFlow<MemoryCardsUiState> = combine(
        repository.cards.catch {
            Log.e(TAG, "Listing memory cards failed", it)
            emit(emptyList())
        },
        busy,
        message,
    ) { cards, busy, message -> MemoryCardsUiState(cards, busy, message) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MemoryCardsUiState())

    fun create(name: String, folder: Boolean, sizeMb: Int) =
        run(MemoryCardMessage.Created) { repository.create(name, folder, sizeMb) }

    fun import(uri: Uri) = run(MemoryCardMessage.Imported) { repository.import(uri) }

    fun export(card: MemoryCard, uri: Uri) = run(MemoryCardMessage.Exported) {
        if (repository.export(card, uri)) MemoryCardResult.Ok else MemoryCardResult.Failed
    }

    fun rename(card: MemoryCard, newName: String) =
        run(MemoryCardMessage.Renamed) { repository.rename(card, newName) }

    fun format(card: MemoryCard) = run(MemoryCardMessage.Formatted) { repository.format(card) }

    fun delete(card: MemoryCard) = run(MemoryCardMessage.Deleted) { repository.delete(card) }

    fun exportName(card: MemoryCard): String = repository.exportName(card)

    fun onMessageShown() = message.update { null }

    private fun run(success: MemoryCardMessage, action: suspend () -> MemoryCardResult) {
        viewModelScope.launch {
            busy.value = true
            val result = withContext(NonCancellable) {
                runCatching { action() }
                    .onFailure { Log.e(TAG, "Memory card action failed", it) }
                    .getOrDefault(MemoryCardResult.Failed)
            }
            busy.value = false
            message.value = when (result) {
                MemoryCardResult.Ok -> success
                MemoryCardResult.Exists -> MemoryCardMessage.Exists
                MemoryCardResult.Invalid -> MemoryCardMessage.Invalid
                MemoryCardResult.Failed -> MemoryCardMessage.Failed
                MemoryCardResult.Full -> MemoryCardMessage.Full
                MemoryCardResult.Busy -> MemoryCardMessage.Busy
            }
        }
    }

    companion object {
        private const val TAG = "MemoryCardsViewModel"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as ScphxApplication).container
                MemoryCardsViewModel(container.memoryCardRepository)
            }
        }
    }
}
