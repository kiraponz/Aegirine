package com.kira3.scphx.ui.library

import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kira3.scphx.R
import com.kira3.scphx.data.Game
import com.kira3.scphx.emulation.EmulationActivity
import com.kira3.scphx.ui.theme.ScphxTheme

@Composable
fun LibraryScreen(
    onOpenSettings: () -> Unit,
    onOpenBios: () -> Unit,
    onOpenGameSettings: (serial: String, title: String) -> Unit,
    viewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val pickGameFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::onGameFolderPicked)
    }

    LaunchedEffect(state.launchRequest) {
        val request = state.launchRequest ?: return@LaunchedEffect
        context.startActivity(EmulationActivity.intent(context, request.gameUri, request.serial))
        viewModel.onLaunchHandled()
    }

    LaunchedEffect(state.settingsRequest) {
        val request = state.settingsRequest ?: return@LaunchedEffect
        onOpenGameSettings(request.serial, request.title)
        viewModel.onSettingsRequestHandled()
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val messageText = state.message?.let { stringResource(it.textRes) }
    LaunchedEffect(state.message) {
        if (messageText != null) {
            snackbarHostState.showSnackbar(messageText)
            viewModel.onMessageShown()
        }
    }

    LibraryContent(
        state = state,
        snackbarHostState = snackbarHostState,
        onOpenSettings = onOpenSettings,
        onOpenBios = onOpenBios,
        onPickGameFolder = { pickGameFolder.launch(null) },
        onRefresh = viewModel::refresh,
        onBootBios = viewModel::onBootBiosClicked,
        onGameClick = viewModel::onGameClicked,
        onGameSettingsClick = viewModel::onGameSettingsClicked,
    )
}

private val LibraryMessage.textRes: Int
    get() = when (this) {
        LibraryMessage.ScanFailed -> R.string.library_message_scan_failed
        LibraryMessage.BiosRequired -> R.string.library_message_bios_required
        LibraryMessage.CoreUnavailable -> R.string.library_message_core_unavailable
        LibraryMessage.NoSerial -> R.string.library_message_no_serial
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryContent(
    state: LibraryUiState,
    snackbarHostState: SnackbarHostState,
    onOpenSettings: () -> Unit,
    onOpenBios: () -> Unit,
    onPickGameFolder: () -> Unit,
    onRefresh: () -> Unit,
    onBootBios: () -> Unit,
    onGameClick: (Game) -> Unit,
    onGameSettingsClick: (Game) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.library_title)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_title))
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.library_more))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.library_boot_bios)) },
                            onClick = {
                                menuOpen = false
                                onBootBios()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.bios_title)) },
                            onClick = {
                                menuOpen = false
                                onOpenBios()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.library_change_game_folder)) },
                            onClick = {
                                menuOpen = false
                                onPickGameFolder()
                            },
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        val games = state.games
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when {
                // Nothing to show yet; the refresh indicator covers the wait.
                games == null -> Unit
                !state.hasGameFolder -> EmptyState(
                    text = stringResource(R.string.library_no_folder),
                    actionText = stringResource(R.string.library_select_game_folder),
                    onAction = onPickGameFolder,
                )
                games.isEmpty() -> if (!state.refreshing) {
                    EmptyState(
                        text = stringResource(R.string.library_empty),
                        actionText = stringResource(R.string.library_change_game_folder),
                        onAction = onPickGameFolder,
                    )
                }
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (state.bios == null) {
                        item(key = "bios-missing") {
                            ListItem(
                                headlineContent = { Text(stringResource(R.string.library_bios_missing)) },
                                supportingContent = { Text(stringResource(R.string.library_bios_missing_summary)) },
                                trailingContent = {
                                    FilledTonalButton(onClick = onOpenBios) {
                                        Text(stringResource(R.string.library_bios_set_up))
                                    }
                                },
                            )
                        }
                    }
                    items(games, key = { it.uri.toString() }) { game ->
                        ListItem(
                            headlineContent = { Text(game.title, maxLines = 2) },
                            supportingContent = { Text(game.details(), maxLines = 1) },
                            trailingContent = {
                                IconButton(onClick = { onGameSettingsClick(game) }) {
                                    Icon(
                                        painterResource(R.drawable.ic_tune),
                                        contentDescription = stringResource(R.string.library_game_settings),
                                    )
                                }
                            },
                            modifier = Modifier.clickable { onGameClick(game) },
                        )
                    }
                }
            }
        }
    }
}

/** Serial, format and size, e.g. "SLUS-20312 · CHD · 2.1 GB". */
@Composable
private fun Game.details(): String {
    val size = Formatter.formatShortFileSize(LocalContext.current, sizeBytes)
    return listOfNotNull(serial, format.ifEmpty { null }, size.takeIf { sizeBytes > 0 }).joinToString(" · ")
}

@Composable
private fun EmptyState(text: String, actionText: String, onAction: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        FilledTonalButton(onClick = onAction) { Text(actionText) }
    }
}

@Preview
@Composable
private fun LibraryContentPreview() {
    ScphxTheme {
        LibraryContent(
            state = LibraryUiState(
                hasGameFolder = true,
                games = listOf(Game(Uri.EMPTY, "Example Game (USA).chd", "SLUS-20312", true, 2_100_000_000L)),
            ),
            snackbarHostState = SnackbarHostState(),
            onOpenSettings = {},
            onOpenBios = {},
            onPickGameFolder = {},
            onRefresh = {},
            onBootBios = {},
            onGameClick = {},
            onGameSettingsClick = {},
        )
    }
}
