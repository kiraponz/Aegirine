package com.kira3.ametrine.data

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app")

/** App-level choices that are not emulator settings (picked folders, chosen BIOS). */
class AppPreferences(context: Context) {
    private val dataStore = context.applicationContext.dataStore

    /** SAF tree URI of the folder the library scans. */
    val gameFolder: Flow<Uri?> = dataStore.data.map { prefs -> prefs[GAME_FOLDER]?.let(Uri::parse) }

    /** File name of the selected BIOS inside EmulatorCore.biosDir. */
    val biosFileName: Flow<String?> = dataStore.data.map { prefs -> prefs[BIOS_FILE_NAME] }

    /** [com.kira3.ametrine.data.LibrarySort] name; null until the user picks one. */
    val librarySort: Flow<String?> = dataStore.data.map { prefs -> prefs[LIBRARY_SORT] }

    suspend fun setLibrarySort(name: String) {
        dataStore.edit { it[LIBRARY_SORT] = name }
    }

    suspend fun setGameFolder(uri: Uri) {
        dataStore.edit { it[GAME_FOLDER] = uri.toString() }
    }

    suspend fun setBiosFileName(name: String) {
        dataStore.edit { it[BIOS_FILE_NAME] = name }
    }

    suspend fun clearBiosFileName() {
        dataStore.edit { it.remove(BIOS_FILE_NAME) }
    }

    private companion object {
        val GAME_FOLDER = stringPreferencesKey("game_folder")
        val LIBRARY_SORT = stringPreferencesKey("library_sort")
        val BIOS_FILE_NAME = stringPreferencesKey("bios_file_name")
    }
}
