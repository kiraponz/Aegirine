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

    /** Opens the core's /saf/ paths. Set by EmulatorCore before [initialize]. */
    @Volatile
    @JvmStatic
    var files: SafFiles? = null

    interface Listener {
        fun onVmPausedChanged(paused: Boolean)
        fun onPadRumble(pad: Int, largeMotor: Int, smallMotor: Int)
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

    /**
     * Where the picture sits: at the top of a portrait surface when [portraitTop], [topMargin]
     * surface pixels down (clear of the display cutout), else in the middle.
     */
    @JvmStatic external fun setDisplayLayout(portraitTop: Boolean, topMargin: Int)

    // ---- VM lifecycle ----

    /**
     * Boots [path] (empty for the BIOS alone) and runs it until the VM shuts down, from the save
     * state at [saveState] unless it is empty. Blocks the calling thread. Null when it booted, or
     * why it did not.
     */
    @JvmStatic external fun runSystem(path: String, saveState: String): String?
    @JvmStatic external fun hasActiveSystem(): Boolean
    @JvmStatic external fun pause()
    @JvmStatic external fun resume()
    @JvmStatic external fun shutdown()

    // ---- Input and speed ----

    /** Sets bind [index] (a [PadButton.code], or a half axis after them) of the pad in [port] to [value] (0..1). */
    @JvmStatic external fun setPadBind(port: Int, index: Int, value: Float)

    /** Moves the left or [right] stick of the pad in [port] to ([x], [y]), each -1..1 with +y down. */
    @JvmStatic external fun setPadStick(port: Int, right: Boolean, x: Float, y: Float)
    @JvmStatic external fun setFastForward(enabled: Boolean)

    // ---- Save states, rewind and screenshots ----

    /** Saves the running game to [path]. Blocks. Null when it did, or why not. */
    @JvmStatic external fun saveState(path: String): String?

    /** Loads the state at [path] into the running game. Blocks. Null when it did, or why not. */
    @JvmStatic external fun loadState(path: String): String?

    /** True while a memory card is waiting to be written, which refuses a save state. */
    @JvmStatic external fun isSavingMemoryCards(): Boolean

    /** [serial, PNG screenshot or null] from the header of the state at [path]; null if it is not a state. */
    @JvmStatic external fun getSaveStateInfo(path: String): Array<Any?>?

    /** [ages in ms (LongArray), PNG thumbnails (Array<ByteArray?>)] of the rewind points, newest first. */
    @JvmStatic external fun getRewindPoints(): Array<Any>

    /** Goes back to the rewind point [index] (in [getRewindPoints]). Blocks. */
    @JvmStatic external fun loadRewindPoint(index: Int): Boolean

    /** Writes what is on screen to [path] as a PNG, on a thread of the core's own. */
    @JvmStatic external fun saveScreenshot(path: String)

    // ---- Cheats ----

    /** The cheats to run in every game from now on: [names] with their code lines in [codes]. */
    @JvmStatic external fun setCheats(names: Array<String>, codes: Array<String>)

    /** Alternating names and code lines of the cheats in a cheat file's [text]; null if it has none. */
    @JvmStatic external fun parseCheats(text: String): Array<String>?

    /** Alternating names and code lines of the cheat database's cheats for [serial]; null if it has none. Needs [initialize]. */
    @JvmStatic external fun getDatabaseCheats(serial: String): Array<String>?

    // ---- Memory ----

    /** [length] bytes of main RAM from [address]; null with no game or out of range. Blocks. */
    @JvmStatic external fun readMemory(address: Int, length: Int): ByteArray?

    /** Writes [value] as [size] (1, 2 or 4) bytes at [address] of main RAM. Blocks. */
    @JvmStatic external fun writeMemory(address: Int, size: Int, value: Int): Boolean

    // ---- Info ----

    @JvmStatic external fun getFps(): Float
    @JvmStatic external fun getFrameNumber(): Long

    /** The disc's serial (e.g. "SLUS-00067"), or null if the core cannot read one from [path]. */
    @JvmStatic external fun getGameSerial(path: String): String?

    /** The GameDB's title for [serial], or null. */
    @JvmStatic external fun getGameDbTitle(serial: String): String?

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

    @JvmStatic
    fun safOpen(path: String): Int = runCatching { files?.open(path) }
        .onFailure { Log.e(TAG, "safOpen", it) }.getOrNull() ?: -1

    @JvmStatic
    fun safStat(path: String): LongArray? = runCatching { files?.stat(path) }
        .onFailure { Log.e(TAG, "safStat", it) }.getOrNull()

    @JvmStatic
    fun safList(path: String): Array<String>? = runCatching { files?.list(path) }
        .onFailure { Log.e(TAG, "safList", it) }.getOrNull()

    /** The game set [pad]'s motors (0..255 each); zeros when it stops them or the VM pauses. */
    @JvmStatic
    fun onPadRumble(pad: Int, largeMotor: Int, smallMotor: Int) {
        runCatching { listener?.onPadRumble(pad, largeMotor, smallMotor) }
            .onFailure { Log.e(TAG, "onPadRumble", it) }
    }
}
