package com.kira3.scphx.ui.backup

import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kira3.scphx.R
import com.kira3.scphx.data.BackupPartInfo
import com.kira3.scphx.ui.settings.BackButton
import com.kira3.scphx.ui.settings.listPadding
import com.kira3.scphx.ui.settings.segmentedShapes
import com.kira3.scphx.ui.settings.settingsListColors
import com.kira3.scphx.ui.settings.settingsTopBarColors
import java.util.Date

/** Backing the app's data up into a zip and restoring it, a page of the global settings. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun BackupScreen(
    onBack: () -> Unit,
    viewModel: BackupViewModel = viewModel(factory = BackupViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    // Beside the settings list there is nothing to go back to.
    val twoPane = LocalListDetailSceneScope.current != null
    val snackbarHostState = remember { SnackbarHostState() }

    val saveBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let(viewModel::backUp)
    }
    val pickBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::open)
    }

    val messages = mapOf(
        BackupMessage.BackedUp to stringResource(R.string.backup_done),
        BackupMessage.Restored to stringResource(R.string.backup_restored),
        BackupMessage.NotABackup to stringResource(R.string.backup_invalid),
        BackupMessage.Failed to stringResource(R.string.backup_failed),
        BackupMessage.Busy to stringResource(R.string.backup_busy),
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
                title = { Text(stringResource(R.string.backup_title)) },
                navigationIcon = { if (!twoPane) BackButton(onBack) },
                colors = settingsTopBarColors(),
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        val available = state.available
        if (available == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(innerPadding).padding(16.dp))
            return@Scaffold
        }
        LazyColumn(
            contentPadding = listPadding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (state.busy) {
                item(key = "busy") { LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 8.dp)) }
            }
            item(key = "back_up_title") { SectionTitle(stringResource(R.string.backup_section_back_up)) }
            if (available.isEmpty()) {
                item(key = "nothing") { Note(stringResource(R.string.backup_nothing)) }
            }
            itemsIndexed(available, key = { _, info -> info.part.name }) { index, info ->
                PartRow(
                    info = info,
                    checked = info.part in state.selected,
                    enabled = !state.busy,
                    index = index,
                    count = available.size,
                    onToggle = { viewModel.toggle(info.part) },
                )
            }
            item(key = "back_up") {
                Button(
                    onClick = { saveBackup.launch(viewModel.fileName()) },
                    enabled = !state.busy && state.selected.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) { Text(stringResource(R.string.backup_action)) }
            }
            item(key = "excluded") { Note(stringResource(R.string.backup_excluded)) }
            item(key = "restore_title") { SectionTitle(stringResource(R.string.backup_section_restore)) }
            item(key = "restore") {
                OutlinedButton(
                    onClick = { pickBackup.launch(arrayOf("application/zip", "application/octet-stream")) },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.backup_restore_action)) }
            }
            item(key = "restore_note") { Note(stringResource(R.string.backup_restore_note)) }
        }
    }

    state.restore?.let { plan ->
        val context = LocalContext.current
        AlertDialog(
            onDismissRequest = viewModel::cancelRestore,
            title = { Text(stringResource(R.string.backup_restore_title)) },
            text = {
                androidx.compose.foundation.layout.Column {
                    if (plan.contents.createdAt > 0) {
                        val date = DateFormat.getMediumDateFormat(context).format(Date(plan.contents.createdAt)) + " " +
                            DateFormat.getTimeFormat(context).format(Date(plan.contents.createdAt))
                        Text(stringResource(R.string.backup_made_at, date), style = MaterialTheme.typography.bodyMedium)
                    }
                    for (info in plan.contents.parts) {
                        Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Checkbox(checked = info.part in plan.selected, onCheckedChange = { viewModel.toggleRestore(info.part) })
                            Text(stringResource(info.part.title) + " · " + details(info), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Text(
                        stringResource(R.string.backup_restore_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::restore, enabled = plan.selected.isNotEmpty()) {
                    Text(stringResource(R.string.backup_restore))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelRestore) { Text(stringResource(R.string.settings_cancel)) }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PartRow(info: BackupPartInfo, checked: Boolean, enabled: Boolean, index: Int, count: Int, onToggle: () -> Unit) {
    SegmentedListItem(
        onClick = onToggle,
        enabled = enabled,
        shapes = segmentedShapes(index, count),
        colors = settingsListColors(),
        trailingContent = { Checkbox(checked = checked, onCheckedChange = null, enabled = enabled) },
        supportingContent = { Text(stringResource(info.part.summary) + "\n" + details(info)) },
    ) { Text(stringResource(info.part.title)) }
}

@Composable
private fun details(info: BackupPartInfo): String =
    stringResource(R.string.backup_part_details, info.files, Formatter.formatShortFileSize(LocalContext.current, info.bytes))

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
