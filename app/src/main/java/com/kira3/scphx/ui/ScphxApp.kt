package com.kira3.scphx.ui

import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.kira3.scphx.ui.bios.BiosScreen
import com.kira3.scphx.ui.library.LibraryScreen
import com.kira3.scphx.ui.settings.GpuDriverScreen
import com.kira3.scphx.ui.settings.SettingsCategoryScreen
import com.kira3.scphx.ui.settings.SettingsDetailPlaceholder
import com.kira3.scphx.ui.settings.SettingsHomeScreen
import com.kira3.scphx.ui.settings.SettingsSearchScreen

/**
 * Root of MainActivity's UI. The back stack is a plain list the app owns (Navigation 3).
 * Settings show as list and detail side by side when the window is wide enough.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun ScphxApp() {
    val backStack = rememberNavBackStack(LibraryRoute)
    val listDetail = rememberListDetailSceneStrategy<NavKey>()

    // Beside the settings list, opening something replaces what is open in the detail pane.
    fun openDetail(route: NavKey) {
        val last = backStack.lastOrNull()
        if (last is SettingsCategoryRoute || last is SettingsSearchRoute || last is GpuDriverRoute || last is BiosRoute) {
            while (backStack.lastOrNull() !is SettingsRoute) backStack.removeLastOrNull()
        }
        backStack.add(route)
    }

    NavDisplay(
        backStack = backStack,
        // Shows between the list and detail panes.
        modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer),
        onBack = { backStack.removeLastOrNull() },
        sceneStrategies = listOf(listDetail),
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            // Scopes each destination's ViewModels to its back stack entry.
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = entryProvider {
            entry<LibraryRoute> {
                LibraryScreen(
                    onOpenSettings = { backStack.add(SettingsRoute()) },
                    // The BIOS files are managed from the settings.
                    onOpenBios = {
                        backStack.add(SettingsRoute())
                        backStack.add(BiosRoute)
                    },
                    onOpenGameSettings = { serial, title -> backStack.add(SettingsRoute(serial, title)) },
                )
            }
            entry<BiosRoute>(
                metadata = { ListDetailSceneStrategy.detailPane(sceneKey = SettingsRoute()) },
            ) {
                BiosScreen(onBack = { backStack.removeLastOrNull() })
            }
            entry<SettingsRoute>(
                metadata = { route ->
                    ListDetailSceneStrategy.listPane(sceneKey = route) { SettingsDetailPlaceholder() }
                },
            ) { route ->
                SettingsHomeScreen(
                    serial = route.serial,
                    gameTitle = route.gameTitle,
                    selected = backStack.filterIsInstance<SettingsCategoryRoute>().lastOrNull()?.category,
                    onOpenCategory = { category ->
                        openDetail(SettingsCategoryRoute(category, route.serial, route.gameTitle))
                    },
                    onOpenSearch = { openDetail(SettingsSearchRoute(route.serial, route.gameTitle)) },
                    onBack = { backStack.removeLastOrNull() },
                )
            }
            entry<SettingsCategoryRoute>(
                metadata = { route ->
                    ListDetailSceneStrategy.detailPane(sceneKey = SettingsRoute(route.serial, route.gameTitle))
                },
            ) { route ->
                SettingsCategoryScreen(
                    category = route.category,
                    serial = route.serial,
                    gameTitle = route.gameTitle,
                    onOpenGpuDriver = { backStack.add(GpuDriverRoute(route.serial, route.gameTitle)) },
                    onOpenBios = { openDetail(BiosRoute) },
                    onBack = { backStack.removeLastOrNull() },
                )
            }
            entry<SettingsSearchRoute>(
                metadata = { route ->
                    ListDetailSceneStrategy.detailPane(sceneKey = SettingsRoute(route.serial, route.gameTitle))
                },
            ) { route ->
                SettingsSearchScreen(
                    serial = route.serial,
                    gameTitle = route.gameTitle,
                    onOpenGpuDriver = { backStack.add(GpuDriverRoute(route.serial, route.gameTitle)) },
                    onOpenBios = { openDetail(BiosRoute) },
                    onBack = { backStack.removeLastOrNull() },
                )
            }
            entry<GpuDriverRoute>(
                metadata = { route ->
                    ListDetailSceneStrategy.detailPane(sceneKey = SettingsRoute(route.serial, route.gameTitle))
                },
            ) { route ->
                GpuDriverScreen(
                    serial = route.serial,
                    gameTitle = route.gameTitle,
                    onBack = { backStack.removeLastOrNull() },
                )
            }
        },
    )
}
