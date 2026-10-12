package com.kira3.scphx.ui

import androidx.navigation3.runtime.NavKey
import com.kira3.scphx.ui.settings.SettingsCategory
import kotlinx.serialization.Serializable

// Destinations in MainActivity. Serializable so the back stack survives process death.

@Serializable
data object LibraryRoute : NavKey

/** The settings categories: global when [serial] is null, else that game's. */
@Serializable
data class SettingsRoute(val serial: String? = null, val gameTitle: String? = null) : NavKey

/** One category of the settings [SettingsRoute] lists, shown beside it on a large screen. */
@Serializable
data class SettingsCategoryRoute(
    val category: SettingsCategory,
    val serial: String? = null,
    val gameTitle: String? = null,
) : NavKey

/** The custom Vulkan driver of the settings [SettingsRoute] lists, global or for a game. */
@Serializable
data class GpuDriverRoute(val serial: String? = null, val gameTitle: String? = null) : NavKey
