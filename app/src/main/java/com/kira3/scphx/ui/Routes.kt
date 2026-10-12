package com.kira3.scphx.ui

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

// Destinations in MainActivity. Serializable so the back stack survives process death.

@Serializable
data object LibraryRoute : NavKey

@Serializable
data object SettingsRoute : NavKey
