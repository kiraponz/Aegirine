package kr.co.iefriends.pcsx2

import android.content.Context
import android.net.Uri
import android.system.Os
import android.system.OsConstants
import android.util.Log
import android.view.Surface
import com.armsx2.BiosInfo
import java.io.File

/**
 * JNI boundary to the ARMSX2 core (native-lib.cpp).
 *
 * The package and class name are fixed: the native symbols are named
 * `Java_kr_co_iefriends_pcsx2_NativeApp_*`, and native code looks this class up with
 * FindClass to call the callbacks at the bottom. Only the natives v1 uses are declared;
 * the rest of the core's ~175 entry points can be added here as they are needed.
 *
 * Use [com.kira3.scphx.emucore.EmulatorCore] instead of calling this directly.
 */
internal object NativeApp {
    private const val TAG = "NativeApp"

    /** Null when the core library loaded; otherwise why it did not. */
    @JvmStatic
    val loadError: String?

    init {
        val pageSize = runCatching { Os.sysconf(OsConstants._SC_PAGESIZE) }.getOrDefault(4096L)
        // :emucore only builds the 4 KiB-page core (ARMSX2_ANDROID_HOST_PAGE_SIZE=0x1000).
        loadError = if (pageSize != 4096L) {
            "Devices with a $pageSize-byte memory page size are not supported"
        } else {
            try {
                System.loadLibrary("emucore_4k")
                null
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load emucore_4k", e)
                e.message ?: "Failed to load the emulator core"
            }
        }
    }

    /** Set by EmulatorCore before [initialize]; used by the content:// callbacks. */
    @Volatile
    @JvmStatic
    var context: Context? = null

    /** Receives the core's callbacks. Set by EmulatorCore. */
    @Volatile
    @JvmStatic
    var listener: Listener? = null

    interface Listener {
        fun onVmPausedChanged(paused: Boolean)
        fun onPadRumble(pad: Int, largeMotor: Int, smallMotor: Int)
    }

    // ---- Setup ----

    @JvmStatic external fun initialize(path: String, biosFolder: String, apiVer: Int)
    @JvmStatic external fun getBuildVersion(): String

    // ---- Settings ----

    /** Writes one value to the base settings layer. Not applied until [commitSettings]. */
    @JvmStatic external fun setSetting(section: String, key: String, type: String, value: String)
    @JvmStatic external fun commitSettings()

    // ---- Surface ----

    @JvmStatic external fun onNativeSurfaceCreated()
    @JvmStatic external fun onNativeSurfaceChanged(surface: Surface, w: Int, h: Int)
    @JvmStatic external fun onNativeSurfaceDestroyed()
    @JvmStatic external fun setDisplayRefreshRate(hz: Float)

    // ---- VM lifecycle ----

    /** Boots [path] and runs until the VM shuts down. Blocks the calling thread. */
    @JvmStatic external fun runVMThread(path: String): Boolean
    @JvmStatic external fun getLastBootError(): String
    @JvmStatic external fun pause()
    @JvmStatic external fun resume()
    @JvmStatic external fun shutdown()
    @JvmStatic external fun hasActiveVM(): Boolean
    @JvmStatic external fun setAppBackgrounded(backgrounded: Boolean)
    @JvmStatic external fun flushShaderCache()

    // ---- Input ----

    @JvmStatic external fun setPadButton(index: Int, range: Int, isKeyPressed: Boolean)
    @JvmStatic external fun setPadButtonForPort(port: Int, index: Int, range: Int, isKeyPressed: Boolean)
    @JvmStatic external fun setPadVibration(enabled: Boolean)

    // ---- Game / BIOS info ----

    /** Takes ownership of [fd] and closes it. Null if the file is not a PS2 BIOS. */
    @JvmStatic external fun getBiosInfoFromFd(fd: Int): BiosInfo?

    /** Takes ownership of [fd] and closes it. Null for formats it cannot read (CSO/ZSO/GZ). */
    @JvmStatic external fun getGameSerialFromFd(fd: Int): String?
    @JvmStatic external fun getGameTitle(path: String): String
    @JvmStatic external fun getGameSerial(): String
    @JvmStatic external fun getGameCRC(): String
    @JvmStatic external fun getTitlesForSerial(serial: String): String
    @JvmStatic external fun getRegionForSerial(serial: String): String
    @JvmStatic external fun getCompatibilityForSerial(serial: String): Int
    @JvmStatic external fun getFPS(): Float

    // ---- Save states ----

    @JvmStatic external fun saveStateToSlot(slot: Int): Boolean
    @JvmStatic external fun loadStateFromSlot(slot: Int): Boolean
    @JvmStatic external fun isMemcardBusy(): Boolean

    // ---- Called from native ----
    // These run on core threads with no exception check on the native side, so nothing may
    // throw out of them: a pending exception aborts the app at the thread's next JNI call.

    @JvmStatic
    fun vmSetPaused(paused: Boolean) {
        runCatching { listener?.onVmPausedChanged(paused) }
            .onFailure { Log.e(TAG, "vmSetPaused", it) }
    }

    @JvmStatic
    fun onPadRumble(pad: Int, largeMotor: Int, smallMotor: Int) {
        runCatching { listener?.onPadRumble(pad, largeMotor, smallMotor) }
            .onFailure { Log.e(TAG, "onPadRumble", it) }
    }

    /** RetroAchievements sounds. Achievements are out of scope, so this does nothing. */
    @JvmStatic
    fun playSound(path: String?) = Unit

    /** Opens a content:// URI for reading and hands the fd to native, or returns -1. */
    @JvmStatic
    fun openContentUri(uriString: String): Int {
        val ctx = context ?: return -1
        return try {
            ctx.contentResolver.openFileDescriptor(Uri.parse(uriString), "r")?.detachFd() ?: -1
        } catch (e: Exception) {
            Log.w(TAG, "openContentUri failed: $uriString", e)
            -1
        }
    }

    /** The other CHDs in the same SAF folder as [uriString], for multi-file CHD sets. */
    @JvmStatic
    fun findSiblingChds(uriString: String): Array<String> {
        val ctx = context ?: return emptyArray()
        return runCatching { ContentChdFiles.findSiblings(ctx.contentResolver, Uri.parse(uriString)) }
            .getOrDefault(emptyArray())
    }

    /** Fallback for native mkdir, which FUSE can deny where java.io.File succeeds. */
    @JvmStatic
    fun createDirectoryPath(path: String?): Boolean {
        if (path.isNullOrEmpty()) return false
        return runCatching {
            val dir = File(path)
            dir.isDirectory || (dir.mkdirs() || dir.isDirectory)
        }.getOrDefault(false)
    }

    /** Fallback for native fopen(O_CREAT), for the same reason as [createDirectoryPath]. */
    @JvmStatic
    fun createFilePath(path: String?): Boolean {
        if (path.isNullOrEmpty()) return false
        return runCatching {
            val file = File(path)
            if (file.isFile) return@runCatching true
            file.parentFile?.takeIf { !it.isDirectory }?.mkdirs()
            file.createNewFile() || file.isFile
        }.getOrDefault(false)
    }
}
