package com.kira3.scphx.emucore

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.Surface
import com.kira3.scphx.emucore.settings.CoreKey
import com.kira3.scphx.emucore.settings.CoreKeys
import com.kira3.scphx.emucore.settings.EmulatorSettings
import com.kira3.scphx.emucore.settings.SettingsEffects
import com.kira3.scphx.emucore.settings.SettingsJson
import com.kira3.scphx.emucore.settings.SettingsOverrides
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kr.co.iefriends.pcsx2.NativeApp
import org.libsdl.app.HIDDeviceManager
import org.libsdl.app.SDLControllerManager
import java.io.File

/**
 * The app's only way into the emulation core. Wraps [NativeApp] so ARMSX2's class names
 * stay inside this module.
 *
 * One instance per process (the core itself is a process-wide singleton). Call
 * [initialize] once before anything else.
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

    /** Null when the native core loaded; otherwise a reason it cannot run on this device. */
    val unavailableReason: String? get() = NativeApp.loadError

    private val _vmState = MutableStateFlow(VmState.Stopped)
    val vmState: StateFlow<VmState> = _vmState.asStateFlow()

    /** Called from a core thread when the game drives a pad's motors (0..255 each). */
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

            NativeApp.context = appContext
            NativeApp.listener = object : NativeApp.Listener {
                override fun onVmPausedChanged(paused: Boolean) {
                    val next = if (paused) VmState.Paused else VmState.Running
                    // A late callback after the VM stopped must not revive it.
                    _vmState.update { if (it == VmState.Stopped) it else next }
                }

                override fun onPadRumble(pad: Int, largeMotor: Int, smallMotor: Int) {
                    rumbleHandler?.invoke(pad, largeMotor, smallMotor)
                }
            }
            NativeApp.initialize(dataDir.absolutePath, biosDir.absolutePath, Build.VERSION.SDK_INT)

            // SDL's Java side, which the core's SDL gamepad input talks to.
            SDLControllerManager.nativeSetupJNI()
            SDLControllerManager.initialize()
            HIDDeviceManager.acquire(appContext)
        }
        initialized = true
    }

    val buildVersion: String get() = NativeApp.getBuildVersion()

    // ---- Settings ----

    /**
     * Writes one value to the core's base settings layer (the same section/key names as
     * PCSX2's INI). Changes apply on the next [commitSettings] or VM boot.
     */
    fun setSetting(section: String, key: String, value: Boolean) =
        NativeApp.setSetting(section, key, "bool", value.toString())

    fun setSetting(section: String, key: String, value: Int) =
        NativeApp.setSetting(section, key, "int", value.toString())

    fun setSetting(section: String, key: String, value: Float) =
        NativeApp.setSetting(section, key, "float", value.toString())

    fun setSetting(section: String, key: String, value: String) =
        NativeApp.setSetting(section, key, "string", value)

    /** Selects the BIOS the next boot loads: a file name inside [biosDir]. */
    fun setBios(fileName: String) = setSetting("Filenames", "BIOS", fileName)

    /** Applies the settings written since the last commit to a running VM. */
    fun commitSettings() = NativeApp.commitSettings()

    private val applyMutex = Mutex()

    @Volatile
    private var lastApplied: EmulatorSettings? = null

    /** What the last boot was given, global and effective; see [applySettings]. */
    private var bootSettings: Pair<EmulatorSettings, EmulatorSettings>? = null

    /**
     * Hands the settings to the core: [global] to its base layer, and [overrides] to the
     * per-game layer of [serial] (the game about to boot, or the one running). Call it before
     * [runVm], and again whenever settings change while a game runs; the running game picks up
     * every change except those in [SettingsEffects.appliesOnNextBoot].
     *
     * Per-game values go in the core's own game layer rather than being merged into the base,
     * because the game database outranks the base layer but not the game layer: a fix the
     * database sets for a game would otherwise silently undo the player's choice for it.
     *
     * [serial] null means no per-game layer (a game whose serial could not be read).
     */
    suspend fun applySettings(
        global: EmulatorSettings,
        overrides: SettingsOverrides,
        serial: String?,
    ) = withContext(Dispatchers.IO) {
        applyMutex.withLock {
            val running = NativeApp.hasActiveVM()
            var base = global
            var effective = SettingsJson.merge(global, overrides)
            val boot = bootSettings
            if (!running) {
                bootSettings = base to effective
            } else if (boot != null) {
                // A commit reconfigures the running VM from these keys, and changing a boot-time
                // option such as the renderer under a running GS makes it rebuild its device,
                // which is not safe mid-game. Keep what it booted with; the next boot gets the rest.
                base = SettingsJson.copyPaths(base, boot.first, SettingsEffects.appliesOnNextBoot)
                effective = SettingsJson.copyPaths(effective, boot.second, SettingsEffects.appliesOnNextBoot)
            }

            val baseKeys = CoreKeys.of(base)
            for (key in CoreKeys.pinned + baseKeys) NativeApp.setSetting(key.section, key.key, key.type, key.value)

            if (serial != null) {
                writeGameLayer(serial, running, CoreKeys.of(effective), baseKeys, overrides)
            } else if (!running) {
                NativeApp.gameIniClearStage()
            }

            // The settings the core takes straight away rather than on commit.
            NativeApp.setAudioVolume(effective.audio.audioVolume.coerceIn(0, 200))
            NativeApp.setAudioMuted(effective.audio.audioMuted)
            NativeApp.setAudioSwapChannels(effective.audio.audioSwapChannels)
            NativeApp.setFpsCap(effective.frameLimit.fpsLimit.coerceIn(0, 1000))
            NativeApp.setPortraitRenderTop(effective.output.portraitRenderTop)
            NativeApp.setLandscapeRenderTop(effective.output.landscapeRenderTop)
            NativeApp.setAffinityMode(effective.output.affinityMode.coerceIn(0, 7))
            NativeApp.setAdpfEnabled(effective.output.performanceHint)
            if (running) {
                NativeApp.speedhackLimitermode(limiterMode(effective))
                NativeApp.setNominalSpeed(effective.frameLimit.nominalSpeedPercent.coerceIn(10, 1000))
                val previous = lastApplied
                if (previous == null || previous.output.framerateNtsc != effective.output.framerateNtsc ||
                    previous.output.frameratePal != effective.output.frameratePal
                ) {
                    // The vsync rate is cached by the pacer; a commit alone does not recompute it.
                    NativeApp.applyFramerateLive(effective.output.framerateNtsc, effective.output.frameratePal)
                }
            }

            // Applies everything to a running VM on its CPU thread, and saves the base layer.
            NativeApp.commitSettings()
            lastApplied = effective
        }
    }

    /**
     * Writes the game's per-game file: every key computed from an overridden field, even where
     * it equals the global value (a key's presence is what makes the game database stand
     * back), plus any key whose value differs from the base layer.
     */
    private fun writeGameLayer(
        serial: String,
        running: Boolean,
        effectiveKeys: List<CoreKey>,
        baseKeys: List<CoreKey>,
        overrides: SettingsOverrides,
    ) {
        val baseValues = baseKeys.associate { it.id to it.value }
        val began = if (running) NativeApp.gameIniBeginWrite() else NativeApp.gameIniBeginStage(serial)
        if (!began) return
        for (key in effectiveKeys) {
            if (key.fields.any { it in overrides } || baseValues[key.id] != key.value) {
                NativeApp.gameIniPut(key.section, key.key, key.value)
            }
        }
        NativeApp.gameIniCommitWrite()
        if (running) NativeApp.reloadGameSettingsLayer()
    }

    // ---- Fast forward ----

    private val _fastForward = MutableStateFlow(false)

    /** True while the running game is fast-forwarded. Every boot starts at normal speed. */
    val fastForward: StateFlow<Boolean> = _fastForward.asStateFlow()

    /** Fast-forwards the running game, or returns it to normal speed. */
    fun setFastForward(on: Boolean) {
        _fastForward.value = on
        val settings = lastApplied ?: return
        if (NativeApp.hasActiveVM()) NativeApp.speedhackLimitermode(limiterMode(settings))
    }

    /** The core's limiter mode for [settings], honouring [fastForward]. */
    private fun limiterMode(settings: EmulatorSettings): Int {
        val frameLimit = settings.frameLimit
        return when {
            !_fastForward.value -> if (frameLimit.frameLimitEnable) LIMITER_NOMINAL else LIMITER_UNLIMITED
            frameLimit.fastForwardSpeed == 0 -> LIMITER_UNLIMITED
            else -> {
                // Turbo reads the scalar when the mode is set, so this goes first.
                NativeApp.setTurboScalar(frameLimit.fastForwardSpeed.coerceIn(2, 10).toFloat())
                LIMITER_TURBO
            }
        }
    }

    // ---- BIOS and games ----

    /** Reads BIOS metadata from [uri], or null if it is not a PS2 BIOS. Blocking I/O. */
    fun readBiosMetadata(uri: Uri): BiosMetadata? {
        val fd = openFd(uri) ?: return null
        val info = NativeApp.getBiosInfoFromFd(fd) ?: return null
        return BiosMetadata(
            versionMajor = (info.version shr 8) and 0xFF,
            versionMinor = info.version and 0xFF,
            region = BiosRegion.fromCode(info.region),
            description = info.description,
            zone = info.zone,
        )
    }

    /**
     * Reads the serial (e.g. "SLUS-20312") from a PS2 disc image, or null if it can't
     * (CSO/ZSO/GZ, unreadable images, and PS1 discs). Blocking I/O.
     */
    fun readGameSerial(uri: Uri): String? {
        val fd = openFd(uri) ?: return null
        // The core tags the serial with the disc's platform: "ps2:SLUS-20312" or "ps1:...".
        val tagged = NativeApp.getGameSerialFromFd(fd) ?: return null
        val platform = tagged.substringBefore(':', missingDelimiterValue = "ps2")
        return tagged.substringAfter(':').takeIf { platform == "ps2" && it.isNotEmpty() }
    }

    /** Opens [uri] and detaches the fd; the core takes ownership and closes it. */
    private fun openFd(uri: Uri): Int? = try {
        appContext.contentResolver.openFileDescriptor(uri, "r")?.detachFd()
    } catch (e: Exception) {
        Log.w(TAG, "Could not open $uri", e)
        null
    }

    // ---- VM ----

    /**
     * Boots [gameUri] (a content:// URI the app holds read permission for) and runs it
     * until the VM shuts down. Blocks; call it on a dedicated emulation thread.
     *
     * Returns null when the game ran and exited normally, or the reason it failed to boot.
     */
    fun runVm(gameUri: String): String? {
        check(initialized) { "EmulatorCore.initialize() has not completed" }
        _vmState.value = VmState.Starting
        // The core boots at the speed the settings give it.
        _fastForward.value = false
        return try {
            // runVMThread returns true even when the boot fails; the reason is left in
            // getLastBootError. It returns false only when the host checks fail first.
            val ok = NativeApp.runVMThread(gameUri)
            NativeApp.getLastBootError().ifEmpty { if (ok) null else "The game could not be started" }
        } finally {
            _vmState.value = VmState.Stopped
        }
    }

    fun pause() = NativeApp.pause()
    fun resume() = NativeApp.resume()

    /** Asks the VM to stop; [runVm] returns once it has. */
    fun shutdown() = NativeApp.shutdown()

    val hasActiveVm: Boolean get() = NativeApp.hasActiveVM()

    /** From the activity's onStop (true) and onStart (false). */
    fun setAppBackgrounded(backgrounded: Boolean) = NativeApp.setAppBackgrounded(backgrounded)

    fun saveState(slot: Int): Boolean = NativeApp.saveStateToSlot(slot)
    fun loadState(slot: Int): Boolean = NativeApp.loadStateFromSlot(slot)

    /** True while the memory card is being written; a save state is refused then. */
    val isMemcardBusy: Boolean get() = NativeApp.isMemcardBusy()

    /** What [slot] holds for the running game, or null when it is empty. Blocking I/O. */
    fun saveStateInfo(slot: Int): SaveStateInfo? {
        val file = NativeApp.getGamePathSlot(slot)?.let(::File)?.takeIf { it.isFile } ?: return null
        return SaveStateInfo(slot, file.lastModified(), NativeApp.getImageSlot(slot))
    }

    /**
     * Captures the next frame the running game presents to [png] (a path ending in .png), at
     * the internal resolution and without the touch pad or OSD. Returns straight away; the
     * core writes the file a moment later.
     */
    fun saveScreenshot(png: File) = NativeApp.saveScreenshot(png.absolutePath)

    val fps: Float get() = NativeApp.getFPS()

    // ---- Surface (from SurfaceHolder.Callback) ----

    fun onSurfaceCreated() = NativeApp.onNativeSurfaceCreated()
    fun onSurfaceChanged(surface: Surface, width: Int, height: Int) =
        NativeApp.onNativeSurfaceChanged(surface, width, height)
    fun onSurfaceDestroyed() = NativeApp.onNativeSurfaceDestroyed()
    fun setDisplayRefreshRate(hz: Float) = NativeApp.setDisplayRefreshRate(hz)

    // ---- Input ----
    // Call these directly from input handlers, not through a ViewModel, to keep latency low.

    /**
     * Presses or releases [button] on [port] (0 = player 1). [pressure] (0..1) is how hard a
     * pressed button is pushed, for the games that read button pressure (or an analog trigger).
     */
    fun setPadButton(port: Int, button: PadButton, pressed: Boolean, pressure: Float = 1f) {
        val range = if (pressed) pressureToRange(pressure) else 0
        NativeApp.setPadButtonForPort(port, button.code, range, pressed && range > 0)
    }

    /**
     * Moves [stick] on [port] to ([x], [y]), each -1..1 with +x right and +y down. Shape the
     * input (deadzone) before calling; this sends it as is.
     */
    fun setPadStick(port: Int, stick: PadStick, x: Float, y: Float) {
        // The core takes each direction as its own half-axis.
        setHalfAxis(port, stick.right, x)
        setHalfAxis(port, stick.left, -x)
        setHalfAxis(port, stick.down, y)
        setHalfAxis(port, stick.up, -y)
    }

    private fun setHalfAxis(port: Int, code: Int, value: Float) {
        val range = if (value > 0f) pressureToRange(value) else 0
        NativeApp.setPadButtonForPort(port, code, range, range > 0)
    }

    /** The core reads 1..32767 as a 0..1 magnitude (0 would mean "fully pressed"). */
    private fun pressureToRange(value: Float): Int =
        (value.coerceIn(0f, 1f) * PAD_RANGE_MAX).toInt()

    companion object {
        /** The save-state slots, as the core numbers them. */
        val SAVE_STATE_SLOTS: IntRange = 1..10

        private const val TAG = "EmulatorCore"
        private const val PAD_RANGE_MAX = 32767

        // NativeApp.speedhackLimitermode values.
        private const val LIMITER_NOMINAL = 0
        private const val LIMITER_TURBO = 1
        private const val LIMITER_UNLIMITED = 3
    }
}
