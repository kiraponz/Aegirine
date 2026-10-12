package com.kira3.scphx.ui.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kira3.scphx.R
import com.kira3.scphx.data.GameFile
import com.kira3.scphx.emulation.EmulationActivity
import com.kira3.scphx.ui.theme.ScphxTheme

@Composable
fun LibraryScreen(
    onOpenSettings: () -> Unit,
    onOpenGameSettings: (serial: String, title: String) -> Unit,
    viewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val pickBios = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::onBiosPicked)
    }
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
        onPickBios = { pickBios.launch(arrayOf("*/*")) },
        onPickGameFolder = { pickGameFolder.launch(null) },
        onGameClick = viewModel::onGameClicked,
        onGameSettingsClick = viewModel::onGameSettingsClicked,
    )
}

private val LibraryMessage.textRes: Int
    get() = when (this) {
        LibraryMessage.InvalidBios -> R.string.library_message_invalid_bios
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
    onPickBios: () -> Unit,
    onPickGameFolder: () -> Unit,
    onGameClick: (GameFile) -> Unit,
    onGameSettingsClick: (GameFile) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.library_title)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_title))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // Provisional setup rows; they move to the BIOS and library settings later.
            item {
                val bios = state.bios
                ListItem(
                    headlineContent = { Text(stringResource(R.string.library_bios)) },
                    supportingContent = {
                        Text(
                            if (bios == null) {
                                stringResource(R.string.library_not_selected)
                            } else {
                                "${bios.metadata.description} (${bios.metadata.versionString})"
                            },
                        )
                    },
                    trailingContent = {
                        TextButton(onClick = onPickBios) { Text(stringResource(R.string.library_select)) }
                    },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.library_game_folder)) },
                    supportingContent = {
                        Text(
                            stringResource(
                                if (state.hasGameFolder) R.string.library_selected else R.string.library_not_selected,
                            ),
                        )
                    },
                    trailingContent = {
                        TextButton(onClick = onPickGameFolder) { Text(stringResource(R.string.library_select)) }
                    },
                )
                HorizontalDivider()
            }

            val games = state.games
            when {
                games == null -> item {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(16.dp))
                }
                games.isEmpty() -> item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.library_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> items(games, key = { it.uri.toString() }) { game ->
                    ListItem(
                        headlineContent = { Text(game.name) },
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

@Preview
@Composable
private fun LibraryContentPreview() {
    ScphxTheme {
        LibraryContent(
            state = LibraryUiState(
                hasGameFolder = true,
                games = listOf(GameFile("Example Game (USA).chd", Uri.EMPTY)),
            ),
            snackbarHostState = SnackbarHostState(),
            onOpenSettings = {},
            onPickBios = {},
            onPickGameFolder = {},
            onGameClick = {},
            onGameSettingsClick = {},
        )
    }
}
