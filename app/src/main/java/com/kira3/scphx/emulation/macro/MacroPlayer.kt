package com.kira3.scphx.emulation.macro

import android.os.SystemClock
import com.kira3.scphx.data.macro.Macro
import com.kira3.scphx.emucore.EmulatorCore
import com.kira3.scphx.emucore.PadButton
import com.kira3.scphx.emucore.PadStick
import com.kira3.scphx.emucore.VmState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Plays a [Macro] into the pad of [port]. The clock only runs while the game does, so opening the
 * menu pauses the macro with the game. Only one plays at a time; playing another replaces it.
 */
class MacroPlayer(private val core: EmulatorCore, private val port: Int, private val scope: CoroutineScope) {
    private val _playing = MutableStateFlow<Macro?>(null)

    /** The macro that is playing, or null. */
    val playing: StateFlow<Macro?> = _playing.asStateFlow()

    private var job: Job? = null
    private val held = HashSet<PadButton>()
    private val sticks = HashSet<PadStick>()

    fun play(macro: Macro) {
        stop()
        if (macro.events.isEmpty()) return
        _playing.value = macro
        job = scope.launch {
            try {
                do {
                    runOnce(macro)
                } while (macro.loop && isActive)
            } finally {
                releaseAll()
                if (_playing.value?.id == macro.id) _playing.value = null
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        releaseAll()
        _playing.value = null
    }

    private suspend fun runOnce(macro: Macro) {
        var next = 0
        var played = 0L
        var last = SystemClock.elapsedRealtime()
        while (next < macro.events.size || played < macro.durationMs) {
            delay(TICK_MS)
            val now = SystemClock.elapsedRealtime()
            if (core.vmState.value == VmState.Running) played += now - last
            last = now
            while (next < macro.events.size && macro.events[next].t <= played) {
                send(macro.events[next++])
            }
        }
        releaseAll()
    }

    private fun send(e: com.kira3.scphx.data.macro.MacroEvent) {
        val button = e.button?.let { name -> PadButton.entries.firstOrNull { it.name == name } }
        if (button != null) {
            if (e.pressed) held += button else held -= button
            core.setPadButton(port, button, e.pressed, e.value)
            return
        }
        val stick = e.stick?.let { name -> PadStick.entries.firstOrNull { it.name == name } } ?: return
        if (e.x != 0f || e.y != 0f) sticks += stick else sticks -= stick
        core.setPadStick(port, stick, e.x, e.y)
    }

    private fun releaseAll() {
        for (b in held) core.setPadButton(port, b, false)
        held.clear()
        for (s in sticks) core.setPadStick(port, s, 0f, 0f)
        sticks.clear()
    }

    private companion object {
        const val TICK_MS = 4L
    }
}
