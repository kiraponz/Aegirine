package com.kira3.scphx.emulation

import android.content.Context
import android.content.Intent
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.kira3.scphx.R
import com.kira3.scphx.ScphxApplication
import com.kira3.scphx.emucore.EmulatorCore
import com.kira3.scphx.emucore.PadButton
import com.kira3.scphx.emucore.PadStick
import com.kira3.scphx.emucore.VmState
import com.kira3.scphx.emulation.input.ControllerRumble
import com.kira3.scphx.emulation.input.GamepadInput
import com.kira3.scphx.emulation.input.PadSink
import com.kira3.scphx.emulation.input.TouchPad
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val gameUri = IntentCompat.getParcelableExtra(intent, EXTRA_GAME_URI, Uri::class.java)
        if (gameUri == null) {
            finish()
            return
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersiveMode()

        onBackPressedDispatcher.addCallback(this) { exit() }

        touchPadVisible = !isControllerConnected()
        gamepad.onControllerUsed = { touchPadVisible = false }
        core.rumbleHandler = { pad, large, small -> if (pad == PORT) rumble.rumble(large, small) }

        setContent {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                AndroidView(
                    factory = { context ->
                        SurfaceView(context).apply { holder.addCallback(surfaceCallback) }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                TouchPad(
                    sink = touchSink,
                    visible = touchPadVisible,
                    onShowRequest = { touchPadVisible = true },
                )
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

        // Settings are read fresh on every launch rather than shared in memory with
        // MainActivity, so this activity can move to its own process.
        val bios = container.appPreferences.biosFileName.first()
        if (bios == null) {
            finishWithMessage(getString(R.string.emulation_bios_missing))
            return
        }
        core.setBios(bios)

        Log.i(TAG, "Booting $gameUri (serial ${intent.getStringExtra(EXTRA_SERIAL)})")
        emulationThread = thread(name = "Emulation") {
            val error = core.runVm(gameUri.toString())
            runOnUiThread {
                if (error != null) {
                    Log.e(TAG, "Boot failed: $error")
                    finishWithMessage(getString(R.string.emulation_boot_failed, error))
                } else {
                    finish()
                }
            }
        }
    }

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            core.onSurfaceCreated()
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            // Before onSurfaceChanged: the core reads it when it (re)acquires the window.
            displayRefreshRate()?.let(core::setDisplayRefreshRate)
            core.onSurfaceChanged(holder.surface, width, height)
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        gamepad.onKeyEvent(event) || super.dispatchKeyEvent(event)

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        gamepad.onMotionEvent(event) || super.dispatchGenericMotionEvent(event)

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
        private const val EXTRA_GAME_URI = "com.kira3.scphx.extra.GAME_URI"
        private const val EXTRA_SERIAL = "com.kira3.scphx.extra.SERIAL"

        fun intent(context: Context, gameUri: Uri, serial: String?): Intent =
            Intent(context, EmulationActivity::class.java)
                .putExtra(EXTRA_GAME_URI, gameUri)
                .putExtra(EXTRA_SERIAL, serial)
    }
}
