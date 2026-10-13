package com.kira3.scphx.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kira3.scphx.R
import com.kira3.scphx.emulation.MacroDialogs
import com.kira3.scphx.emulation.MacroEditorScreen
import com.kira3.scphx.emulation.MacroMenu
import com.kira3.scphx.emulation.details
import com.kira3.scphx.emulation.rememberMacroDialogs

/**
 * A game's macros, to rename, edit and delete without starting the game. Recording and playing
 * need the game. The memory addresses cannot be picked from a search here, so they are typed.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MacrosScreen(
    serial: String,
    gameTitle: String?,
    onBack: () -> Unit,
    viewModel: MacrosViewModel = viewModel(key = "macros:$serial", factory = MacrosViewModel.factory(serial)),
) {
    val macros by viewModel.macros.collectAsStateWithLifecycle()
    val dialogs = rememberMacroDialogs()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val editing = dialogs.editor
    if (editing != null) {
        BackHandler { dialogs.editor = null }
        MacroEditorScreen(
            macro = editing,
            suggestions = emptyList(),
            onSave = { viewModel.replace(it); dialogs.editor = null },
            onCancel = { dialogs.editor = null },
        )
        return
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.settings_macros)) },
                subtitle = gameTitle?.let { { Text(it, maxLines = 1) } },
                navigationIcon = { BackButton(onBack) },
                colors = settingsTopBarColors(),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        val list = macros ?: return@Scaffold
        LazyColumn(
            contentPadding = listPadding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            itemsIndexed(list, key = { _, macro -> macro.id }) { index, macro ->
                SegmentedListItem(
                    onClick = { dialogs.editor = macro },
                    shapes = segmentedShapes(index, list.size),
                    colors = settingsListColors(),
                    modifier = Modifier.animateItem(),
                    trailingContent = { MacroMenu(macro, viewModel, dialogs) },
                    supportingContent = { Text(macro.details()) },
                ) { Text(macro.name, maxLines = 1) }
            }
            if (list.isEmpty()) {
                item(key = "empty") {
                    Text(
                        stringResource(R.string.macros_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
    MacroDialogs(dialogs, viewModel, suggestions = emptyList())
}
