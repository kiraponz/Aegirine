package com.kira3.scphx.emulation.macro

import com.kira3.scphx.data.macro.Macro
import com.kira3.scphx.data.macro.MacroEvent
import com.kira3.scphx.emucore.EmulatorCore
import com.kira3.scphx.emucore.PadButton
import com.kira3.scphx.emucore.PadStick
import kotlin.math.roundToInt

/**
 * Records what is sent to the pad of [port] while it runs, from the touch pad and a controller
 * alike. Time starts at the first input, so the wait before it is not kept. Time is counted in emulated frames.
 */
class MacroRecorder(private val core: EmulatorCore, private val port: Int) {
    private val events = ArrayList<MacroEvent>()
    private var firstAt = 0L
    private var lastFrame = 0L
    private var frame = 0L
    private val down = HashMap<PadButton, Float>()
    private val moved = HashSet<PadStick>()
    private var running = false

    /** Inputs recorded so far, for the on-screen counter. */
    val count: Int get() = synchronized(events) { events.size }

    private val listener = object : EmulatorCore.PadListener {
        override fun onButton(port: Int, button: PadButton, pressed: Boolean, pressure: Float) {
            if (port != this@MacroRecorder.port) return
            synchronized(events) {
                if (!pressed && !down.containsKey(button)) return
                if (pressed) down[button] = pressure else down.remove(button)
                add(MacroEvent(t = 0, button = button.name, pressed = pressed, value = pressure))
            }
        }

        override fun onStick(port: Int, stick: PadStick, x: Float, y: Float) {
            if (port != this@MacroRecorder.port) return
            synchronized(events) {
                val centered = x == 0f && y == 0f
                if (centered && stick !in moved) return
                if (centered) moved.remove(stick) else moved.add(stick)
                add(MacroEvent(t = 0, stick = stick.name, x = round(x), y = round(y)))
            }
        }
    }

    fun start() {
        synchronized(events) {
            events.clear()
            down.clear()
            moved.clear()
            firstAt = 0L
            lastFrame = core.frameCount
            frame = 0L
        }
        running = true
        core.padListener = listener
    }

    /**
     * Stops recording and returns the macro, or null if nothing was pressed. Whatever is still held
     * is let go at the end, and the last [TAIL_FRAMES] (the time spent reaching the stop button) are dropped.
     */
    fun stop(id: String, name: String): Macro? {
        if (!running) return null
        running = false
        core.padListener = null
        synchronized(events) {
            if (events.isEmpty()) return null
            val end = maxOf(events.last().t, elapsed() - TAIL_FRAMES)
            for (button in down.keys) events += MacroEvent(t = end, button = button.name, pressed = false)
            for (stick in moved) events += MacroEvent(t = end, stick = stick.name)
            return Macro(id, name, end, events.toList(), frames = true)
        }
    }

    fun cancel() {
        running = false
        core.padListener = null
    }

    private fun add(event: MacroEvent) {
        if (events.isEmpty()) firstAt = tick()
        events += event.copy(t = elapsed())
    }

    /** Frames since the first input (0 before it). */
    private fun elapsed(): Long = if (events.isEmpty()) 0L else tick() - firstAt

    /** The frames run since [start]. A load state sends the counter back; that adds nothing. */
    private fun tick(): Long {
        val now = core.frameCount
        if (now > lastFrame) frame += now - lastFrame
        lastFrame = now
        return frame
    }

    private fun round(v: Float) = (v * 1000).roundToInt() / 1000f

    private companion object {
        const val TAIL_FRAMES = 24L
    }
}
