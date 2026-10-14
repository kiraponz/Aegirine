package com.kira3.ametrine.emucore

import android.content.Context
import android.net.Uri
import android.system.Os
import android.util.Log
import android.view.Surface
import com.kira3.ametrine.emucore.settings.EmulatorSettings
import com.kira3.ametrine.emucore.settings.SettingsJson
import com.kira3.ametrine.emucore.settings.SettingsOverrides
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The app's only way into the emulation core (aegirine-core). Wraps [NativeCore] so the JNI
 * boundary stays inside this module.
 *
 * One instance per process (the core itself is a process-wide singleton). Call
 * [initialize] once before anything else.
 *
 * Moving from ARMSX2 to aegirine-core is done in steps (docs/CORE.md). What a step has not
 * reached yet does nothing here and says which step brings it ("Step n").
 */
class EmulatorCore(context: Context) {
    private val appContext = context.applicationContext

    /** Memory cards, save states, settings and the core's resources live here. */
    val dataDir: File = appContext.getExternalFilesDir(null) ?: appContext.filesDir

    /**
     * The folder the core loads the BIOS from. The core opens the BIOS with plain file
     * APIs, so a BIOS picked through SAF has to be copied in here.
     */
    val biosDir: File = File(dataDir, "bios")

    /** Where the core keeps its memory cards. */
    val memoryCardsDir: File = File(dataDir, "memcards")

    /** The save states (`<serial> (<CRC>).<slot>.p2s`), and the resume and rewind states. */
    val saveStatesDir: File = File(dataDir, "sstates")

    /** Null when the native core loaded; otherwise a reason it cannot run on this device. */
    val unavailableReason: String? get() = NativeCore.loadError ?: initError

    /** Why [initialize] failed, if it did. */
    @Volatile
    private var initError: String? = null

    private val _vmState = MutableStateFlow(VmState.Stopped)
    val vmState: StateFlow<VmState> = _vmState.asStateFlow()

    /** Called from a core thread when the game drives a pad's motors (0..255 each). Step 3. */
    @Volatile
    var rumbleHandler: ((pad: Int, largeMotor: Int, smallMotor: Int) -> Unit)? = null

    private val initMutex = Mutex()
    private var initialized = false

    /**
     * Installs the core's resources and initializes the core. Safe to call more than once;
     * only the first call does the work. Throws [IllegalStateException] if the core is
     * unavailable ([unavailableReason]).
     */
    suspend fun initialize() = initMutex.withLock {
        if (initialized) return@withLock
        unavailableReason?.let { throw IllegalStateException(it) }

        withContext(Dispatchers.IO) {
            biosDir.mkdirs()
            CoreResources.install(appContext, dataDir)

            NativeCore.listener = object : NativeCore.Listener {
                override fun onVmPausedChanged(paused: Boolean) {
                    val next = if (paused) VmState.Paused else VmState.Running
                    // A late callback after the VM stopped must not revive it.
                    _vmState.update { if (it == VmState.Stopped) it else next }
                }
            }
            NativeCore.initialize(dataDir.absolutePath)?.let { error ->
                initError = error
                throw IllegalStateException(error)
            }
        }
        initialized = true
    }

    val buildVersion: String get() = NativeCore.getBuildVersion()

    // ---- Settings ----

    /**
     * Writes one value to the core's base settings layer (the same section/key names as
     * DuckStation's INI). Changes apply on the next [commitSettings] or VM boot.
     */
    fun setSetting(section: String, key: String, value: Boolean) =
        NativeCore.setSetting(section, key, value.toString())

    fun setSetting(section: String, key: String, value: Int) =
        NativeCore.setSetting(section, key, value.toString())

    fun setSetting(section: String, key: String, value: Float) =
        NativeCore.setSetting(section, key, value.toString())

    fun setSetting(section: String, key: String, value: String) =
        NativeCore.setSetting(section, key, value)

