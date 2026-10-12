package com.kira3.scphx.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kira3.scphx.R
import com.kira3.scphx.ScphxApplication
import com.kira3.scphx.data.Game
import com.kira3.scphx.data.LibraryRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** The library's games that have a serial, which is what a game's settings and cheats are keyed by. */
class CheatGamesViewModel(repository: LibraryRepository) : ViewModel() {
    /** Null while the library is being read. */
    val games: StateFlow<List<Game>?> = repository.games
        .map { list -> list.filter { it.serial != null } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as ScphxApplication
                CheatGamesViewModel(application.container.libraryRepository)
            }
        }
    }
}

/** Which game's cheats to edit, from the global settings. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CheatGamesScreen(
    onPick: (serial: String, title: String) -> Unit,
    onBack: () -> Unit,
    viewModel: CheatGamesViewModel = viewModel(factory = CheatGamesViewModel.Factory),
) {
    val games by viewModel.games.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.settings_cheats)) },
                subtitle = { Text(stringResource(R.string.cheats_pick_game)) },
                navigationIcon = { BackButton(onBack) },
                colors = settingsTopBarColors(),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        val list = games ?: return@Scaffold
        LazyColumn(
            contentPadding = listPadding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            itemsIndexed(list, key = { _, game -> game.uri.toString() }) { index, game ->
                SegmentedListItem(
                    onClick = { onPick(game.serial!!, game.title) },
                    shapes = segmentedShapes(index, list.size),
                    colors = settingsListColors(),
                    modifier = Modifier.animateItem(),
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                    supportingContent = { Text(game.serial!!) },
                ) { Text(game.title, maxLines = 2) }
            }
            if (list.isEmpty()) {
                item(key = "empty") {
                    Text(
                        stringResource(R.string.cheats_pick_game_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}
