package com.kira3.scphx.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.kira3.scphx.ui.library.LibraryScreen
import com.kira3.scphx.ui.settings.SettingsScreen

/** Root of MainActivity's UI. The back stack is a plain list the app owns (Navigation 3). */
@Composable
fun ScphxApp() {
    val backStack = rememberNavBackStack(LibraryRoute)

    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            // Scopes each destination's ViewModels to its back stack entry.
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = entryProvider {
            entry<LibraryRoute> {
                LibraryScreen(onOpenSettings = { backStack.add(SettingsRoute) })
            }
            entry<SettingsRoute> {
                SettingsScreen(onBack = { backStack.removeLastOrNull() })
            }
        },
    )
}