    /**
     * Selects the BIOS the next boot loads: a file name inside [biosDir]. It is used for every
     * console region until each region can have its own (step 5).
     */
    fun setBios(fileName: String) {
        for (key in listOf("PathNTSCJ", "PathNTSCU", "PathPAL")) setSetting("BIOS", key, fileName)
    }

    /** Applies the settings written since the last commit to a running VM. */
    fun commitSettings() = NativeCore.commitSettings()

    private val applyMutex = Mutex()

    @Volatile
    private var lastApplied: EmulatorSettings? = null

    /**
     * Hands the settings to the core: [global], with [overrides] for [serial] (the game about
     * to boot, or the one running). Call it before [runVm], and again whenever settings change
     * while a game runs.
     *
     * The settings are still ARMSX2's; until they are remade for the core (step 5), only the
     * renderer is passed on, and it applies on the next boot.
     */
    suspend fun applySettings(
        global: EmulatorSettings,
        overrides: SettingsOverrides,
        serial: String?,
    ) = withContext(Dispatchers.IO) {
        applyMutex.withLock {
            val effective = SettingsJson.merge(global, overrides)
            setSetting("GPU", "Renderer", rendererName(effective.output.renderer))
            lastApplied = effective
        }
    }

    /** The core's [GPU] Renderer for the app's renderer setting. */
    private fun rendererName(renderer: String): String = when (renderer) {
        "opengl" -> "OpenGL"
        "vulkan" -> "Vulkan"
        "software" -> "Software"
        else -> "Automatic"
    }

    // ---- Fast forward ----

    private val _fastForward = MutableStateFlow(false)

    /** True while the running game is fast-forwarded. Every boot starts at normal speed. */
    val fastForward: StateFlow<Boolean> = _fastForward.asStateFlow()

    /** Fast-forwards the running game, or returns it to normal speed. Step 3. */
    fun setFastForward(on: Boolean) {
        _fastForward.value = on
    }

    // ---- BIOS and games ----

    /**
     * Creates the empty card [name] in [memoryCardsDir]: a folder card, or a file card of [sizeMb]
     * (8, 16, 32 or 64). Needs [initialize]. Blocking I/O. Step 5.
     */
    @Suppress("UNUSED_PARAMETER")
    fun createMemoryCard(name: String, folder: Boolean, sizeMb: Int): Boolean = false

    /** Reads BIOS metadata from [uri], or null if it is not a PS1 BIOS. Blocking I/O. */
    fun readBiosMetadata(uri: Uri): BiosMetadata? {
        val fd = openFd(uri) ?: return null
        val info = NativeCore.getBiosInfoFromFd(fd) ?: return null
        // TODO(step 5): PS1 regions and versions in BiosMetadata itself.
        val region = when (info.substringBefore('\t').toIntOrNull()) {
            1 -> BiosRegion.Japan
            2 -> BiosRegion.Usa
            3 -> BiosRegion.Europe
            else -> BiosRegion.Unknown
        }
        return BiosMetadata(
            versionMajor = 0,
            versionMinor = 0,
            region = region,
            description = info.substringAfter('\t'),
            zone = "",
        )
    }

    /** Reads the serial (e.g. "SLUS-00067") from a disc image, or null if it can't. Blocking I/O. Step 4. */
    @Suppress("UNUSED_PARAMETER")
    fun readGameSerial(uri: Uri): String? = null

    /** The GameDB's name for [serial], or null when the database doesn't know it. Step 4. */
    @Suppress("UNUSED_PARAMETER")
    fun gameDbTitle(serial: String): String? = null

    /**
     * Writes [serial]'s cheats where the core reads them and turns on the ones that are
     * enabled, in the running game. Blocking I/O. Step 6.
     */
    @Suppress("UNUSED_PARAMETER")
    fun applyCheats(serial: String, cheats: List<Cheat>): Boolean = false

    /** Opens [uri] and detaches the fd; the core takes ownership and closes it. */
    private fun openFd(uri: Uri): Int? = try {
        appContext.contentResolver.openFileDescriptor(uri, "r")?.detachFd()
    } catch (e: Exception) {
        Log.w(TAG, "Could not open $uri", e)
        null
    }

