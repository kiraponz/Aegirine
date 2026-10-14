package com.kira3.ametrine.emucore

import android.system.Os
import android.system.OsConstants
import android.util.Log
import android.view.Surface

/**
 * JNI boundary to aegirine-core (emucore/src/main/cpp/jni.cpp). Native code finds this class
 * by name to call the callbacks at the bottom, so its name and members must not change
 * (consumer-rules.pro keeps them).
 *
 * Use [EmulatorCore] instead of calling this directly.
 */
internal object NativeCore {
    private const val TAG = "NativeCore"

    /** Null when the core library loaded; otherwise why it did not. */
    @JvmStatic
    val loadError: String?

    init {
        val pageSize = runCatching { Os.sysconf(OsConstants._SC_PAGESIZE) }.getOrDefault(4096L)
        // The core is built for 4 KiB pages and refuses to start on anything else.
        loadError = if (pageSize != 4096L) {
            "Devices with a $pageSize-byte memory page size are not supported"
        } else {
            try {
                System.loadLibrary("emucore")
                null
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load emucore", e)
                e.message ?: "Failed to load the emulator core"
            }
        }
    }

    /** Receives the core's callbacks. Set by EmulatorCore. */
    @Volatile
    @JvmStatic
    var listener: Listener? = null

    interface Listener {
        fun onVmPausedChanged(paused: Boolean)
    }

    // ---- Setup ----

    /** Once per process. Null on success, or why the core cannot run. */
    @JvmStatic external fun initialize(dataDir: String): String?
    @JvmStatic external fun getBuildVersion(): String

    // ---- Settings ----

    /** Writes one value to the base settings layer. A running VM applies it on [commitSettings]. */
    @JvmStatic external fun setSetting(section: String, key: String, value: String)
    @JvmStatic external fun commitSettings()

    // ---- Surface ----

    /** The surface to render to, or null for none. Blocks until the core has let go of the previous one. */
    @JvmStatic external fun setSurface(surface: Surface?, width: Int, height: Int)
    @JvmStatic external fun setDisplayRefreshRate(hz: Float)

    // ---- VM lifecycle ----

    /**
     * Boots [path] (empty for the BIOS alone) and runs it until the VM shuts down. Blocks the
     * calling thread. Null when it booted, or why it did not.
     */
    @JvmStatic external fun runSystem(path: String): String?
    @JvmStatic external fun hasActiveSystem(): Boolean
    @JvmStatic external fun pause()
    @JvmStatic external fun resume()
    @JvmStatic external fun shutdown()

    // ---- Info ----

    @JvmStatic external fun getFps(): Float
    @JvmStatic external fun getFrameNumber(): Long

    /**
     * "<region>\t<description>" for a PS1 BIOS image, or null. The region is 1 NTSC-J,
     * 2 NTSC-U, 3 PAL, or -1 for an image the core does not know. Takes ownership of [fd].
     */
    @JvmStatic external fun getBiosInfoFromFd(fd: Int): String?

    // ---- Called from native ----
    // These run on core threads; nothing may throw out of them.

    @JvmStatic
    fun onVmPausedChanged(paused: Boolean) {
        runCatching { listener?.onVmPausedChanged(paused) }
            .onFailure { Log.e(TAG, "onVmPausedChanged", it) }
    }
}
