package com.kira3.scphx.emulation

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.kira3.scphx.R
import com.kira3.scphx.ScphxApplication
import com.kira3.scphx.ui.theme.ScphxTheme
import com.kira3.scphx.emucore.EmulatorCore
import com.kira3.scphx.emucore.PadButton
import com.kira3.scphx.emucore.PadStick
import com.kira3.scphx.emucore.VmState
import com.kira3.scphx.emulation.input.ControllerRumble
import com.kira3.scphx.emulation.input.GamepadInput
import com.kira3.scphx.emulation.input.PadSink
import com.kira3.scphx.emulation.input.TouchPad
import com.kira3.scphx.emucore.settings.EmulatorSettings
import com.kira3.scphx.emucore.settings.SettingsJson
import com.kira3.scphx.emucore.settings.SettingsOverrides
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.concurrent.thread

/**
 * Gameplay. Owns the VM for its lifetime: boots the game on a dedicated thread, hands the
 * core its surface, pauses while hidden, and shuts the VM down when the player leaves.
 *
 * Takes only the game's URI and serial from its Intent and reads everything else from
 * storage, so it can later move to its own process.
 */
class EmulationActivity : ComponentActivity() {
    private val container by lazy { (application as ScphxApplication).container }
    private val core: EmulatorCore by lazy { container.emulatorCore }

    private var emulationThread: Thread? = null

    /** True when onStop paused the VM, so onStart knows to resume it. */
    private var pausedWhileHidden = false

    // Input goes straight to the core from the input callbacks, not through a ViewModel.
    private val gamepad by lazy { GamepadInput(core) }
    private val rumble by lazy { ControllerRumble { gamepad.lastDeviceId } }
    private val touchSink by lazy {
        object : PadSink {
            override fun onButton(button: PadButton, pressed: Boolean) =
                core.setPadButton(PORT, button, pressed)

            override fun onStick(stick: PadStick, x: Float, y: Float) =
                core.setPadStick(PORT, stick, x, y)
        }
    }

    /** The touch pad hides while a controller is in use and comes back on a touch. */
    private var touchPadVisible by mutableStateOf(true)

    /** What the game runs with, once read. The fast-forward button and the menu show from it. */
    private var settings by mutableStateOf<EmulatorSettings?>(null)

    private var serial: String? = null

    /** Layout size of the game view; the output buffer is scaled from this. */
    private var viewWidth = 0
    private var viewHeight = 0
    private var surfaceHolder: SurfaceHolder? = null
    private var surfaceView: SurfaceView? = null

    /** Live buffer size (post setFixedSize); the cutout inset is scaled to this. */
    private var bufferWidth = 0
    private var bufferHeight = 0

    /** Holds Triangle+Cross through boot while auto progressive scan is on. */
    private var progressiveScanJob: Job? = null

    private val screenshots by lazy { Screenshots(applicationContext, core) }

    // ---- In-game menu ----

    private var menuOpen by mutableStateOf(false)

    /** True when opening the menu paused the VM, so closing it resumes. */
    private var pausedByMenu = false
    private var saveSlots by mutableStateOf<List<SaveSlot>?>(null)
    private var selectedSlot by mutableStateOf(EmulatorCore.SAVE_STATE_SLOTS.first)

    /** A save or load is running. */
    private var stateBusy by mutableStateOf(false)

