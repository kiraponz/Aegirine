package com.kira3.scphx.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kira3.scphx.R
import com.kira3.scphx.data.GpuDriver

/**
 * The custom Vulkan driver: pick one of the installed packs or the system driver, install
 * packs from zip files, delete them. For a game, "same as global" follows the global choice.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GpuDriverScreen(
    serial: String?,
    gameTitle: String?,
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(serial)),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val message by viewModel.driverMessage.collectAsStateWithLifecycle()
    val isGame = serial != null
    val path = SettingsViewModel.DRIVER_PATH
    var deleting by remember { mutableStateOf<GpuDriver?>(null) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val snackbarHostState = remember { SnackbarHostState() }

    val pickZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::installDriver)
    }

    val messageText = message?.let {
        stringResource(if (it == DriverMessage.Installed) R.string.driver_installed else R.string.driver_not_a_driver)
    }
    LaunchedEffect(message) {
        if (messageText != null) {
            snackbarHostState.showSnackbar(messageText)
            viewModel.onDriverMessageShown()
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.settings_gpu_driver)) },
                subtitle = gameTitle?.let { { Text(it, maxLines = 1) } },
                navigationIcon = { BackButton(onBack) },
                colors = settingsTopBarColors(),
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            val installing = state?.installing == true
            ExtendedFloatingActionButton(
                onClick = { if (!installing) pickZip.launch(ZIP_TYPES) },
                icon = {
                    if (installing) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                    else Icon(Icons.Filled.Add, contentDescription = null)
                },
                text = { Text(stringResource(if (installing) R.string.driver_installing else R.string.driver_install)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        val current = state ?: return@Scaffold
        val selectedId = current.values.string(path)
        val overridden = isGame && path in current.overrides
        // Rows: (for a game) same as global, the system driver, then each installed pack.
        val choices: List<DriverChoice> = buildList {
            if (isGame) add(DriverChoice.Global)
            add(DriverChoice.System)
            current.drivers.forEach { add(DriverChoice.Installed(it)) }
        }
        LazyColumn(
            contentPadding = listPadding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            itemsIndexed(choices, key = { _, choice -> choice.key }) { index, choice ->
                val selected = when (choice) {
                    DriverChoice.Global -> !overridden
                    DriverChoice.System -> (!isGame || overridden) && selectedId.isEmpty()
                    is DriverChoice.Installed -> (!isGame || overridden) && selectedId == choice.driver.id
                }
                SegmentedListItem(
                    selected = selected,
                    onClick = {
                        when (choice) {
                            DriverChoice.Global -> viewModel.reset(listOf(path))
                            DriverChoice.System -> viewModel.set(path, "")
                            is DriverChoice.Installed -> viewModel.set(path, choice.driver.id)
                        }
                    },
                    shapes = segmentedShapes(index, choices.size),
                    colors = settingsListColors(),
                    modifier = Modifier.animateItem(),
                    leadingContent = { RadioButton(selected = selected, onClick = null) },
                    trailingContent = (choice as? DriverChoice.Installed)?.let {
                        {
                            IconButton(onClick = { deleting = it.driver }) {
                                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.driver_delete))
                            }
                        }
                    },
                    supportingContent = {
                        val text = when (choice) {
                            DriverChoice.Global -> stringResource(R.string.driver_global_summary)
                            DriverChoice.System -> stringResource(R.string.driver_system_summary)
                            is DriverChoice.Installed -> choice.driver.details()
                        }
                        if (text.isNotEmpty()) Text(text)
                    },
                ) {
                    Text(
                        when (choice) {
                            DriverChoice.Global -> stringResource(R.string.driver_same_as_global)
                            DriverChoice.System -> stringResource(R.string.driver_system)
                            is DriverChoice.Installed -> choice.driver.name
                        },
                    )
                }
            }
            item(key = "about") {
                Text(
                    stringResource(R.string.driver_about),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // Room for the floating button over the last line.
                    modifier = Modifier.animateItem().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 72.dp),
                )
            }
        }
    }

    deleting?.let { driver ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.driver_delete_title, driver.name)) },
            text = { Text(stringResource(R.string.driver_delete_text)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    viewModel.deleteDriver(driver)
                }) { Text(stringResource(R.string.driver_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.settings_cancel)) }
            },
        )
    }
}

private sealed interface DriverChoice {
    val key: String

    data object Global : DriverChoice {
        override val key = "global"
    }

    data object System : DriverChoice {
        override val key = "system"
    }

    data class Installed(val driver: GpuDriver) : DriverChoice {
        override val key = "driver:${driver.id}"
    }
}

/** Version and maker, as far as the pack's meta.json says. */
private fun GpuDriver.details(): String =
    listOf(version, vendor.ifEmpty { author }).filter { it.isNotBlank() }.joinToString(" · ")

/** Driver packs are zips; some file pickers label them as plain binary data. */
private val ZIP_TYPES = arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")
