package com.kira3.scphx

import android.content.Context
import com.kira3.scphx.data.AppPreferences
import com.kira3.scphx.data.BiosRepository
import com.kira3.scphx.data.CheatRepository
import com.kira3.scphx.data.MacroRepository
import com.kira3.scphx.data.PadLayoutRepository
import com.kira3.scphx.data.DriverRepository
import com.kira3.scphx.data.GameRecordRepository
import com.kira3.scphx.data.GeminiKeyStore
import com.kira3.scphx.data.LibraryRepository
import com.kira3.scphx.data.MemoRepository
import com.kira3.scphx.data.MemoryCardRepository
import com.kira3.scphx.data.SettingsRepository
import com.kira3.scphx.data.db.LibraryDatabase
import com.kira3.scphx.emucore.EmulatorCore

/**
 * Manual DI: creates the app's long-lived objects once per process. ViewModels get what
 * they need through their factories, never by reaching into this directly.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val emulatorCore: EmulatorCore by lazy { EmulatorCore(appContext) }
    val appPreferences: AppPreferences by lazy { AppPreferences(appContext) }
    val biosRepository: BiosRepository by lazy { BiosRepository(appContext, emulatorCore, appPreferences) }
    private val libraryDatabase: LibraryDatabase by lazy { LibraryDatabase.create(appContext) }
    val libraryRepository: LibraryRepository by lazy {
        LibraryRepository(appContext, emulatorCore, appPreferences, libraryDatabase.gameDao(), gameRecordRepository)
    }
    val gameRecordRepository: GameRecordRepository by lazy { GameRecordRepository(appContext) }
    val memoryCardRepository: MemoryCardRepository by lazy { MemoryCardRepository(appContext, emulatorCore) }
    val cheatRepository: CheatRepository by lazy { CheatRepository(appContext) }
    val macroRepository: MacroRepository by lazy { MacroRepository(appContext) }
    val padLayoutRepository: PadLayoutRepository by lazy { PadLayoutRepository(appContext) }
    val memoRepository: MemoRepository by lazy { MemoRepository(appContext) }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(appContext) }
    val geminiKeyStore: GeminiKeyStore by lazy { GeminiKeyStore(appContext) }
    val driverRepository: DriverRepository by lazy { DriverRepository(appContext) }
}