    // ---- VM ----

    /**
     * The Vulkan driver the next boot loads: [driver], or the system driver when null.
     * [ubwcFlagHint] turns on Turnip's UBWC flag hint, which recent Turnip builds need on
     * Adreno 740 to draw correctly; other drivers ignore it. Call before [runVm].
     *
     * The core only uses the system driver so far (step 7).
     */
    @Suppress("UNUSED_PARAMETER")
    fun useVulkanDriver(driver: VulkanDriver?, ubwcFlagHint: Boolean) {
        // Mesa reads its options from the environment each time it creates a device.
        runCatching {
            if (ubwcFlagHint) Os.setenv("FD_DEV_FEATURES", "enable_tp_ubwc_flag_hint=1", true)
            else Os.unsetenv("FD_DEV_FEATURES")
        }
    }

    /**
     * Boots [gameUri] (a content:// URI the app holds read permission for, or "" for the BIOS
     * alone) and runs it until the VM shuts down. Blocks; call it on a dedicated emulation
     * thread.
     *
     * Returns null when the game ran and exited normally, or the reason it failed to boot.
     */
    fun runVm(gameUri: String): String? {
        check(initialized) { "EmulatorCore.initialize() has not completed" }
        // TODO(step 4): games, through SAF.
        if (gameUri.isNotEmpty()) return "Starting games is not supported yet; only the BIOS can be started"
        _vmState.value = VmState.Starting
        // The core boots at the speed the settings give it.
        _fastForward.value = false
        return try {
            NativeCore.runSystem(gameUri)
        } finally {
            _vmState.value = VmState.Stopped
        }
    }

    fun pause() = NativeCore.pause()
    fun resume() = NativeCore.resume()

    /** Asks the VM to stop; [runVm] returns once it has. */
    fun shutdown() = NativeCore.shutdown()

    val hasActiveVm: Boolean get() = NativeCore.hasActiveSystem()

    /** From the activity's onStop (true) and onStart (false). Step 3. */
    @Suppress("UNUSED_PARAMETER")
    fun setAppBackgrounded(backgrounded: Boolean) = Unit

    // TODO(step 6): save states, the suspend state, rewind and screenshots.

    @Suppress("UNUSED_PARAMETER")
    fun saveState(slot: Int): Boolean = false

    @Suppress("UNUSED_PARAMETER")
    fun loadState(slot: Int): Boolean = false

    /**
     * Saves the suspend state: where the game stands, for [suspendStateFile] to find the next
     * time the game starts. Step 6.
     */
    fun saveSuspendState(): Boolean = false

    /** The suspend state [serial] left behind, if any. Blocking I/O. Step 6. */
    @Suppress("UNUSED_PARAMETER")
    fun suspendStateFile(serial: String): File? = null

    /** Serials that have a suspend state to resume from. Blocking I/O. Step 6. */
    fun resumableSerials(): Set<String> = emptySet()

    /** Makes the next [runVm] start from the save state at [file], or from the beginning for null. Step 6. */
    @Suppress("UNUSED_PARAMETER")
    fun setBootSaveState(file: File?) = Unit

    /** The points rewind can go back to for the running game, newest first. Blocking I/O. Step 6. */
    fun rewindPoints(): List<SaveStateInfo> = emptyList()

    /** Goes back to the rewind point [n] (see [rewindPoints]). Step 6. */
    @Suppress("UNUSED_PARAMETER")
    fun rewindTo(n: Int): Boolean = false

    /** Forgets the rewind points a game left. Blocking I/O. Step 6. */
    @Suppress("UNUSED_PARAMETER")
    fun clearRewindPoints(serial: String) = Unit

    /** True while the memory card is being written; a save state is refused then. Step 6. */
    val isMemcardBusy: Boolean get() = false

    /** What [slot] holds for the running game, or null when it is empty. Blocking I/O. Step 6. */
    @Suppress("UNUSED_PARAMETER")
    fun saveStateInfo(slot: Int): SaveStateInfo? = null

