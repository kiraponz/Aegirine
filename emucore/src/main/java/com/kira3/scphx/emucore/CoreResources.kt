package com.kira3.scphx.emucore

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * Puts the APK's `assets/resources` (GameDB, shaders, fonts) into `<dataDir>/resources`,
 * where the core reads them as plain files.
 *
 * The copy runs only when this install has not written them yet: a marker file records the
 * install that did, and is written after a clean pass, so a failed copy is retried at the
 * next launch.
 */
internal object CoreResources {
    private const val TAG = "CoreResources"
    private const val ASSET_ROOT = "resources"
    private const val MARKER = ".install"

    fun install(context: Context, dataDir: File) {
        val target = File(dataDir, ASSET_ROOT)
        val marker = File(target, MARKER)
        val install = installId(context)
        if (runCatching { marker.readText() }.getOrNull() == install) return

        // Start clean so files dropped from a newer build don't linger.
        target.deleteRecursively()
        val ok = copyTree(context.assets, ASSET_ROOT, target)
        if (ok) {
            marker.writeText(install)
        } else {
            Log.e(TAG, "Copying core resources failed; will retry next launch")
        }
    }

    /** Changes on every install, including a rebuilt APK with the same versionCode. */
    private fun installId(context: Context): String {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return "${context.packageName} ${info.longVersionCode} ${info.lastUpdateTime}"
    }

    private fun copyTree(assets: AssetManager, path: String, dest: File): Boolean {
        return try {
            val children = assets.list(path).orEmpty()
            if (children.isEmpty()) {
                // A file (asset folders are never empty).
                dest.parentFile?.mkdirs()
                assets.open(path).use { input -> dest.outputStream().use { input.copyTo(it) } }
                true
            } else {
                dest.mkdirs()
                children.fold(true) { ok, child ->
                    copyTree(assets, "$path/$child", File(dest, child)) && ok
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to copy asset $path", e)
            false
        }
    }
}
