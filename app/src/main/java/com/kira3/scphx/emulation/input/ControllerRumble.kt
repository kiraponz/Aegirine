package com.kira3.scphx.emulation.input

import android.os.Build
import android.os.CombinedVibration
import android.os.VibrationEffect
import android.view.InputDevice

/**
 * Plays the game's rumble on the controller the player is using. The phone itself does not
 * vibrate: a touch player holds it, and a rumbling phone is more distracting than useful.
 *
 * The core reports a change of motor intensity only when it changes, so each one starts a
 * long one-shot that the next change (or a zero) replaces.
 */
class ControllerRumble(private val deviceId: () -> Int) {
    /** Called on a core thread. Motors are 0..255; the large one dominates the feel. */
    fun rumble(largeMotor: Int, smallMotor: Int) {
        val device = InputDevice.getDevice(deviceId()) ?: return
        val amplitude = maxOf(largeMotor, smallMotor).coerceIn(0, 255)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = device.vibratorManager
                if (manager.vibratorIds.isEmpty()) return
                if (amplitude == 0) {
                    manager.cancel()
                } else {
                    manager.vibrate(CombinedVibration.createParallel(oneShot(amplitude)))
                }
            } else {
                @Suppress("DEPRECATION")
                val vibrator = device.vibrator
                if (!vibrator.hasVibrator()) return
                if (amplitude == 0) vibrator.cancel() else vibrator.vibrate(oneShot(amplitude))
            }
        }
    }

    fun stop() = rumble(0, 0)

    private fun oneShot(amplitude: Int) = VibrationEffect.createOneShot(DURATION_MS, amplitude)

    private companion object {
        /** The core sends nothing while a level holds, so a level held longer than this fades early. */
        const val DURATION_MS = 3000L
    }
}
