package com.kira3.scphx

import android.content.Context
import com.kira3.scphx.emucore.EmulatorCore

/**
 * Manual DI: creates the app's long-lived objects once per process. ViewModels get what
 * they need through their factories, never by reaching into this directly.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val emulatorCore: EmulatorCore by lazy { EmulatorCore(appContext) }
}