    /**
     * Captures the next frame the running game presents to [png] (a path ending in .png).
     * Returns straight away; the core writes the file a moment later. Step 6.
     */
    @Suppress("UNUSED_PARAMETER")
    fun saveScreenshot(png: File) = Unit

    val fps: Float get() = NativeCore.getFps()

    /** Frames the game has run (the core's own count). A load state sets it back. */
    val frameCount: Long get() = NativeCore.getFrameNumber()

    /** Why the custom driver set by [useVulkanDriver] did not open, once the VM is up; null if it did or none was set. */
    val customDriverFailure: String? get() = null

    /** The unsigned little-endian number of [size] (1, 2 or 4) bytes at [address], or null if it cannot be read. */
    fun readValue(address: Int, size: Int): Long? {
        val bytes = readMemory(address, size) ?: return null
        var v = 0L
        for (i in size - 1 downTo 0) v = (v shl 8) or (bytes[i].toLong() and 0xFF)
        return v
    }

    /** [length] bytes of main RAM from [address] (the first [MAIN_RAM_BYTES]), or null with no game or out of range. Step 6. */
    @Suppress("UNUSED_PARAMETER")
    fun readMemory(address: Int, length: Int): ByteArray? = null

    /** Writes [value] as [size] (1, 2 or 4) little-endian bytes at [address]. False with no game or out of range. Step 6. */
    @Suppress("UNUSED_PARAMETER")
    fun writeValue(address: Int, size: Int, value: Long): Boolean = false

    /** "00000000" until the game's ELF is running. Step 6 decides whether this stays. */
    val gameCrc: String get() = "00000000"

    // ---- Surface (from SurfaceHolder.Callback) ----

    fun onSurfaceCreated() = Unit
    fun onSurfaceChanged(surface: Surface, width: Int, height: Int) =
        NativeCore.setSurface(surface, width, height)

    /** Returns once the core has stopped rendering to the surface. */
    fun onSurfaceDestroyed() = NativeCore.setSurface(null, 0, 0)
    fun setDisplayRefreshRate(hz: Float) = NativeCore.setDisplayRefreshRate(hz)

    /**
     * How far to shift a top-aligned portrait render down so it clears the display cutout, in
     * surface (buffer) pixels. Step 5.
     */
    @Suppress("UNUSED_PARAMETER")
    fun setPortraitRenderTopInset(pixels: Int) = Unit

    /** Keeps the on-screen messages clear of the display cutout. Surface pixels. Step 5. */
    @Suppress("UNUSED_PARAMETER")
    fun setOsdSafeAreaInsets(left: Int, top: Int, right: Int, bottom: Int) = Unit

    // ---- Input ----

    /** Hears every press and stick move sent below, on the thread that sent it. For recording. */
    interface PadListener {
        fun onButton(port: Int, button: PadButton, pressed: Boolean, pressure: Float)
        fun onStick(port: Int, stick: PadStick, x: Float, y: Float)
    }

    @Volatile
    var padListener: PadListener? = null
    // Call these directly from input handlers, not through a ViewModel, to keep latency low.

    /**
     * Presses or releases [button] on [port] (0 = player 1). [pressure] (0..1) is how hard a
     * pressed button is pushed. Step 3.
     */
    fun setPadButton(port: Int, button: PadButton, pressed: Boolean, pressure: Float = 1f) {
        padListener?.onButton(port, button, pressed, pressure)
    }

    /**
     * Moves [stick] on [port] to ([x], [y]), each -1..1 with +x right and +y down. Shape the
     * input (deadzone) before calling; this sends it as is. Step 3.
     */
    fun setPadStick(port: Int, stick: PadStick, x: Float, y: Float) {
        padListener?.onStick(port, stick, x, y)
    }

    companion object {
        /** Size of the main RAM that [readMemory] reaches. */
        const val MAIN_RAM_BYTES = 2 * 1024 * 1024

        /** The save-state slots, as the core numbers them. */
        val SAVE_STATE_SLOTS: IntRange = 1..10

        private const val TAG = "EmulatorCore"
    }
}
