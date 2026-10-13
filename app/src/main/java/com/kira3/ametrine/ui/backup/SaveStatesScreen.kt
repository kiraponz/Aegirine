package com.kira3.ametrine.ui.backup

import android.graphics.BitmapFactory
import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.LocalListDetailSceneScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kira3.ametrine.R
import com.kira3.ametrine.data.SaveStateFile
import com.kira3.ametrine.emucore.EmulatorCore
import com.kira3.ametrine.ui.settings.BackButton
import com.kira3.ametrine.ui.settings.listPadding
import com.kira3.ametrine.ui.settings.segmentedShapes
import com.kira3.ametrine.ui.settings.settingsListColors
import com.kira3.ametrine.ui.settings.settingsTopBarColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date

/** The save states games have left, with export to a file and import from one, a page of the global settings. */
@OptIn(
    ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3AdaptiveApi::class,
    ExperimentalLayoutApi::class,
)
@Composable
fun SaveStatesScreen(
    onBack: () -> Unit,
    viewModel: SaveStatesViewModel = viewModel(factory = SaveStatesViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    // Beside the settings list there is nothing to go back to.
    val twoPane = LocalListDetailSceneScope.current != null
    val snackbarHostState = remember { SnackbarHostState() }
    var exporting by remember { mutableStateOf<SaveStateFile?>(null) }
    var deleting by remember { mutableStateOf<SaveStateFile?>(null) }

    val pickState = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::open)
    }
    val saveState = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val file = exporting
        exporting = null
        if (uri != null && file != null) viewModel.export(file, uri)
    }

    val messages = mapOf(
        SaveStateMessage.Exported to stringResource(R.string.states_exported),
        SaveStateMessage.Imported to stringResource(R.string.states_imported),
        SaveStateMessage.Deleted to stringResource(R.string.states_deleted),
        SaveStateMessage.NotAState to stringResource(R.string.states_invalid),
        SaveStateMessage.Failed to stringResource(R.string.states_failed),
        SaveStateMessage.Busy to stringResource(R.string.states_busy),
    )
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        messages[message]?.let { snackbarHostState.showSnackbar(it) }
        viewModel.onMessageShown()
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.states_title)) },
                navigationIcon = { if (!twoPane) BackButton(onBack) },
                actions = {
                    TextButton(onClick = { pickState.launch(arrayOf("*/*")) }, enabled = !state.busy) {
                        Text(stringResource(R.string.states_import))
                    }
                },
                colors = settingsTopBarColors(),
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        val games = state.games
        if (games == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(innerPadding).padding(16.dp))
            return@Scaffold
        }
        LazyColumn(
            contentPadding = listPadding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (games.isEmpty()) {
                item(key = "empty") {
                    Text(
                        stringResource(R.string.states_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(32.dp),
                    )
                }
            }
            for (game in games) {
                item(key = "game_${game.serial}") {
                    Text(
                        game.title ?: game.serial,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
                    )
                }
                val count = game.states.size
                for ((index, file) in game.states.withIndex()) {
                    item(key = file.name) {
                        StateRow(
                            file = file,
                            index = index,
                            count = count,
                            enabled = !state.busy,
                            thumbnail = { viewModel.thumbnail(file) },
                            onExport = {
                                exporting = file
                                saveState.launch(file.name)
                            },
                            onDelete = { deleting = file },
                        )
                    }
                }
            }
            item(key = "about") {
                Text(
                    stringResource(R.string.states_about),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }

    deleting?.let { file ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.states_delete_title, file.slot)) },
            text = { Text(stringResource(R.string.states_delete_text)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    viewModel.delete(file)
                }) { Text(stringResource(R.string.states_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.settings_cancel)) } },
        )
    }

    state.pending?.let { pending ->
        var serial by remember(pending) { mutableStateOf(pending.serial) }
        var slot by remember(pending) { mutableIntStateOf(pending.slot ?: EmulatorCore.SAVE_STATE_SLOTS.first) }
        val game = pending.games.firstOrNull { it.serial == serial }
        val exact = pending.file.serial != null && pending.file.crc != null && pending.file.slot != null
        AlertDialog(
            onDismissRequest = viewModel::cancelImport,
            title = { Text(stringResource(R.string.states_import_title)) },
            text = {
                androidx.compose.foundation.layout.Column {
                    if (!exact) {
                        Text(
                            stringResource(R.string.states_import_renamed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    Text(stringResource(R.string.states_import_game), style = MaterialTheme.typography.labelLarge)
                    LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                        items(pending.games, key = { it.serial }) { item ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable { serial = item.serial },
                            ) {
                                RadioButton(selected = item.serial == serial, onClick = { serial = item.serial })
                                Text(item.title ?: item.serial, maxLines = 2, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    Text(stringResource(R.string.states_import_slot), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (n in EmulatorCore.SAVE_STATE_SLOTS) {
                            FilterChip(selected = n == slot, onClick = { slot = n }, label = { Text(n.toString()) })
                        }
                    }
                    if (game != null && game.crc == null) {
                        Text(
                            stringResource(R.string.states_import_unknown_crc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    } else if (game != null && slot in game.takenSlots) {
                        Text(
                            stringResource(R.string.states_import_replaces, slot),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { game?.let { viewModel.import(it, slot) } }, enabled = game?.crc != null) {
                    Text(stringResource(R.string.states_import))
                }
            },
            dismissButton = { TextButton(onClick = viewModel::cancelImport) { Text(stringResource(R.string.settings_cancel)) } },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StateRow(
    file: SaveStateFile,
    index: Int,
    count: Int,
    enabled: Boolean,
    thumbnail: () -> ByteArray?,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val image by produceState<ImageBitmap?>(null, file.name, file.modified) {
        value = withContext(Dispatchers.IO) {
            thumbnail()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }?.asImageBitmap()
        }
    }
    val date = DateFormat.getMediumDateFormat(context).format(Date(file.modified)) + " " +
        DateFormat.getTimeFormat(context).format(Date(file.modified))
    SegmentedListItem(
        onClick = onExport,
        enabled = enabled,
        shapes = segmentedShapes(index, count),
        colors = settingsListColors(),
        leadingContent = {
            Box(
                Modifier.width(72.dp).aspectRatio(4f / 3f).clip(RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                image?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            }
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }, enabled = enabled) {
                    Icon(Icons.Filled.MoreVert, contentDescription = null)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.states_export)) },
                        onClick = {
                            menu = false
                            onExport()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.states_delete)) },
                        onClick = {
                            menu = false
                            onDelete()
                        },
                    )
                }
            }
        },
        supportingContent = { Text(date + " · " + Formatter.formatShortFileSize(context, file.bytes)) },
    ) { Text(stringResource(R.string.menu_slot, file.slot)) }
}