    /** Set by Reset: when the VM stops, boot the game again instead of finishing. */
    private var restartRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val gameUri = IntentCompat.getParcelableExtra(intent, EXTRA_GAME_URI, Uri::class.java)
        if (gameUri == null) {
            finish()
            return
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersiveMode()

        serial = intent.getStringExtra(EXTRA_SERIAL)
        onBackPressedDispatcher.addCallback(this) {
            when {
                menuOpen -> if (!stateBusy) closeMenu()
                core.hasActiveVm -> openMenu()
                // Still booting, or the boot failed: nothing to pause.
                else -> exit()
            }
        }

        touchPadVisible = !isControllerConnected()
        gamepad.onControllerUsed = { touchPadVisible = false }
        core.rumbleHandler = { pad, large, small -> if (pad == PORT) rumble.rumble(large, small) }

        setContent {
            ScphxTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    AndroidView(
                        factory = { context ->
                            SurfaceView(context).apply {
                                holder.addCallback(surfaceCallback)
                                surfaceView = this
                            }
                        },
                        modifier = Modifier.fillMaxSize().onSizeChanged {
                            viewWidth = it.width
                            viewHeight = it.height
                            applyOutputScale()
                        },
                    )
                    TouchPad(
                        sink = touchSink,
                        visible = touchPadVisible,
                        onShowRequest = { touchPadVisible = true },
                        fastForwardButton = if (settings?.frameLimit?.fastForwardButton == true) {
                            {
                                val fastForward by core.fastForward.collectAsState()
                                FastForwardButton(active = fastForward, onActiveChange = core::setFastForward)
                            }
                        } else {
                            null
                        },
                    )
                    val current = settings
                    if (menuOpen && current != null) {
                        InGameMenu(current, saveSlots, selectedSlot, stateBusy, menuActions)
                    }
                }
            }
        }

        lifecycleScope.launch { boot(gameUri) }
    }

    private suspend fun boot(gameUri: Uri) {
        try {
            core.initialize()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Core unavailable", e)
            finishWithMessage(getString(R.string.library_message_core_unavailable))
            return
        }

        if (!startVm(gameUri)) return

        // Settings changed during play (from anywhere in this process) reach the running game.
        val settings = container.settingsRepository
        lifecycleScope.launch {
            settings.changes.drop(1).collect {
                if (!core.hasActiveVm) return@collect
                val current = serial?.let { settings.loadOverrides(it) } ?: SettingsOverrides()
                apply(settings.loadGlobal(), current)
                applyEmulationOrientation()
                applyOutputScale()
            }
        }
    }

    /** Reads the settings and boots the game on a new emulation thread. False if it cannot. */
    private suspend fun startVm(gameUri: Uri): Boolean {
        // Settings are read fresh on every boot rather than shared in memory with
        // MainActivity, so this activity can move to its own process.
        val settings = container.settingsRepository
        val overrides = serial?.let { settings.loadOverrides(it) } ?: SettingsOverrides()
        val global = settings.loadGlobal()
        val gameBios = SettingsJson.merge(global, overrides).system.biosFilename
            .takeIf { it.isNotEmpty() && File(core.biosDir, it).isFile }
        val bios = gameBios ?: container.appPreferences.biosFileName.first()
        if (bios == null) {
            finishWithMessage(getString(R.string.emulation_bios_missing))
            return false
        }
        core.setBios(bios)
        apply(global, overrides)
        applyEmulationOrientation()
        applyOutputScale()
        if (SettingsJson.merge(global, overrides).output.autoProgressiveScan) {
            startAutoProgressiveScanHold()
        } else {
            stopAutoProgressiveScanHold()
        }
        useVulkanDriver(global, SettingsJson.merge(global, overrides))

        Log.i(TAG, "Booting $gameUri (serial $serial)")
        emulationThread = thread(name = "Emulation") {
            val error = core.runVm(gameUri.toString())
            runOnUiThread { onVmStopped(gameUri, error) }
        }
        return true
    }

    private fun onVmStopped(gameUri: Uri, error: String?) {
        stopAutoProgressiveScanHold()
        when {
            error != null -> {
                Log.e(TAG, "Boot failed: $error")
                finishWithMessage(getString(R.string.emulation_boot_failed, error))
            }
            restartRequested && !isDestroyed -> {
                restartRequested = false
                lifecycleScope.launch { startVm(gameUri) }
            }
            else -> finish()
        }
    }

    /**
     * Points the core at the custom Vulkan driver this game uses. A game whose driver has been
     * deleted follows the global driver, and the system driver is used when neither is installed.
     */
    private suspend fun useVulkanDriver(global: EmulatorSettings, effective: EmulatorSettings) {
        val installed = container.driverRepository.list()
        fun find(id: String) = installed.firstOrNull { it.id == id }
        val id = effective.output.customDriverId
        val driver = if (id.isEmpty()) null else find(id) ?: find(global.output.customDriverId)
        if (id.isNotEmpty() && driver?.id != id) Log.w(TAG, "Driver $id is not installed, using ${driver?.id ?: "the system driver"}")
        core.useVulkanDriver(driver?.toVulkanDriver(), effective.output.turnipUbwcHint)
    }

    private suspend fun apply(global: EmulatorSettings, overrides: SettingsOverrides) {
        core.applySettings(global, overrides, serial)
        settings = SettingsJson.merge(global, overrides)
    }

    /**
     * Locks the play screen's orientation. Only assigns when it changes: writing
     * requestedOrientation makes Android re-evaluate even an identical value, and each
     * change rebuilds the Vulkan swapchain (ARMSX2 hit multi-second picture freezes).
     */
    private fun applyEmulationOrientation() {
        val effective = settings ?: return
        val requested = when (effective.output.orientation.coerceIn(0, 3)) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            2 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            3 -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        if (requestedOrientation != requested) requestedOrientation = requested
    }

    /** Pins the output buffer size from hwScaler/screenResOverride; see OutputScale.kt. */
    private fun applyOutputScale() {
        val holder = surfaceHolder ?: return
        val effective = settings ?: return
        val buffer = outputBufferSize(viewWidth, viewHeight, effective.output.hwScaler, effective.output.screenResOverride)
        if (buffer == null) {
            bufferWidth = viewWidth
            bufferHeight = viewHeight
            holder.setSizeFromLayout()
        } else {
            bufferWidth = buffer.first
            bufferHeight = buffer.second
            holder.setFixedSize(buffer.first, buffer.second)
        }
        pushDisplayCutoutInset()
    }

    /**
     * Shifts a top-aligned portrait render below the display cutout (punch-hole /
     * notch camera). Top-aligning put the picture directly under the camera, which
     * sat on the game. Sent on creation and every rotation/resize, in the surface
     * pixels the renderer works in.
     */
    private fun pushDisplayCutoutInset() {
        val view = surfaceView ?: return
        // Landscape: the cutout is on a side, so shifting vertically would not help.
        val inset = if (viewHeight > viewWidth && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { view.rootWindowInsets?.displayCutout?.safeInsetTop ?: 0 }.getOrDefault(0)
        } else {
            0
        }
        core.setPortraitRenderTopInset(scaleInsetToBuffer(inset, viewHeight, bufferHeight))
    }

    /**
     * Holds Triangle+Cross through boot, the combo some games probe to offer 480p
     * progressive scan. Purely synthetic pad input; games without the prompt ignore it.
     */
    private fun startAutoProgressiveScanHold() {
        stopAutoProgressiveScanHold()
        // Not on the emulation thread: runVm blocks it for the whole session.
        progressiveScanJob = lifecycleScope.launch(Dispatchers.Default) {
            var held = false
            try {
                var waited = 0L
                while (!core.hasActiveVm && isActive && waited < AUTO_PROGRESSIVE_VM_WAIT_MS) {
                    delay(100)
                    waited += 100
                }
                if (!core.hasActiveVm) return@launch
                held = true
                // Re-assert on a short interval so the game never samples a gap
                // (e.g. across the pad re-init during boot). Stops soon after the
                // game's ELF starts; holding into menus would fight the player.
                var elapsed = 0L
                var sinceElf = -1L
                while (elapsed < AUTO_PROGRESSIVE_HOLD_MS && isActive) {
                    if (!core.hasActiveVm) return@launch
                    core.setPadButton(PORT, PadButton.Triangle, true)
                    core.setPadButton(PORT, PadButton.Cross, true)
                    delay(AUTO_PROGRESSIVE_REASSERT_MS)
                    elapsed += AUTO_PROGRESSIVE_REASSERT_MS
                    val elfRunning = core.gameCrc.let { it.length == 8 && it != "00000000" }
                    if (elfRunning) {
                        sinceElf = if (sinceElf < 0) 0 else sinceElf + AUTO_PROGRESSIVE_REASSERT_MS
                        if (sinceElf >= AUTO_PROGRESSIVE_POST_ELF_MS) break
                    }
                }
            } finally {
                // Release on every exit path, cancellation included.
                if (held && core.hasActiveVm) {
                    core.setPadButton(PORT, PadButton.Triangle, false)
                    core.setPadButton(PORT, PadButton.Cross, false)
                }
            }
        }
    }

    private fun stopAutoProgressiveScanHold() {
        progressiveScanJob?.cancel()
        progressiveScanJob = null
    }

    private fun openMenu() {
        gamepad.releaseAll()
        if (core.vmState.value == VmState.Running) {
            core.pause()
            pausedByMenu = true
        }
        menuOpen = true
        saveSlots = null
        lifecycleScope.launch { saveSlots = readSaveSlots() }
    }

    private fun closeMenu() {
        menuOpen = false
        if (pausedByMenu) {
            pausedByMenu = false
            core.resume()
        }
    }

    private suspend fun readSaveSlots(): List<SaveSlot> = withContext(Dispatchers.IO) {
        EmulatorCore.SAVE_STATE_SLOTS.map { slot ->
            val info = core.saveStateInfo(slot)
            val thumbnail = info?.screenshot?.let { png ->
                BitmapFactory.decodeByteArray(png, 0, png.size)?.asImageBitmap()
            }
            SaveSlot(slot, info?.savedAt, thumbnail)
        }
    }

    private val menuActions = object : InGameMenuActions {
        override fun resume() = closeMenu()

        override fun selectSlot(slot: Int) {
            selectedSlot = slot
        }

        override fun saveState() = runStateAction(
            action = core::saveState,
            done = R.string.emulation_state_saved,
            failed = R.string.emulation_state_save_failed,
        )

        override fun loadState() = runStateAction(
            action = core::loadState,
            done = R.string.emulation_state_loaded,
            failed = R.string.emulation_state_load_failed,
        )

        override fun screenshot() {
            // The core captures the next frame it presents, which a paused game never does.
            closeMenu()
            lifecycleScope.launch {
                val saved = screenshots.capture(serial)
                showMessage(getString(if (saved) R.string.emulation_screenshot_saved else R.string.emulation_screenshot_failed))
            }
        }

        override fun reset() {
            menuOpen = false
            pausedByMenu = false
            restartRequested = true
            core.shutdown()
        }

        override fun exit() {
            menuOpen = false
            pausedByMenu = false
            this@EmulationActivity.exit()
        }

        override fun setShowFps(show: Boolean) = updateSetting("osd.osdShowFps") {
            it.copy(osd = it.osd.copy(osdShowFps = show))
        }

        override fun setFastForwardButton(show: Boolean) = updateSetting("frameLimit.fastForwardButton") {
            it.copy(frameLimit = it.frameLimit.copy(fastForwardButton = show))
        }

        override fun setFastForwardSpeed(speed: Int) = updateSetting("frameLimit.fastForwardSpeed") {
            it.copy(frameLimit = it.frameLimit.copy(fastForwardSpeed = speed))
        }
    }

    /** Saves or loads the selected slot, then returns to the game. */
    private fun runStateAction(action: (Int) -> Boolean, done: Int, failed: Int) {
        val slot = selectedSlot
        stateBusy = true
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { action(slot) }
            stateBusy = false
            when {
                ok -> {
                    showMessage(getString(done, slot))
                    closeMenu()
                }
                // The card only finishes writing while the game runs, so waiting in the menu cannot help.
                core.isMemcardBusy -> showMessage(getString(R.string.emulation_state_memcard_busy))
                else -> showMessage(getString(failed, slot))
            }
        }
    }

    /**
     * Changes a setting where it is set (this game's settings or the global ones). The change
     * reaches the game through the settings collector in [boot].
     */
    private fun updateSetting(path: String, transform: (EmulatorSettings) -> EmulatorSettings) {
        lifecycleScope.launch { container.settingsRepository.updateWhereSet(serial, path, transform) }
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            surfaceHolder = holder
            core.onSurfaceCreated()
            applyOutputScale()
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            // Before onSurfaceChanged: the core reads it when it (re)acquires the window.
            displayRefreshRate()?.let(core::setDisplayRefreshRate)
            core.onSurfaceChanged(holder.surface, width, height)
            // width/height are the live buffer size; keep the cutout inset scaled to it.
            bufferWidth = width
            bufferHeight = height
            pushDisplayCutoutInset()
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            if (surfaceHolder === holder) surfaceHolder = null
            core.onSurfaceDestroyed()
        }
    }

    override fun onStart() {
        super.onStart()
        if (emulationThread == null) return
        core.setAppBackgrounded(false)
        if (pausedWhileHidden) {
            pausedWhileHidden = false
            core.resume()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // While the menu is open a controller navigates it: the D-pad moves focus, A clicks, B goes back.
        if (menuOpen) return super.dispatchKeyEvent(menuKeyEvent(event))
        return gamepad.onKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        // Unhandled hat motion comes back as D-pad key events, which the menu navigates by.
        if (menuOpen) return super.dispatchGenericMotionEvent(event)
        return gamepad.onMotionEvent(event) || super.dispatchGenericMotionEvent(event)
    }

    private fun menuKeyEvent(event: KeyEvent): KeyEvent {
        val keyCode = when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> KeyEvent.KEYCODE_DPAD_CENTER
            KeyEvent.KEYCODE_BUTTON_B -> KeyEvent.KEYCODE_BACK
            else -> return event
        }
        return KeyEvent(
            event.downTime, event.eventTime, event.action, keyCode, event.repeatCount,
            event.metaState, event.deviceId, event.scanCode, event.flags, event.source,
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Key-up events go to whatever has focus, so a button held while focus leaves would
        // stay pressed.
        if (!hasFocus) gamepad.releaseAll()
    }

    override fun onStop() {
        super.onStop()
        rumble.stop()
        if (emulationThread == null) return
        if (core.vmState.value == VmState.Running) {
            core.pause()
            pausedWhileHidden = true
        }
        core.setAppBackgrounded(true)
    }

    override fun onDestroy() {
        super.onDestroy()
        core.rumbleHandler = null
        stopAutoProgressiveScanHold()
        // configChanges keeps rotation from recreating this activity, so being destroyed
        // means the game is over.
        if (emulationThread != null && core.hasActiveVm) core.shutdown()
    }

    /** Stops the VM; the emulation thread finishes the activity once it has shut down. */
    private fun exit() {
        when {
            emulationThread == null -> finish()
            core.hasActiveVm -> core.shutdown()
            // Still booting: the core ignores a stop request until the VM is up.
            else -> Unit
        }
    }

    private fun finishWithMessage(message: String) {
        // Application context: this can run after the activity is already destroyed.
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).run {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun isControllerConnected(): Boolean = InputDevice.getDeviceIds().any { id ->
        val device = InputDevice.getDevice(id) ?: return@any false
        !device.isVirtual && device.supportsSource(InputDevice.SOURCE_GAMEPAD)
    }

    private fun displayRefreshRate(): Float? {
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay
        }
        return display?.refreshRate
    }

    companion object {
        private const val TAG = "EmulationActivity"

        /** Player 1. Both the touch pad and controllers play as player 1 for now. */
        private const val PORT = 0

        /** How long to keep Triangle+Cross held. Some titles probe it well after the PS2 logo. */
        private const val AUTO_PROGRESSIVE_HOLD_MS = 30_000L
        /** How often the hold is re-pressed, so the game never samples a gap. */
        private const val AUTO_PROGRESSIVE_REASSERT_MS = 200L
        /** Let go this long after the game's ELF starts; holding into menus would fight the player. */
        private const val AUTO_PROGRESSIVE_POST_ELF_MS = 8_000L
        /** Pad writes are dropped with no VM, so wait for boot rather than pressing into the void. */
        private const val AUTO_PROGRESSIVE_VM_WAIT_MS = 15_000L
        private const val EXTRA_GAME_URI = "com.kira3.scphx.extra.GAME_URI"
        private const val EXTRA_SERIAL = "com.kira3.scphx.extra.SERIAL"

        fun intent(context: Context, gameUri: Uri, serial: String?): Intent =
            Intent(context, EmulationActivity::class.java)
                .putExtra(EXTRA_GAME_URI, gameUri)
                .putExtra(EXTRA_SERIAL, serial)
    }
}
