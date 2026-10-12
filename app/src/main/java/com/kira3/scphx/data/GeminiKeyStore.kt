package com.kira3.scphx.data

import android.content.Context
import java.io.File

/**
 * The Gemini API key, in a file of its own under the app's private no-backup directory:
 * not in the settings files, which are exported and backed up. Both app processes read
 * the file on use, so a key set in the main process is seen by the emulation process.
 */
class GeminiKeyStore(context: Context) {
    private val file = File(context.applicationContext.noBackupFilesDir, "gemini_api_key")

    fun get(): String = runCatching { file.readText().trim() }.getOrDefault("")

    fun isSet(): Boolean = get().isNotEmpty()

    /** Stores [key], or removes the stored key if it is blank. */
    fun set(key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) file.delete() else file.writeText(trimmed)
    }
}
